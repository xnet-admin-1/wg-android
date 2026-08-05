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
            if (":" in trimmed) {
                val (listen, target) = trimmed.split(":", limit = 2).map { it.trim().toInt() }
                listOf(listen to target)
            } else if ("-" in trimmed) {
                val (lo, hi) = trimmed.split("-", limit = 2).map { it.trim().toInt() }
                (lo..hi).map { it to it }
            } else {
                listOf(trimmed.toInt() to trimmed.toInt())
            }
        }
    }

    // Map to track active connections by target
    private val activeConnections = ConcurrentHashMap<String, MutableSet<ActiveConnection>>()

    data class ActiveConnection(
        val serverSocket: ServerSocket,
        val clientSocket: Socket,
        val upstreamSocket: Socket,
        val inputPipe: Thread,
        val outputPipe: Thread
    )

    fun startForDevice(targetHost: String, portSpec: String, bindAddress: String? = null) {
        stopForDevice(targetHost)
        val ports = parsePorts(portSpec)
        val servers = mutableListOf<ServerSocket>()

        for ((listenPort, targetPort) in ports) {
            try {
                val ss = if (bindAddress != null) {
                    ServerSocket().apply { bind(InetSocketAddress(bindAddress, listenPort)) }
                } else {
                    ServerSocket(listenPort)
                }
                servers.add(ss)
                Timber.d("Forwarding ${bindAddress ?: "0.0.0.0"}:$listenPort → $targetHost:$targetPort")

                val handlerThread = Thread({
                    try {
                        while (!Thread.interrupted()) {
                            val client = ss.accept()
                            
                            // Create upstream connection with protection
                            val upstream = Socket()
                            try {
                                protectSocket(upstream)
                                upstream.connect(InetSocketAddress(targetHost, targetPort), 5000)
                                upstream.tcpNoDelay = true
                                client.tcpNoDelay = true
                                
                                // Create bi-directional pipes
                                val inputPipe = Thread({
                                    try {
                                        val buf = ByteArray(8192)
                                        val input = client.getInputStream()
                                        val output = upstream.getOutputStream()
                                        var n: Int
                                        while (input.read(buf).also { n = it } > 0) {
                                            output.write(buf, 0, n)
                                            output.flush()
                                        }
                                    } catch (_: java.io.IOException) {
                                        // Connection closed normally
                                    } catch (e: Exception) {
                                        Timber.w("Input pipe error: ${e.message}")
                                    } finally {
                                        runCatching { upstream.close() }
                                        runCatching { client.close() }
                                    }
                                }, "pipe-in-${targetHost}:${targetPort}").apply { isDaemon = true }
                                
                                val outputPipe = Thread({
                                    try {
                                        val buf = ByteArray(8192)
                                        val input = upstream.getInputStream()
                                        val output = client.getOutputStream()
                                        var n: Int
                                        while (input.read(buf).also { n = it } > 0) {
                                            output.write(buf, 0, n)
                                            output.flush()
                                        }
                                    } catch (_: java.io.IOException) {
                                        // Connection closed normally
                                    } catch (e: Exception) {
                                        Timber.w("Output pipe error: ${e.message}")
                                    } finally {
                                        runCatching { upstream.close() }
                                        runCatching { client.close() }
                                    }
                                }, "pipe-out-${targetHost}:${targetPort}").apply { isDaemon = true }
                                
                                // Track active connection
                                val connection = ActiveConnection(ss, client, upstream, inputPipe, outputPipe)
                                activeConnections.getOrPut(targetHost) { mutableSetOf() }.add(connection)
                                
                                // Start pipes
                                inputPipe.start()
                                outputPipe.start()
                                
                            } catch (e: Exception) {
                                Timber.w("Forward to $targetHost:$targetPort failed: ${e.message}")
                                runCatching { client.close() }
                                runCatching { upstream.close() }
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
        // Close all server sockets
        activeForwards.remove(targetHost)?.forEach { serverSocket ->
            runCatching { 
                serverSocket.close() 
            }
        }
        
        // Close all active connections
        activeConnections.remove(targetHost)?.forEach { connection ->
            runCatching { connection.clientSocket.close() }
            runCatching { connection.upstreamSocket.close() }
            
            // Interrupt pipes if they're still running
            runCatching { connection.inputPipe.interrupt() }
            runCatching { connection.outputPipe.interrupt() }
        }
        
        Timber.d("PortForwarder: stopped all connections for $targetHost")
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
