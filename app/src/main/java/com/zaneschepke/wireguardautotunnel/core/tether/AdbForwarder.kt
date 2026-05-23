package com.zaneschepke.wireguardautotunnel.core.tether

import timber.log.Timber
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Discovers the wireless debugging (adbd) port and forwards a fixed port (5555)
 * to it, binding only to the WireGuard tunnel interface.
 *
 * Since adbd on Android 11+ binds to all interfaces (including tun), the forwarder
 * provides a stable port (5555) that redirects to adbd's random port.
 */
object AdbForwarder {

    private const val TAG = "AdbForwarder"
    private const val LISTEN_PORT = 5555
    private const val FORWARD_KEY = "127.0.0.1"

    val isRunning: Boolean get() = PortForwarder.isActive(FORWARD_KEY)

    fun start(): Boolean {
        if (isRunning) stop() // restart to pick up port changes
        val adbPort = discoverAdbPort()
        if (adbPort == null) {
            Timber.w("$TAG: could not discover adbd port — is wireless debugging enabled?")
            return false
        }
        if (adbPort == LISTEN_PORT) {
            Timber.i("$TAG: adbd already on port $LISTEN_PORT, no forwarding needed")
            return true
        }
        val wgIp = findTunInterfaceIp()
        if (wgIp == null) {
            Timber.w("$TAG: could not find WireGuard interface IP — is tunnel active?")
            return false
        }
        Timber.i("$TAG: forwarding $wgIp:$LISTEN_PORT → 127.0.0.1:$adbPort")
        PortForwarder.startForDevice(FORWARD_KEY, "$LISTEN_PORT:$adbPort", bindAddress = wgIp)
        return true
    }

    fun stop() {
        PortForwarder.stopForDevice(FORWARD_KEY)
        Timber.i("$TAG: stopped")
    }

    /**
     * Finds the IPv4 address of the WireGuard tun interface.
     */
    fun findTunInterfaceIp(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                if (!ni.name.startsWith("tun") && !ni.name.startsWith("wg")) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Timber.d("$TAG: tun interface scan failed: ${e.message}")
        }
        return null
    }

    /**
     * Discovers the adbd listening port. Tries getprop first, then scans
     * the typical wireless debugging port range by attempting ADB handshakes.
     */
    fun discoverAdbPort(): Int? {
        // Method 1: check system property
        try {
            val prop = Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tcp.port"))
                .inputStream.bufferedReader().readText().trim()
            val propPort = prop.toIntOrNull()
            if (propPort != null && propPort > 0 && isAdbPort(propPort)) {
                Timber.d("$TAG: found via getprop: $propPort")
                return propPort
            }
        } catch (e: Exception) {
            Timber.d("$TAG: getprop failed: ${e.message}")
        }

        // Method 2: parse /proc/net/tcp (may fail with EACCES on non-root)
        try {
            val candidates = mutableSetOf<Int>()
            for (path in listOf("/proc/net/tcp6", "/proc/net/tcp")) {
                val f = File(path)
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
                    Timber.d("$TAG: found via /proc/net/tcp: $port")
                    return port
                }
            }
        } catch (e: Exception) {
            Timber.d("$TAG: /proc/net/tcp scan failed: ${e.message}")
        }

        // Method 3: brute-force scan common wireless debugging port range
        Timber.d("$TAG: scanning port range 32000-46000")
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
            Timber.d("$TAG: found via port scan: $result")
            return result
        }

        return null
    }

    /**
     * Checks if a port has adbd listening by verifying TCP connection succeeds.
     * Wireless debugging uses TLS so we can't check the protocol banner —
     * we just verify the port accepts connections on localhost.
     */
    private fun isAdbPort(port: Int): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", port), 300)
            s.close()
            true
        } catch (_: Exception) {
            false
        }
    }
}
