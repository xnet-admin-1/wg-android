package com.zaneschepke.wireguardautotunnel.core.tether

import android.net.VpnService
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds a reference to the active VpnService for socket protection.
 * The GoBackend's VpnService registers itself here when it starts.
 *
 * Usage: VpnProtect.protect(socket) — protects a socket from VPN routing.
 */
object VpnProtect {
    private val vpnService = AtomicReference<VpnService?>(null)

    fun register(service: VpnService) {
        vpnService.set(service)
    }

    fun unregister() {
        vpnService.set(null)
    }

    fun protect(socket: Socket): Boolean {
        return vpnService.get()?.protect(socket) ?: false
    }

    fun protect(socket: DatagramSocket): Boolean {
        return vpnService.get()?.protect(socket) ?: false
    }

    fun protect(fd: Int): Boolean {
        return vpnService.get()?.protect(fd) ?: false
    }

    val isAvailable: Boolean get() = vpnService.get() != null
}
