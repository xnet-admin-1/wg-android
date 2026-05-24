package com.zaneschepke.wireguardautotunnel.core.tether

import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import timber.log.Timber

data class TetheredDevice(val ip: String, val name: String = "tethered")

object DeviceDiscovery {

    fun findTetheredDevices(protectSocket: (Socket) -> Unit = {}): List<TetheredDevice> {
        val devices = ConcurrentHashMap<String, TetheredDevice>()
        val tetherIps = findTetherInterfaceIps()

        Timber.d("Tether IPs: $tetherIps")

        for (selfIp in tetherIps) {
            probeSubnet(selfIp, devices, protectSocket)
        }

        Timber.d("Total devices found: ${devices.size}")
        return devices.values.toList()
    }

    private fun findTetherInterfaceIps(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return ips
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name
                val isTether = name.startsWith("wlan") && name != "wlan0"
                        || name.startsWith("swlan") || name.startsWith("ap")
                        || name.startsWith("ncm") || name.startsWith("rndis")
                        || name.startsWith("usb")
                if (!isTether) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Tether iface scan failed")
        }
        return ips
    }

    private fun probeSubnet(
        selfIp: String,
        devices: ConcurrentHashMap<String, TetheredDevice>,
        protectSocket: (Socket) -> Unit
    ) {
        val prefix = selfIp.substringBeforeLast('.') + "."
        Timber.d("Probing $prefix* (self=$selfIp)")

        // First check ARP table for known clients (instant, no network probing)
        try {
            java.io.File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 4 && parts[0].startsWith(prefix) && parts[0] != selfIp) {
                    val flags = parts[2]
                    if (flags != "0x0") { // 0x0 = incomplete/stale
                        devices[parts[0]] = TetheredDevice(parts[0])
                    }
                }
            }
        } catch (_: Exception) {}

        if (devices.isNotEmpty()) return // ARP found clients, skip slow probe

        val executor = Executors.newFixedThreadPool(50)

        for (i in 1..254) {
            val target = "$prefix$i"
            if (target == selfIp) continue
            executor.submit {
                try {
                    val s = Socket()
                    protectSocket(s)
                    s.connect(InetSocketAddress(target, 80), 400)
                    s.close()
                    devices[target] = TetheredDevice(target)
                } catch (_: ConnectException) {
                    devices[target] = TetheredDevice(target)
                } catch (_: Exception) {}
            }
        }

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }
}
