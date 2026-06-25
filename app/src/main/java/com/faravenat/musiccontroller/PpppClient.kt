package com.faravenat.musiccontroller

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

class PpppClient(
    private val onFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    companion object {
        private val PROBE = byteArrayOf(0x2c, 0xba.toByte(), 0x5f, 0x5d)
        private const val DISCOVERY_PORT = 32108
        private const val MSG_PUNCH     = 0x41
        private const val MSG_P2P_RDY   = 0x42
        private const val MSG_DRW       = 0xd0
        private const val MSG_DRW_ACK   = 0xd1
        private const val MSG_ALIVE     = 0xe0
        private const val MSG_ALIVE_ACK = 0xe1
        private val FRAME_MAGIC = byteArrayOf(0x55, 0xaa.toByte(), 0x15, 0xa8.toByte(), 0x03, 0x00)
        private const val FRAME_HEADER_SIZE = 0x20
        private val VIDEO_CMD =
            """{"pro":"stream","cmd":111,"video":1,"user":"admin","pwd":"6666","devmac":"0000"}"""
                .toByteArray()
    }

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(0).also {
                    it.broadcast = true
                    socket = it
                }

                withContext(Dispatchers.Main) { onStatus("Buscando cámara...") }

                // Discovery con reintentos: enviar probe cada 2s hasta 10s
                val broadcasts = probeTargets()
                var peerAddr: InetAddress? = null
                var peerPort = 0
                val punchBuf = ByteArray(2048)
                val punchPkt = DatagramPacket(punchBuf, punchBuf.size)
                val deadline = System.currentTimeMillis() + 10_000

                sock.soTimeout = 2000
                while (peerAddr == null && System.currentTimeMillis() < deadline && isActive) {
                    broadcasts.forEach { addr ->
                        runCatching { sock.send(DatagramPacket(PROBE, PROBE.size, addr, DISCOVERY_PORT)) }
                    }
                    try {
                        punchPkt.setData(punchBuf)
                        sock.receive(punchPkt)
                        if ((punchBuf[1].toInt() and 0xFF) == MSG_PUNCH) {
                            peerAddr = punchPkt.address
                            peerPort = punchPkt.port
                        }
                    } catch (_: java.net.SocketTimeoutException) { /* reintento */ }
                }

                if (peerAddr == null) {
                    withContext(Dispatchers.Main) { onStatus("Cámara no encontrada") }
                    return@launch
                }

                // Echo del PUNCH (hasta 5 veces)
                val punchData = punchBuf.copyOf(punchPkt.length)
                repeat(5) {
                    sock.send(DatagramPacket(punchData, punchData.size, peerAddr, peerPort))
                }

                // Esperar MSG_P2P_RDY
                sock.soTimeout = 5000
                val rdyBuf = ByteArray(256)
                val rdyPkt = DatagramPacket(rdyBuf, rdyBuf.size)
                sock.receive(rdyPkt)

                if ((rdyBuf[1].toInt() and 0xFF) != MSG_P2P_RDY) {
                    withContext(Dispatchers.Main) { onStatus("Cámara no respondió P2P") }
                    return@launch
                }

                // Pedir stream de video
                sendCmd(sock, peerAddr, peerPort, VIDEO_CMD)
                withContext(Dispatchers.Main) { onStatus("Conectado — ${peerAddr.hostAddress}") }

                // Loop de recepción de video
                sock.soTimeout = 3000
                val videoBuf = ByteArray(8192)
                val videoPkt = DatagramPacket(videoBuf, videoBuf.size)
                val frameData = ByteArrayOutputStream(65536)

                while (isActive) {
                    try {
                        videoPkt.setData(videoBuf)
                        sock.receive(videoPkt)
                        handlePacket(videoBuf, videoPkt.length, sock, peerAddr, peerPort, frameData)
                    } catch (_: java.net.SocketTimeoutException) {
                        val alive = byteArrayOf(0xf1.toByte(), MSG_ALIVE.toByte(), 0x00, 0x00)
                        sock.send(DatagramPacket(alive, alive.size, peerAddr, peerPort))
                    }
                }

            } catch (e: Exception) {
                if (isActive) withContext(Dispatchers.Main) { onStatus("Error: ${e.message}") }
            } finally {
                socket?.close()
            }
        }
    }

    // Todos los destinos a probar: clientes ARP reales + broadcasts comunes
    private fun probeTargets(): List<InetAddress> {
        val seen = LinkedHashSet<String>()

        // 1. Clientes conectados al hotspot (tabla ARP del kernel — más confiable)
        runCatching {
            File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                // Flags 0x2 = NUD_REACHABLE (dispositivo activo)
                if (parts.size >= 4 && parts[2] == "0x2") seen.add(parts[0])
            }
        }

        // 2. Broadcast por interfaz de red real
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                if (!iface.isLoopback && iface.isUp) {
                    iface.interfaceAddresses.forEach { ia ->
                        ia.broadcast?.hostAddress?.let { seen.add(it) }
                    }
                }
            }
        }

        // 3. Subredes de hotspot más comunes en Android
        seen += listOf(
            "192.168.43.255", "192.168.49.255",
            "172.20.10.255",  "10.0.0.255",
            "255.255.255.255"
        )
        return seen.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }

    private fun handlePacket(
        buf: ByteArray, len: Int,
        sock: DatagramSocket,
        peer: InetAddress, port: Int,
        frameData: ByteArrayOutputStream
    ) {
        if (len < 4) return
        val msgType = buf[1].toInt() and 0xFF
        val payloadSize = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)

        when (msgType) {
            MSG_DRW -> {
                if (len < 8) return
                val ack = byteArrayOf(
                    0xf1.toByte(), MSG_DRW_ACK.toByte(), 0x00, 0x04,
                    0xd1.toByte(), buf[5], buf[6], buf[7]
                )
                sock.send(DatagramPacket(ack, ack.size, peer, port))

                val channel = buf[5].toInt() and 0xFF
                val dataLen = payloadSize - 4
                if (channel == 1 && dataLen > 0 && 8 + dataLen <= len) {
                    processVideoBytes(buf, 8, dataLen, frameData)
                }
            }
            MSG_ALIVE -> {
                val ack = byteArrayOf(0xf1.toByte(), MSG_ALIVE_ACK.toByte(), 0x00, 0x00)
                sock.send(DatagramPacket(ack, ack.size, peer, port))
            }
        }
    }

    private fun processVideoBytes(buf: ByteArray, start: Int, length: Int, frameData: ByteArrayOutputStream) {
        val end = (start + length).coerceAtMost(buf.size)
        var i = start
        while (i < end) {
            if (i + FRAME_MAGIC.size <= end && matchesMagic(buf, i)) {
                if (frameData.size() > 0) emitFrame(frameData)
                i += FRAME_HEADER_SIZE
            } else {
                frameData.write(buf[i].toInt())
                i++
            }
        }
    }

    private fun matchesMagic(buf: ByteArray, pos: Int): Boolean {
        for (j in FRAME_MAGIC.indices) {
            if (buf[pos + j] != FRAME_MAGIC[j]) return false
        }
        return true
    }

    private fun emitFrame(frameData: ByteArrayOutputStream) {
        val bytes = frameData.toByteArray()
        frameData.reset()
        val jpegStart = bytes.indexOfJpegSoi()
        if (jpegStart >= 0) onFrame(bytes.copyOfRange(jpegStart, bytes.size))
    }

    private fun ByteArray.indexOfJpegSoi(): Int {
        for (i in 0 until size - 1) {
            if (this[i] == 0xFF.toByte() && this[i + 1] == 0xD8.toByte()) return i
        }
        return -1
    }

    private fun sendCmd(sock: DatagramSocket, peer: InetAddress, port: Int, json: ByteArray) {
        val enc = PpppCipher.encode(json)
        val size = 4 + enc.size
        val pkt = ByteArray(8 + enc.size)
        pkt[0] = 0xf1.toByte()
        pkt[1] = 0xd0.toByte()
        pkt[2] = (size shr 8).toByte()
        pkt[3] = (size and 0xFF).toByte()
        pkt[4] = 0xd1.toByte()
        pkt[5] = 0x00
        pkt[6] = 0x00
        pkt[7] = 0x00
        enc.copyInto(pkt, 8)
        sock.send(DatagramPacket(pkt, pkt.size, peer, port))
    }

    fun stop() {
        job?.cancel()
        socket?.close()
        socket = null
    }
}
