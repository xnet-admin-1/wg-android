package com.zaneschepke.wireguardautotunnel.core.tether

import kotlinx.coroutines.*
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PersistentAdbConnection(
    private val adbInterface: String = "127.0.0.1",
    private val adbPort: Int = 5555,
    private val wgInterface: String,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher
) {

    private val TAG = "PersistentAdbConnection"
    private var connectionJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private val discoveredPort = AtomicInteger(0)
    private var backoffDelay = 1000L
    private val maxBackoffDelay = 30_000L

    /**
     * Starts the persistent ADB connection manager.
     * Discovers the local ADB wireless debugging port and forwards
     * wgInterface:5555 → 127.0.0.1:<adb_wireless_port>
     */
    fun start() {
        stop()

        connectionJob = applicationScope.launch(ioDispatcher) {
            Timber.i("$TAG: Starting ADB port forward: $wgInterface:$adbPort → localhost:<adb_port>")
            isRunning.set(true)

            while (isActive) {
                val port = discoverAndVerifyPort()
                if (port == null) {
                    Timber.w("$TAG: Could not discover ADB port, retrying in ${backoffDelay}ms")
                    delay(backoffDelay)
                    backoffDelay = (backoffDelay * 1.5).toLong().coerceAtMost(maxBackoffDelay)
                    continue
                }

                // Port discovered/verified
                if (discoveredPort.get() != port) {
                    Timber.i("$TAG: ADB port discovered: $port")
                    discoveredPort.set(port)
                    restartForward(port)
                    backoffDelay = 1000L
                }

                // Monitor: verify port is still alive
                delay(5000)
                if (!isPortOpen(port)) {
                    Timber.w("$TAG: ADB port $port no longer responding, re-discovering...")
                    PortForwarder.stopForDevice(FORWARD_KEY)
                    discoveredPort.set(0)
                }
            }
        }
    }

    private fun restartForward(targetPort: Int) {
        PortForwarder.stopForDevice(FORWARD_KEY)
        // Forward 0.0.0.0:5555 → 127.0.0.1:<targetPort>
        // This covers both loopback (self-connect) and WG interface (remote access)
        PortForwarder.startForDevice(
            targetHost = adbInterface,
            portSpec = "$adbPort:$targetPort",
            bindAddress = "0.0.0.0"
        )
        Timber.i("$TAG: Forwarding 0.0.0.0:$adbPort → $adbInterface:$targetPort")
    }

    private fun discoverAndVerifyPort(): Int? {
        // If we already have a port, verify it's still open
        val current = discoveredPort.get()
        if (current > 0 && isPortOpen(current)) return current

        // Discover fresh
        return AdbPortDiscovery.discoverAdbPort()
    }

    private fun isPortOpen(port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(adbInterface, port), 2000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Stops the persistent connection manager and cleans up forwarding.
     */
    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        isRunning.set(false)
        discoveredPort.set(0)
        PortForwarder.stopForDevice(FORWARD_KEY)
        Timber.i("$TAG: Stopped")
    }

    companion object {
        private const val FORWARD_KEY = "127.0.0.1"
    }
}
