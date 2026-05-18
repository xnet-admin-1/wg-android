package com.zaneschepke.wireguardautotunnel.core.tether

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import timber.log.Timber

object PortForwarder {

    private val activeForwards = ConcurrentHashMap<String, List<ServerSocket>>()
    var protectSocket: (Socket) -> Unit = {}

    fun parsePorts(spec: String): List<Pair<Int, Int>> {
        if (spec.isBlank()) return emptyList()
        return spec.split(",").flatMap { part ->
            val trimmed = part.trim()
            if ("-" in trimmed) {
                val (lo, hi) = trimmed.split("-", limit = 2).map { it.trim().toInt() }
                (lo..hi).map { it to it }
            } else {
                listOf(trimmed.toInt() to trimmed.toInt())
            }
        }
    }

    fun startForDevice(targetHost: String, portSpec: String) {
        stopForDevice(targetHost)
        val ports = parsePorts(portSpec)
        val servers = mutableListOf<ServerSocket>()

        for ((listenPort, targetPort) in ports) {
            try {
                val ss = ServerSocket(listenPort)
                servers.add(ss)
                Timber.d("Forwarding :$listenPort → $targetHost:$targetPort")

                Thread({
                    try {
                        while (!Thread.interrupted()) {
                            val client = ss.accept()
                            try {
                                val upstream = Socket()
                                protectSocket(upstream)
                                upstream.connect(InetSocketAddress(targetHost, targetPort), 5000)
                                upstream.tcpNoDelay = true
                                client.tcpNoDelay = true
                                pipe(client, upstream)
                                pipe(upstream, client)
                            } catch (e: Exception) {
                                Timber.w("Forward to $targetHost:$targetPort failed: ${e.message}")
                                runCatching { client.close() }
                            }
                        }
                    } catch (e: Exception) {
                        if (!ss.isClosed) Timber.e("Server error on :$listenPort: ${e.message}")
                    }
                }, "PF-$targetHost:$listenPort").apply { isDaemon = true }.start()
            } catch (e: Exception) {
                Timber.e("Bind :$listenPort failed: ${e.message}")
            }
        }
        activeForwards[targetHost] = servers
    }

    fun stopForDevice(targetHost: String) {
        activeForwards.remove(targetHost)?.forEach { runCatching { it.close() } }
    }

    fun stopAll() {
        activeForwards.keys.toList().forEach { stopForDevice(it) }
    }

    fun isActive(targetHost: String): Boolean {
        return activeForwards[targetHost]?.isNotEmpty() == true
    }

    private fun pipe(from: Socket, to: Socket) {
        Thread({
            try {
                val buf = ByteArray(8192)
                val input: InputStream = from.getInputStream()
                val output: OutputStream = to.getOutputStream()
                var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { from.close() }
                runCatching { to.close() }
            }
        }, "pipe").apply { isDaemon = true }.start()
    }
}
