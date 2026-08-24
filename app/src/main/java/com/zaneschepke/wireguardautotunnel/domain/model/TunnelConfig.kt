package com.zaneschepke.wireguardautotunnel.domain.model

import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import com.zaneschepke.wireguardautotunnel.data.entity.TunnelConfig.Companion.GLOBAL_CONFIG_NAME
import com.zaneschepke.wireguardautotunnel.util.extensions.defaultName
import com.zaneschepke.wireguardautotunnel.util.extensions.isValidIpv4orIpv6Address
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class TunnelConfig(
    val id: Int = 0,
    val name: String,
    val wgQuick: String,
    val tunnelNetworks: Set<String> = setOf(),
    val isMobileDataTunnel: Boolean = false,
    val isPrimaryTunnel: Boolean = false,
    val isActive: Boolean = false,
    val restartOnPingFailure: Boolean = false,
    var pingTarget: String? = null,
    val isEthernetTunnel: Boolean = false,
    val isIpv4Preferred: Boolean = true,
    val position: Int = 0,
    val autoTunnelApps: Set<String> = setOf(),
    val isMetered: Boolean = false,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TunnelConfig) return false
        return id == other.id &&
            name == other.name &&
            wgQuick == other.wgQuick &&
            isPrimaryTunnel == other.isPrimaryTunnel &&
            isMobileDataTunnel == other.isMobileDataTunnel &&
            isEthernetTunnel == other.isEthernetTunnel &&
            pingTarget == other.pingTarget &&
            restartOnPingFailure == other.restartOnPingFailure &&
            tunnelNetworks == other.tunnelNetworks &&
            isIpv4Preferred == other.isIpv4Preferred &&
            isMetered == other.isMetered
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + name.hashCode()
        result = 31 * result + wgQuick.hashCode()
        return result
    }

    fun isStaticallyConfigured(): Boolean {
        return toWgConfig().peers.all { peer ->
            peer.endpoint.isPresent && peer.endpoint.get().host.isValidIpv4orIpv6Address()
        }
    }

    fun toWgConfig(): Config {
        return configFromWgQuick(wgQuick)
    }

    fun copyWithGlobalValues(
        globalTunnel: TunnelConfig,
        includeDns: Boolean,
        includeSpitTunneling: Boolean,
    ): TunnelConfig {
        val existingConfig = toWgConfig()
        val globalConfig = globalTunnel.toWgConfig()

        val newInterfaceBuilder =
            Interface.Builder().apply {
                setKeyPair(existingConfig.`interface`.keyPair)
                existingConfig.`interface`.addresses.forEach { addAddress(it) }
                existingConfig.`interface`.dnsServers.forEach { addDnsServer(it) }
                existingConfig.`interface`.dnsSearchDomains.forEach { addDnsSearchDomain(it) }
                existingConfig.`interface`.excludedApplications.forEach { excludeApplication(it) }
                existingConfig.`interface`.includedApplications.forEach { includeApplication(it) }
                existingConfig.`interface`.listenPort.ifPresent { setListenPort(it) }
                existingConfig.`interface`.mtu.ifPresent { setMtu(it) }
                existingConfig.`interface`.preUp.forEach { parsePreUp(it) }
                existingConfig.`interface`.postUp.forEach { parsePostUp(it) }
                existingConfig.`interface`.preDown.forEach { parsePreDown(it) }
                existingConfig.`interface`.postDown.forEach { parsePostDown(it) }

                if (includeDns) {
                    // Clear and re-add from global
                    val builder = Interface.Builder()
                    builder.setKeyPair(existingConfig.`interface`.keyPair)
                    globalConfig.`interface`.dnsServers.forEach { addDnsServer(it) }
                    globalConfig.`interface`.dnsSearchDomains.forEach { addDnsSearchDomain(it) }
                }
                if (includeSpitTunneling) {
                    globalConfig.`interface`.excludedApplications.forEach { excludeApplication(it) }
                    globalConfig.`interface`.includedApplications.forEach { includeApplication(it) }
                }
            }

        // Since Interface.Builder uses add-style methods and we can't clear,
        // rebuild cleanly when global values override
        val cleanInterfaceBuilder =
            Interface.Builder().apply {
                setKeyPair(existingConfig.`interface`.keyPair)
                existingConfig.`interface`.addresses.forEach { addAddress(it) }
                existingConfig.`interface`.listenPort.ifPresent { setListenPort(it) }
                existingConfig.`interface`.mtu.ifPresent { setMtu(it) }
                existingConfig.`interface`.preUp.forEach { parsePreUp(it) }
                existingConfig.`interface`.postUp.forEach { parsePostUp(it) }
                existingConfig.`interface`.preDown.forEach { parsePreDown(it) }
                existingConfig.`interface`.postDown.forEach { parsePostDown(it) }

                if (includeDns) {
                    globalConfig.`interface`.dnsServers.forEach { addDnsServer(it) }
                    globalConfig.`interface`.dnsSearchDomains.forEach { addDnsSearchDomain(it) }
                } else {
                    existingConfig.`interface`.dnsServers.forEach { addDnsServer(it) }
                    existingConfig.`interface`.dnsSearchDomains.forEach { addDnsSearchDomain(it) }
                }

                if (includeSpitTunneling) {
                    globalConfig.`interface`.excludedApplications.forEach { excludeApplication(it) }
                    globalConfig.`interface`.includedApplications.forEach { includeApplication(it) }
                } else {
                    existingConfig.`interface`.excludedApplications.forEach { excludeApplication(it) }
                    existingConfig.`interface`.includedApplications.forEach { includeApplication(it) }
                }
            }
        val newInterface = cleanInterfaceBuilder.build()

        val newConfig =
            Config.Builder().apply {
                setInterface(newInterface)
                addPeers(existingConfig.peers)
            }.build()

        return copy(
            wgQuick = newConfig.toWgQuickString(true),
        )
    }

    companion object {
        fun configFromWgQuick(wgQuick: String): Config {
            val inputStream: InputStream = wgQuick.byteInputStream()
            return inputStream.bufferedReader(StandardCharsets.UTF_8).use { Config.parse(it) }
        }

        fun tunnelConfFromQuick(quick: String, name: String? = null): TunnelConfig {
            val config = configFromWgQuick(quick)
            val wgQuick = config.toWgQuickString(true)
            return TunnelConfig(
                name = name ?: config.defaultName(),
                wgQuick = wgQuick,
            )
        }

        fun generateDefaultGlobalConfig(): TunnelConfig {
            val keyPair = KeyPair()
            val config =
                Config.Builder()
                    .apply {
                        setInterface(
                            Interface.Builder()
                                .apply {
                                    setKeyPair(keyPair)
                                    addAddress(InetNetwork.parse("10.0.0.2/32"))
                                }
                                .build()
                        )
                        addPeer(
                            Peer.Builder()
                                .apply {
                                    setPublicKey(keyPair.publicKey)
                                    addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
                                    setEndpoint(InetEndpoint.parse("server.example.com:51820"))
                                }
                                .build()
                        )
                    }
                    .build()
            return TunnelConfig(
                name = GLOBAL_CONFIG_NAME,
                wgQuick = config.toWgQuickString(true),
            )
        }

        private const val IPV6_ALL_NETWORKS = "::/0"
        private const val IPV4_ALL_NETWORKS = "0.0.0.0/0"
        val ALL_IPS = listOf(IPV4_ALL_NETWORKS, IPV6_ALL_NETWORKS)
        val IPV4_PUBLIC_NETWORKS =
            setOf(
                "0.0.0.0/5",
                "8.0.0.0/7",
                "11.0.0.0/8",
                "12.0.0.0/6",
                "16.0.0.0/4",
                "32.0.0.0/3",
                "64.0.0.0/2",
                "128.0.0.0/3",
                "160.0.0.0/5",
                "168.0.0.0/6",
                "172.0.0.0/12",
                "172.32.0.0/11",
                "172.64.0.0/10",
                "172.128.0.0/9",
                "173.0.0.0/8",
                "174.0.0.0/7",
                "176.0.0.0/4",
                "192.0.0.0/9",
                "192.128.0.0/11",
                "192.160.0.0/13",
                "192.169.0.0/16",
                "192.170.0.0/15",
                "192.172.0.0/14",
                "192.176.0.0/12",
                "192.192.0.0/10",
                "193.0.0.0/8",
                "194.0.0.0/7",
                "196.0.0.0/6",
                "200.0.0.0/5",
                "208.0.0.0/4",
            )
        val LAN_BYPASS_ALLOWED_IPS = setOf(IPV6_ALL_NETWORKS) + IPV4_PUBLIC_NETWORKS
    }
}
