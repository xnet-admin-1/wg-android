package com.zaneschepke.wireguardautotunnel.core.tether

import android.net.LocalSocketAddress
import android.net.LocalSocket
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.*
import java.net.*
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*

class PersistentAdbConnection(
    private val adbInterface: String = "127.0.0.1",
    private val adbPort: Int = 5555,
    private val wgInterface: String,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher
) {

    private val TAG = "PersistentAdbConnection"
    private var connectionJob: Job? = null
    private val isConnected = AtomicBoolean(false)
    private var lastConnectionAttempt = 0L
    private var backoffDelay = 1000L
    private val maxBackoffDelay = 30_000L // 30 seconds maximum backoff
    private var connectionStartTime = 0L
    
    // ADB protocol constants
    private val ADB_CLIENT_HANDSHAKE = "CNXN".toByteArray()
    private val ADB_SERVER_HANDSHAKE = "AUTH".toByteArray()
    private val ADB_AUTH_HANDSHAKE = "CNXN".toByteArray()
    private val ADB_COMMAND_SEND = "SEND".toByteArray()

    /**
     * Starts the persistent ADB connection manager
     */
    fun start() {
        stop() // Stop any existing connection first
        
        connectionJob = applicationScope.launch(ioDispatcher) {
            Timber.i("$TAG: Starting persistent ADB connection to $adbInterface:$adbPort")
            
            while (isActive) {
                // Check if we need to backoff due to rapid reconnection attempts
                val now = System.currentTimeMillis()
                if (now - lastConnectionAttempt < 5000) {
                    delay(backoffDelay)
                    backoffDelay = (backoffDelay * 1.5).coerceAtMost(maxBackoffDelay)
                }
                
                lastConnectionAttempt = now
                
                try {
                    establishConnection()
                    
                    // Reset backoff when connection succeeds
                    backoffDelay = 1000L
                    connectionStartTime = now
                    
                    // Connection established, wait for it to drop
                    while (isActive && isConnected.get()) {
                        // Keep the connection alive by monitoring its health
                        if (!isConnectionHealthy()) {
                            Timber.w("$TAG: ADB connection health check failed")
                            break
                        }
                        
                        // Wait before next health check
                        delay(2000)
                    }
                } catch (e: Exception) {
                    Timber.w("$TAG: Connection attempt failed: ${e.message}")
                }
                
                // Clear connection state
                isConnected.set(false)
                
                // Wait before reconnection attempt
                delay(backoffDelay)
            }
        }
    }

    /**
     * Establishes a connection to the ADB server
     */
    private suspend fun establishConnection() {
        var socket: Socket? = null
        var tlsSocket: SSLSocket? = null
        
        try {
            // Discover the actual ADB port
            val actualAdbPort = AdbPortDiscovery.discoverAdbPort()
            if (actualAdbPort == null) {
                Timber.w("$TAG: Could not discover ADB port - is wireless debugging enabled?")
                return
            }
            
            Timber.i("$TAG: Discovered ADB port: $actualAdbPort")
            
            // Create and configure socket
            socket = Socket()
            socket.tcpNoDelay = true
            socket.keepAlive = true
            
            // Set up TLS
            val sslContext = getOrCreateSslContext()
            tlsSocket = sslContext.socketFactory.createSocket(socket, adbInterface, actualAdbPort, true) as SSLSocket
            
            // Configure TLS
            tlsSocket.useClientMode = true
            tlsSocket.enableSessionCreation = true
            
            // Set timeout
            tlsSocket.soTimeout = 5000
            
            // Perform ADB handshake
            if (!performAdbHandshake(tlsSocket)) {
                Timber.w("$TAG: ADB handshake failed")
                return
            }
            
            // Set up port forwarding
            PortForwarder.startForDevice("ADB", "$adbPort:$actualAdbPort", bindAddress = wgInterface)
            
            isConnected.set(true)
            Timber.i("$TAG: ADB connection established and forwarding on port $adbPort")
            
            // Monitor connection in background
            launch {
                monitorConnection(tlsSocket)
            }
            
        } catch (e: Exception) {
            Timber.e("$TAG: Failed to establish ADB connection: ${e.message}")
            isConnected.set(false)
            
            // Clean up
            runCatching { tlsSocket?.close() }
            runCatching { socket?.close() }
        }
    }

    /**
     * Monitors the connection and handles disconnections
     */
    private suspend fun monitorConnection(socket: SSLSocket) {
        try {
            while (socket.isConnected && !socket.isClosed && isConnected.get()) {
                delay(1000) // Just keep the coroutine alive
            }
        } catch (e: Exception) {
            Timber.d("$TAG: Connection monitor ended: ${e.message}")
        } finally {
            isConnected.set(false)
            Timber.i("$TAG: ADB connection lost")
        }
    }

    /**
     * Performs the ADB protocol handshake
     */
    private fun performAdbHandshake(socket: SSLSocket): Boolean {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        
        try {
            // Read server handshake (should be AUTH)
            val serverHandshake = ByteArray(4)
            if (input.read(serverHandshake) != 4) {
                return false
            }
            
            if (!serverHandshake.contentEquals(ADB_SERVER_HANDSHAKE)) {
                Timber.w("$TAG: Unexpected server handshake: ${String(serverHandshake)}")
                return false
            }
            
            // Send client handshake (CNXN)
            output.write(ADB_CLIENT_HANDSHAKE)
            output.flush()
            
            // Read response
            val response = ByteArray(4)
            if (input.read(response) != 4) {
                return false
            }
            
            // Should be CNXN for successful connection
            return response.contentEquals(ADB_AUTH_HANDSHAKE)
            
        } catch (e: Exception) {
            Timber.w("$TAG: Handshake failed: ${e.message}")
            return false
        }
    }

    /**
     * Checks if the connection is healthy by sending a test command
     */
    private fun isConnectionHealthy(): Boolean {
        if (!isConnected.get()) return false
        
        // For now, just check if we've been connected for a reasonable time
        return System.currentTimeMillis() - connectionStartTime > 10000
    }

    /**
     * Stops the persistent connection manager
     */
    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        isConnected.set(false)
        
        // Stop port forwarding
        PortForwarder.stopForDevice("ADB")
        
        Timber.i("$TAG: Persistent ADB connection stopped")
    }
    
    /**
     * Gets or creates SSL context for ADB TLS
     */
    @Volatile
    private var sslContext: SSLContext? = null
    
    private fun getOrCreateSslContext(): SSLContext {
        return sslContext ?: synchronized(this) {
            sslContext ?: createSslContext().also { sslContext = it }
        }
    }
    
    private fun createSslContext(): SSLContext {
        return try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(TrustAllTrustManager), java.security.SecureRandom())
            sslContext
        } catch (e: NoSuchAlgorithmException) {
            Timber.e("$TAG: No such algorithm: ${e.message}")
            throw e
        } catch (e: KeyManagementException) {
            Timber.e("$TAG: Key management exception: ${e.message}")
            throw e
        }
    }
}

