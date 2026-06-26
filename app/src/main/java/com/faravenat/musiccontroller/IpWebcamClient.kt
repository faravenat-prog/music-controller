package com.faravenat.musiccontroller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class IpWebcamClient(
    private val streamUrl: String,
    private val onFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            try {
                val conn = URL(streamUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 0
                conn.connect()

                if (conn.responseCode != 200) {
                    onStatus("Error: HTTP ${conn.responseCode}")
                    return@launch
                }

                onStatus("Conectado")
                val input = conn.inputStream
                val readBuf = ByteArray(65536)
                val frameBuf = ByteArrayOutputStream(65536)
                var inFrame = false
                var prev = -1

                while (isActive) {
                    val n = input.read(readBuf)
                    if (n == -1) break

                    for (i in 0 until n) {
                        val b = readBuf[i].toInt() and 0xFF

                        if (!inFrame) {
                            if (prev == 0xFF && b == 0xD8) {
                                inFrame = true
                                frameBuf.reset()
                                frameBuf.write(0xFF)
                                frameBuf.write(0xD8)
                            }
                        } else {
                            frameBuf.write(b)
                            if (prev == 0xFF && b == 0xD9) {
                                onFrame(frameBuf.toByteArray())
                                inFrame = false
                                frameBuf.reset()
                            }
                        }
                        prev = b
                    }
                }
            } catch (e: Exception) {
                if (isActive) onStatus("Error: ${e.message}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
