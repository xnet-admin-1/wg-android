package com.zaneschepke.wireguardautotunnel.core.tether

/**
 * Static tether subnet routes. When tether sharing is enabled, these are added
 * to the VPN builder so tethered clients' traffic flows through the WG tunnel.
 */
object TetherRoutes {
    val TETHER_SUBNETS = listOf(
        "192.168.42.0" to 24,  // USB tether
        "192.168.43.0" to 24,  // WiFi hotspot
        "192.168.44.0" to 24,  // Bluetooth tether
        "192.168.49.0" to 24,  // WiFi Direct
        "172.20.10.0" to 24,   // iOS hotspot (when Android tethers to iOS)
    )
}
