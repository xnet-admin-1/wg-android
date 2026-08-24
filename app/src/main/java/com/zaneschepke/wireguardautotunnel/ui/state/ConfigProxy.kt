package com.zaneschepke.wireguardautotunnel.ui.state

import com.wireguard.config.Config

data class ConfigProxy(
    val peers: List<PeerProxy> = emptyList(),
    val `interface`: InterfaceProxy = InterfaceProxy(),
) {

    fun hasScripts(): Boolean {
        return `interface`.preUp.isNotBlank() ||
            `interface`.preDown.isNotBlank() ||
            `interface`.postUp.isNotBlank() ||
            `interface`.postDown.isNotBlank()
    }

    fun buildWgConfig(): Config {
        return Config.Builder()
            .apply {
                addPeers(peers.map { it.toWgPeer() })
                setInterface(`interface`.toWgInterface())
            }
            .build()
    }

    companion object {
        fun from(config: Config): ConfigProxy {
            return ConfigProxy(
                `interface` = InterfaceProxy.from(config.`interface`),
                peers = config.peers.map { PeerProxy.from(it) },
            )
        }
    }
}
