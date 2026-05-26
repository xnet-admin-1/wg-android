package com.zaneschepke.wireguardautotunnel.core.tether

import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

object RemoteLog {
    private const val TAG = "RemoteLog"
    private const val MAX_LINES = 2000
    private const val PORT = 19981

    private val lines = arrayOfNulls<String>(MAX_LINES)
    private var head = 0
    private var count = 0
    var apiKey: String = ""
        private set
    private var server: ServerSocket? = null

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "${System.currentTimeMillis()} $tag $msg"
        lines[head] = line
        head = (head + 1) % MAX_LINES
        if (count < MAX_LINES) count++
        Log.d(tag, msg)
    }

    @Synchronized
    private fun dump(): String {
        val sb = StringBuilder(count * 80)
        val start = if (count < MAX_LINES) 0 else head
        for (i in 0 until count) {
            sb.append(lines[(start + i) % MAX_LINES]).append('\n')
        }
        return sb.toString()
    }

    fun start() {
        if (server != null) return
        apiKey = UUID.randomUUID().toString().substring(0, 8)
        Log.w(TAG, "Remote log key: $apiKey port: $PORT")
        log(TAG, "Remote log started, key=$apiKey")

        Thread({
            try {
                server = ServerSocket(PORT)
                while (!Thread.currentThread().isInterrupted) {
                    val s: Socket = server!!.accept()
                    try {
                        val r = BufferedReader(InputStreamReader(s.getInputStream()))
                        val reqLine = r.readLine() ?: run { s.close(); continue }
                        val response = when {
                            reqLine.contains("/logs") && reqLine.contains("key=$apiKey") -> {
                                val body = dump()
                                "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n\r\n$body"
                            }
                            reqLine.contains("/clear") && reqLine.contains("key=$apiKey") -> {
                                synchronized(this) { count = 0; head = 0 }
                                "HTTP/1.1 200 OK\r\nContent-Length: 7\r\n\r\ncleared"
                            }
                            else -> "HTTP/1.1 401 Unauthorized\r\nContent-Length: 12\r\n\r\nunauthorized"
                        }
                        s.getOutputStream().write(response.toByteArray())
                        s.getOutputStream().flush()
                        s.close()
                    } catch (_: Exception) {
                        try { s.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }, "RemoteLog").start()
    }
}
