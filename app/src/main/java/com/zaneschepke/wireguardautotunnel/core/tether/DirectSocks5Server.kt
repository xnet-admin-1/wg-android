package com.zaneschepke.wireguardautotunnel.core.tether

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal SOCKS5 server that does direct TCP connect.
 * Connections route through the VPN naturally.
 */
class DirectSocks5Server(private val port: Int = 1080) {
    companion object { private const val TAG = "DirectSocks5" }
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", port))
        }
        Thread({
            while (running) {
                try {
                    val client = server!!.accept()
                    Thread { handleClient(client) }.start()
                } catch (_: Exception) { if (running) break }
            }
        }, "SOCKS5-accept").start()
        Log.i("DirectSocks5", "Listening on 127.0.0.1:$port")
    }

    fun stop() {
        running = false
        server?.close()
    }

    private fun handleClient(client: Socket) {
        try {
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            // Greeting
            val ver = inp.read()
            if (ver != 5) { Log.w(TAG, "bad ver $ver"); client.close(); return }
            val nmethods = inp.read()
            inp.skip(nmethods.toLong())
            out.write(byteArrayOf(5, 0)) // no auth

            // Request
            val buf = ByteArray(4)
            inp.read(buf, 0, 4)
            if (buf[1].toInt() != 1) { Log.w(TAG, "not CONNECT: ${buf[1]}"); client.close(); return }

            val atyp = buf[3].toInt()
            val dstAddr: String
            val dstPort: Int

            when (atyp) {
                1 -> { // IPv4
                    val ip = ByteArray(4); inp.read(ip)
                    dstAddr = ip.joinToString(".") { (it.toInt() and 0xff).toString() }
                    dstPort = (inp.read() shl 8) or inp.read()
                }
                3 -> { // Domain
                    val len = inp.read()
                    val domain = ByteArray(len); inp.read(domain)
                    dstAddr = String(domain)
                    dstPort = (inp.read() shl 8) or inp.read()
                }
                4 -> { // IPv6
                    val ip = ByteArray(16); inp.read(ip)
                    dstAddr = java.net.InetAddress.getByAddress(ip).hostAddress!!
                    dstPort = (inp.read() shl 8) or inp.read()
                }
                else -> { client.close(); return }
            }

            Log.d(TAG, "CONNECT $dstAddr:$dstPort")

            // Direct connect
            val remote = Socket()
            try {
                remote.connect(InetSocketAddress(dstAddr, dstPort), 10000)
                Log.d(TAG, "connected $dstAddr:$dstPort")
            } catch (e: Exception) {
                Log.w(TAG, "connect failed: $dstAddr:$dstPort ${e.message}")
                out.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                client.close(); return
            }

            // Success
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))

            // Relay
            val t1 = Thread { relay(inp, remote.getOutputStream()) }
            val t2 = Thread { relay(remote.getInputStream(), out) }
            t1.start(); t2.start()
            t1.join(); t2.join()
            remote.close()
        } catch (_: Exception) {}
        try { client.close() } catch (_: Exception) {}
    }

    private fun relay(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16384)
        var total = 0
        try {
            while (true) {
                val n = from.read(buf)
                if (n <= 0) break
                to.write(buf, 0, n)
                total += n
                if (total == n) Log.d(TAG, "relay first chunk: $n bytes")
            }
        } catch (_: Exception) {}
        try { to.close() } catch (_: Exception) {}
        Log.d(TAG, "relay done: $total bytes")
    }
}
