package com.faravenat.musiccontroller

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object CloudRelay {
    private const val TAG = "CloudRelay"

    private val RELAY_SERVERS = listOf(
        "119.45.114.92"  to 32100,
        "162.62.63.154"  to 32100,
        "139.155.68.77"  to 32100
    )

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

    // Usa el mismo socket que usará PpppClient para el video — el relay aprende
    // nuestro puerto real, y la cámara puede conectarse de vuelta al socket correcto.
    fun lookup(
        uid: String,
        videoSock: DatagramSocket,
        onStatus: ((String) -> Unit)? = null
    ): Pair<InetAddress, Int>? {
        val localPort = videoSock.localPort

        val uidBytes = uidToBytes(uid)
        val portBytes = byteArrayOf((localPort shr 8).toByte(), (localPort and 0xFF).toByte())
        val lookupPayload = uidBytes + byteArrayOf(0x00, 0x00) + portBytes + ByteArray(12)

        val stunPkt   = buildPacket(0xF1.toByte(), 0x00)
        val lookupPkt = buildPacket(0xF1.toByte(), 0x20, lookupPayload)

        val buf = ByteArray(256)
        val pkt = DatagramPacket(buf, buf.size)

        val prevTimeout = videoSock.soTimeout

        try {
            for ((serverIp, serverPort) in RELAY_SERVERS) {
                val serverAddr = InetAddress.getByName(serverIp)
                Log.d(TAG, "Probando relay $serverIp:$serverPort (localPort=$localPort)")
                onStatus?.invoke("Relay $serverIp...")

                // STUN — enviar y esperar respuesta (pero continuar aunque no llegue)
                videoSock.send(DatagramPacket(stunPkt, stunPkt.size, serverAddr, serverPort))
                videoSock.soTimeout = 3000
                try {
                    pkt.setData(buf); videoSock.receive(pkt)
                    val t = buf[1].toInt() and 0xFF
                    Log.d(TAG, "STUN resp de $serverIp: type=0x${t.toString(16)} len=${pkt.length}")
                } catch (_: java.net.SocketTimeoutException) {
                    Log.d(TAG, "STUN timeout en $serverIp — enviando LOOKUP igual")
                }

                // LOOKUP — enviar siempre
                videoSock.send(DatagramPacket(lookupPkt, lookupPkt.size, serverAddr, serverPort))
                Log.d(TAG, "LOOKUP enviado a $serverIp")

                // Esperar respuesta con IP:puerto de la cámara
                videoSock.soTimeout = 5000
                repeat(5) {
                    try {
                        pkt.setData(buf); videoSock.receive(pkt)
                        val type = buf[1].toInt() and 0xFF
                        val payloadLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
                        Log.d(TAG, "Resp de $serverIp: type=0x${type.toString(16)} payloadLen=$payloadLen len=${pkt.length}")

                        if (buf[0] == 0xF1.toByte() && payloadLen >= 6 && pkt.length >= 10) {
                            val ip   = InetAddress.getByAddress(buf.copyOfRange(4, 8))
                            val port = ((buf[8].toInt() and 0xFF) shl 8) or (buf[9].toInt() and 0xFF)
                            Log.d(TAG, "Posible cámara: ${ip.hostAddress}:$port")
                            if (port > 0 && !ip.isLoopbackAddress) {
                                Log.d(TAG, "¡Cámara encontrada! ${ip.hostAddress}:$port")
                                return ip to port
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        Log.d(TAG, "Timeout resp LOOKUP de $serverIp")
                    }
                }
            }
        } finally {
            videoSock.soTimeout = prevTimeout
        }

        Log.d(TAG, "Relay: no encontrada en ningún servidor")
        return null
    }
}