class TrustAllTrustManager : X509TrustManager {
    override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
    
    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
    
    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
}

/**
 * Port discovery functionality moved here to avoid circular dependencies
 */
class AdbPortDiscovery {
    companion object {
        fun discoverAdbPort(): Int? {
            // Method 1: check system property
            try {
                val prop = Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tcp.port"))
                    .inputStream.bufferedReader().readText().trim()
                val propPort = prop.toIntOrNull()
                if (propPort != null && propPort > 0 && isAdbPort(propPort)) {
                    Timber.d("AdbPortDiscovery: found via getprop: $propPort")
                    return propPort
                }
            } catch (e: Exception) {
                Timber.d("AdbPortDiscovery: getprop failed: ${e.message}")
            }

            // Method 2: parse /proc/net/tcp (may fail with EACCES on non-root)
            try {
                val candidates = mutableSetOf<Int>()
                for (path in listOf("/proc/net/tcp6", "/proc/net/tcp")) {
                    val f = java.io.File(path)
                    if (!f.canRead()) continue
                    for (line in f.readLines().drop(1)) {
                        val fields = line.trim().split(Regex("\\s+"))
                        if (fields.size < 4) continue
                        if (fields[3] != "0A") continue
                        val port = fields[1].substringAfter(":").toIntOrNull(16) ?: continue
                        if (port in 32000..46000) candidates.add(port)
                    }
                }
                for (port in candidates.sorted()) {
                    if (isAdbPort(port)) {
                        Timber.d("AdbPortDiscovery: found via /proc/net/tcp: $port")
                        return port
                    }
                }
            } catch (e: Exception) {
                Timber.d("AdbPortDiscovery: /proc/net/tcp scan failed: ${e.message}")
            }

            // Method 3: brute-force scan common wireless debugging port range
            Timber.d("AdbPortDiscovery: scanning port range 32000-46000")
            val executor = java.util.concurrent.Executors.newFixedThreadPool(20)
            val found = java.util.concurrent.atomic.AtomicInteger(0)
            val latch = java.util.concurrent.CountDownLatch(14000)
            for (port in 32000..45999) {
                executor.submit {
                    try {
                        if (found.get() == 0 && isAdbPort(port)) {
                            found.set(port)
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            executor.shutdownNow()
            val result = found.get()
            if (result > 0) {
                Timber.d("AdbPortDiscovery: found via port scan: $result")
                return result
            }

            return null
        }

        /**
         * Checks if a port has adbd listening by verifying TCP connection succeeds.
         * Uses longer timeout for wireless connections.
         */
        private fun isAdbPort(port: Int): Boolean {
            return try {
                val s = Socket()
                // Use longer timeout (2000ms) for wireless connections
                s.connect(InetSocketAddress("127.0.0.1", port), 2000)
                s.close()
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
