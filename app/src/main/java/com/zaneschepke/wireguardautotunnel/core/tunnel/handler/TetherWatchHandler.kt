package com.zaneschepke.wireguardautotunnel.core.tunnel.handler

import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.state.TunnelState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface

class TetherWatchHandler(
    private val activeTunnels: StateFlow<Map<Int, TunnelState>>,
    private val settingsRepository: GeneralSettingRepository,
    private val backend: Backend,
    applicationScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) {
    private var lastInterfaces = emptySet<String>()

    init {
        Timber.d("TetherWatchHandler: init, backend=${backend::class.simpleName}")
        applicationScope.launch(ioDispatcher) {
            combine(
                activeTunnels.map { it.isNotEmpty() }.distinctUntilChanged(),
                settingsRepository.flow.map { it.isTetherSharingEnabled }.distinctUntilChanged(),
            ) { tunnelActive, tetherEnabled ->
                Timber.d("TetherWatchHandler: tunnelActive=$tunnelActive tetherEnabled=$tetherEnabled")
                tunnelActive && tetherEnabled
            }.distinctUntilChanged().collect { shouldWatch ->
                Timber.d("TetherWatchHandler: shouldWatch=$shouldWatch")
                if (shouldWatch) {
                    while (isActive) {
                        val current = detectTetherInterfaces()
                        if (current != lastInterfaces) {
                            lastInterfaces = current
                            Timber.i("Tether interfaces changed: $current")
                            (backend as? GoBackend)?.refreshTetherNAT()
                        }
                        delay(3000)
                    }
                } else {
                    lastInterfaces = emptySet()
                }
            }
        }
    }

    private fun detectTetherInterfaces(): Set<String> {
        val result = mutableSetOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name
                val isTether = (name.startsWith("wlan") && name != "wlan0")
                    || name.startsWith("swlan") || name.startsWith("ap")
                    || name.startsWith("ncm") || name.startsWith("rndis")
                    || name.startsWith("usb") || name.startsWith("bt-pan")
                    || name.startsWith("bnep")
                if (!isTether) continue
                for (ia in ni.interfaceAddresses) {
                    if (ia.address is Inet4Address) {
                        result.add("${name}:${ia.address.hostAddress}/${ia.networkPrefixLength}")
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }
}
