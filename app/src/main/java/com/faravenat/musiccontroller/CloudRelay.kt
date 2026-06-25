package com.faravenat.musiccontroller

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object CloudRelay {
    // Servidores relay capturados con PCAPdroid
    private val RELAY_SERVERS = listOf(
        "139.155.68.77"  to 32100,
        "119.45.114.92"  to 32100,
        "162.62.63.154"  to 32100
    )

    // Convierte UID de 15 chars a buffer de 20 bytes (formato uid465)
    // Ej: "batg529474bormc" → 4 literal + 8 (pad+hex) + 5 random + 3 pad
    fun uidToBytes(uid: String): ByteArray {
        val literal = uid.substring(0, 4).toByteArray(Charsets.US_ASCII)
        val digit   = uid.substring(4, 10).toLong()
        val random  = uid.substring(10).toByteArray(Charsets.US_ASCII)

        val hex = digit.toString(16).padStart(6, '0')
        val b0 = hex.substring(0, 2).toInt(16).toByte()
        val b1 = hex.substring(2, 4).toInt(16).toByte()
        val b2 = hex.substring(4, 6).toInt(16).toByte()

        return byteArrayOf(
            literal[0], literal[1], literal[2], literal[3],
            0, 0, 0, 0, 0, b0, b1, b2,
            random[0], random[1], random[2], random[3], random[4],
            0, 0, 0
        )
    }

    private fun buildPacket(cmd0: Byte, cmd1: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val pkt = ByteArray(4 + payload.size)
        pkt[0] = cmd0
        pkt[1] = cmd1
        pkt[2] = (payload.size shr 8).toByte()
        pkt[3] = (payload.size and 0xFF).toByte()
        payload.copyInto(pkt, 4)
        return pkt
    }

    // Retorna (cameraIP, cameraPort) o null si no la encuentra
    fun lookup(uid: String): Pair<InetAddress, Int>? {
        val sock = DatagramSocket(0).also { it.soTimeout = 3000 }
        val localPort = sock.localPort

        val uidBytes = uidToBytes(uid)
        val portBytes = byteArrayOf((localPort shr 8).toByte(), (localPort and 0xFF).toByte())
        val lookupPayload = uidBytes + byteArrayOf(0x00, 0x00) + portBytes + ByteArray(12)

        val stunPkt   = buildPacket(0xF1.toByte(), 0x00)
        val lookupPkt = buildPacket(0xF1.toByte(), 0x20, lookupPayload)

        val buf = ByteArray(256)
        val pkt = DatagramPacket(buf, buf.size)

        try {
            for ((serverIp, serverPort) in RELAY_SERVERS) {
                val serverAddr = InetAddress.getByName(serverIp)

                // 1. STUN
                sock.send(DatagramPacket(stunPkt, stunPkt.size, serverAddr, serverPort))
                try {
                    pkt.setData(buf); sock.receive(pkt)
                    if (buf[0] != 0xF1.toByte() || buf[1] != 0x01.toByte()) continue
                } catch (_: java.net.SocketTimeoutException) { continue }

                // 2. LOOKUP
                sock.send(DatagramPacket(lookupPkt, lookupPkt.size, serverAddr, serverPort))

                // 3. Esperar respuesta con IP:puerto de la cámara (hasta 5 paquetes)
                sock.soTimeout = 5000
                repeat(5) attempt@{
                    try {
                        pkt.setData(buf); sock.receive(pkt)
                        val type = buf[1].toInt() and 0xFF
                        // 0x21 = lookup response, 0x40 = direct address
                        if (buf[0] == 0xF1.toByte() && (type == 0x21 || type == 0x40)) {
                            val payloadLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
                            if (payloadLen >= 6) {
                                val ip   = InetAddress.getByAddress(buf.copyOfRange(4, 8))
                                val port = ((buf[8].toInt() and 0xFF) shl 8) or (buf[9].toInt() and 0xFF)
                                if (port > 0 && !ip.isLoopbackAddress) {
                                    return ip to port
                                }
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {}
                }
            }
        } finally {
            runCatching { sock.close() }
        }
        return null
    }
}
