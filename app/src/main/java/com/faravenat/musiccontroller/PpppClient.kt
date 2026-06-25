package com.faravenat.musiccontroller

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer

class PpppClient(
    private val onFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    companion object {
        const val CAMERA_UID = "batg529474bormc"
        private val PROBE = byteArrayOf(0x2c, 0xba.toByte(), 0x5f, 0x5d)
        private const val DISCOVERY_PORT = 32108
        private const val MSG_PUNCH     = 0x41
        private const val MSG_P2P_RDY   = 0x42
        private const val MSG_DRW       = 0xd0
        private const val MSG_DRW_ACK   = 0xd1
        private const val MSG_ALIVE     = 0xe0
        private const val MSG_ALIVE_ACK = 0xe1
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

                // 1. Intentar relay cloud primero (más confiable con hotspot)
                withContext(Dispatchers.Main) { onStatus("Conectando vía relay...") }
                val relayResult = runCatching {
                    CloudRelay.lookup(CAMERA_UID, sock, onStatus)
                }.getOrNull()

                val peer: InetAddress
                val peerPort: Int

                if (relayResult != null) {
                    peer = relayResult.first
                    peerPort = relayResult.second
                    withContext(Dispatchers.Main) { onStatus("Relay OK: ${peer.hostAddress}:$peerPort") }

                    // Handshake igual que discovery local: PROBE → esperar PUNCH → echo → P2P_RDY
                    val punchBuf = ByteArray(2048)
                    val punchPkt = DatagramPacket(punchBuf, punchBuf.size)
                    sock.soTimeout = 5000
                    sock.send(DatagramPacket(PROBE, PROBE.size, peer, peerPort))
                    try {
                        punchPkt.setData(punchBuf)
                        sock.receive(punchPkt)
                        if ((punchBuf[1].toInt() and 0xFF) == MSG_PUNCH) {
                            val echo = punchBuf.copyOf(punchPkt.length)
                            repeat(5) { sock.send(DatagramPacket(echo, echo.size, peer, peerPort)) }
                            punchPkt.setData(punchBuf)
                            sock.receive(punchPkt)  // esperar P2P_RDY (ignoramos si no llega)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // La cámara puede no enviar PUNCH vía relay — continuar igual
                    }
                } else {
                    // 2. Fallback: discovery local por broadcast y ARP
                    withContext(Dispatchers.Main) { onStatus("Buscando en red local...") }
                    val broadcasts = probeTargets()
                    val punchBuf = ByteArray(2048)
                    val punchPkt = DatagramPacket(punchBuf, punchBuf.size)
                    val deadline = System.currentTimeMillis() + 10_000
                    var foundAddr: InetAddress? = null
                    var foundPort = 0

                    sock.soTimeout = 2000
                    while (foundAddr == null && System.currentTimeMillis() < deadline && isActive) {
                        broadcasts.forEach { addr ->
                            runCatching { sock.send(DatagramPacket(PROBE, PROBE.size, addr, DISCOVERY_PORT)) }
                        }
                        try {
                            punchPkt.setData(punchBuf)
                            sock.receive(punchPkt)
                            if ((punchBuf[1].toInt() and 0xFF) == MSG_PUNCH) {
                                foundAddr = punchPkt.address
                                foundPort = punchPkt.port
                            }
                        } catch (_: java.net.SocketTimeoutException) { }
                    }

                    if (foundAddr == null) {
                        withContext(Dispatchers.Main) { onStatus("Cámara no encontrada") }
                        return@launch
                    }

                    // Handshake PUNCH completo
                    val punchData = punchBuf.copyOf(punchPkt.length)
                    repeat(5) {
                        sock.send(DatagramPacket(punchData, punchData.size, foundAddr, foundPort))
                    }
                    sock.soTimeout = 5000
                    val rdyBuf = ByteArray(256)
                    val rdyPkt = DatagramPacket(rdyBuf, rdyBuf.size)
                    sock.receive(rdyPkt)
                    if ((rdyBuf[1].toInt() and 0xFF) != MSG_P2P_RDY) {
                        withContext(Dispatchers.Main) { onStatus("Cámara no respondió P2P") }
                        return@launch
                    }
                    peer = foundAddr
                    peerPort = foundPort
                }

                // Pedir stream de video
                sendCmd(sock, peer, peerPort, VIDEO_CMD)
                withContext(Dispatchers.Main) { onStatus("Conectado — ${peer.hostAddress}") }

                // Loop de recepción de video
                sock.soTimeout = 3000
                val videoBuf = ByteArray(65536)
                val videoPkt = DatagramPacket(videoBuf, videoBuf.size)
                var drwCount = 0

                while (isActive) {
                    try {
                        videoPkt.setData(videoBuf)
                        sock.receive(videoPkt)
                        val gotDrw = handlePacket(videoBuf, videoPkt.length, sock, peer, peerPort)
                        if (gotDrw) {
                            drwCount++
                            if (drwCount == 1)
                                withContext(Dispatchers.Main) { onStatus("Datos de cámara recibidos") }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        val alive = byteArrayOf(0xf1.toByte(), MSG_ALIVE.toByte(), 0x00, 0x00)
                        sock.send(DatagramPacket(alive, alive.size, peer, peerPort))
                    }
                }

            } catch (e: Exception) {
                if (isActive) withContext(Dispatchers.Main) { onStatus("Error: ${e.message}") }
            } finally {
                socket?.close()
            }
        }
    }

    // Todos los destinos a probar: clientes ARP + broadcast calculado por interfaz
    private fun probeTargets(): List<InetAddress> {
        val seen = LinkedHashSet<String>()

        // 1. Tabla ARP — todos los dispositivos vistos recientemente (sin filtrar por flag)
        runCatching {
            File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 2) seen.add(parts[0])
            }
        }

        // 2. Broadcast calculado manualmente desde IP + prefijo de cada interfaz
        // (más confiable que interfaceAddress.broadcast en hotspot Samsung)
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                if (!iface.isLoopback && iface.isUp) {
                    iface.interfaceAddresses.forEach { ia ->
                        val ip = ia.address
                        if (ip is Inet4Address) {
                            val prefix = ia.networkPrefixLength.toInt()
                            val ipInt = ByteBuffer.wrap(ip.address).int
                            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                            val broadcast = ipInt or mask.inv()
                            val bytes = ByteBuffer.allocate(4).putInt(broadcast).array()
                            InetAddress.getByAddress(bytes).hostAddress?.let { seen.add(it) }
                        }
                    }
                }
            }
        }

        // 3. Fallback con subredes comunes de hotspot Android
        seen += listOf(
            "192.168.43.255", "192.168.49.255",
            "172.20.10.255",  "255.255.255.255"
        )
        return seen.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }

    // Estado de máquina para extraer JPEGs del stream continuo
    private var jpegState = 0      // 0=buscando_SOI, 1=got_FF, 2=en_JPEG, 3=got_FF_en_JPEG
    private val jpegBuf = ByteArrayOutputStream(131072)
    private var frameCount = 0
    private var videoCipherPrev = 0  // estado XOR continuo entre paquetes de video

    // Retorna true si recibió un paquete DRW con datos de video
    private fun handlePacket(
        buf: ByteArray, len: Int,
        sock: DatagramSocket,
        peer: InetAddress, port: Int
    ): Boolean {
        if (len < 4) return false
        val msgType = buf[1].toInt() and 0xFF
        val payloadSize = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)

        when (msgType) {
            MSG_DRW -> {
                if (len < 8) return false
                val ack = byteArrayOf(
                    0xf1.toByte(), MSG_DRW_ACK.toByte(), 0x00, 0x04,
                    0xd1.toByte(), buf[5], buf[6], buf[7]
                )
                sock.send(DatagramPacket(ack, ack.size, peer, port))

                val channel = buf[5].toInt() and 0xFF
                val dataLen = payloadSize - 4
                // Aceptar canal 0 y canal 1 — algunas cámaras usan canal 0 para video
                if (dataLen > 0 && 8 + dataLen <= len && (channel == 0 || channel == 1)) {
                    val (dec, newPrev) = PpppCipher.decodeBlock(buf, 8, dataLen, videoCipherPrev)
                    videoCipherPrev = newPrev
                    feedVideoBytes(dec, 0, dec.size)
                    return true
                }
                return false
            }
            MSG_ALIVE -> {
                val ack = byteArrayOf(0xf1.toByte(), MSG_ALIVE_ACK.toByte(), 0x00, 0x00)
                sock.send(DatagramPacket(ack, ack.size, peer, port))
            }
        }
        return false
    }

    // Máquina de estados: detecta FF D8 (SOI) y FF D9 (EOI) para extraer JPEGs completos
    // No depende de los magic bytes del protocolo PPPP (que pueden perderse entre paquetes)
    private fun feedVideoBytes(buf: ByteArray, start: Int, length: Int) {
        val end = minOf(start + length, buf.size)
        for (i in start until end) {
            val b = buf[i].toInt() and 0xFF
            when (jpegState) {
                0 -> if (b == 0xFF) jpegState = 1
                1 -> when (b) {
                    0xD8 -> { jpegBuf.reset(); jpegBuf.write(0xFF); jpegBuf.write(0xD8); jpegState = 2 }
                    0xFF -> {}            // varios FF seguidos — permanecer en estado 1
                    else  -> jpegState = 0
                }
                2 -> { jpegBuf.write(b); if (b == 0xFF) jpegState = 3 }
                3 -> {
                    jpegBuf.write(b)
                    when (b) {
                        0xD9 -> {         // EOI — frame completo
                            frameCount++
                            if (frameCount == 1) onStatus("Primer frame JPEG recibido")
                            onFrame(jpegBuf.toByteArray())
                            jpegBuf.reset()
                            jpegState = 0
                        }
                        0xFF -> {}        // varios FF — permanecer en estado 3
                        else  -> jpegState = 2
                    }
                }
            }
        }
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
