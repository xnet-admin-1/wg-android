package com.zaneschepke.wireguardautotunnel.core.tether

import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object AdbPortDiscovery {

    fun discoverAdbPort(): Int? {
        // Method 1: check system property
        try {
            val prop = Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tcp.port"))
                .inputStream.bufferedReader().readText().trim()
            val propPort = prop.toIntOrNull()
            if (propPort != null && propPort > 0 && isPortOpen(propPort)) {
                Timber.d("AdbPortDiscovery: found via getprop: $propPort")
                return propPort
            }
        } catch (e: Exception) {
            Timber.d("AdbPortDiscovery: getprop failed: ${e.message}")
        }

        // Method 2: parse /proc/net/tcp for listening ports in wireless debug range
        try {
            val candidates = mutableSetOf<Int>()
            for (path in listOf("/proc/net/tcp6", "/proc/net/tcp")) {
                val f = java.io.File(path)
                if (!f.canRead()) continue
                for (line in f.readLines().drop(1)) {
                    val fields = line.trim().split(Regex("\\s+"))
                    if (fields.size < 4) continue
                    if (fields[3] != "0A") continue // LISTEN state
                    val port = fields[1].substringAfter(":").toIntOrNull(16) ?: continue
                    if (port in 32000..46000) candidates.add(port)
                }
            }
            for (port in candidates.sorted()) {
                if (isPortOpen(port)) {
                    Timber.d("AdbPortDiscovery: found via /proc/net/tcp: $port")
                    return port
                }
            }
        } catch (e: Exception) {
            Timber.d("AdbPortDiscovery: /proc/net/tcp scan failed: ${e.message}")
        }

        // Method 3: brute-force scan common wireless debugging port range
        Timber.d("AdbPortDiscovery: scanning port range 32000-46000")
        val executor = Executors.newFixedThreadPool(20)
        val found = AtomicInteger(0)
        val latch = CountDownLatch(14000)
        for (port in 32000..45999) {
            executor.submit {
                try {
                    if (found.get() == 0 && isPortOpen(port)) {
                        found.set(port)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        executor.shutdownNow()
        val result = found.get()
        if (result > 0) {
            Timber.d("AdbPortDiscovery: found via port scan: $result")
            return result
        }

        return null
    }

    private fun isPortOpen(port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 2000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
