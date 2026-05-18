package com.zaneschepke.wireguardautotunnel.core.tether

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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

    private val FALLBACK_SUBNETS = listOf(
        "192.168.42.", "192.168.43.", "192.168.44.", "192.168.49.",
        "172.20.10."
    )

    private var tetherNetwork: Network? = null

    fun findTetheredDevices(ctx: Context, protectSocket: (Socket) -> Unit = {}): List<TetheredDevice> {
        val devices = ConcurrentHashMap<String, TetheredDevice>()

        val tetherIps = findTetherIps(ctx).ifEmpty { findTetherIpsJava() }.ifEmpty {
            FALLBACK_SUBNETS.map { "${it}1" }
        }

        Timber.d("Tether IPs: $tetherIps")

        for (selfIp in tetherIps) {
            probeSubnet(selfIp, devices, protectSocket)
        }

        Timber.d("Total devices found: ${devices.size}")
        return devices.values.toList()
    }

    private fun findTetherIps(ctx: Context): List<String> {
        val ips = mutableListOf<String>()
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(net) ?: continue
                for (la in lp.linkAddresses) {
                    val addr = la.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (isPrivateTether(ip)) {
                            tetherNetwork = net
                            ips.add(ip)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "CM scan failed")
        }
        return ips
    }

    private fun findTetherIpsJava(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return ips
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (isPrivateTether(ip)) ips.add(ip)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Java iface scan failed")
        }
        return ips
    }

    private fun isPrivateTether(ip: String): Boolean {
        // Exclude WG subnets (10.8.x.x typical) — adjust if your WG uses different range
        if (ip.startsWith("10.8.")) return false
        return ip.startsWith("192.168.") || ip.startsWith("172.") || ip.startsWith("10.")
    }

    private fun probeSubnet(
        selfIp: String,
        devices: ConcurrentHashMap<String, TetheredDevice>,
        protectSocket: (Socket) -> Unit
    ) {
        val prefix = selfIp.substringBeforeLast('.') + "."
        Timber.d("Probing $prefix* (self=$selfIp)")

        val executor = Executors.newFixedThreadPool(50)
        val net = tetherNetwork

        for (i in 1..254) {
            val target = "$prefix$i"
            if (target == selfIp) continue
            executor.submit {
                try {
                    val s = Socket()
                    if (net != null) {
                        net.bindSocket(s)
                    } else {
                        protectSocket(s)
                    }
                    s.connect(InetSocketAddress(target, 80), 400)
                    s.close()
                    devices[target] = TetheredDevice(target)
                } catch (_: ConnectException) {
                    // Connection refused = host is alive
                    devices[target] = TetheredDevice(target)
                } catch (_: Exception) {
                    // Timeout or unreachable = no host
                }
            }
        }

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }
}
