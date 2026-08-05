package com.zaneschepke.wireguardautotunnel.core.tunnel.handler

import android.app.Application
import com.zaneschepke.wireguardautotunnel.core.tether.AdbPortDiscovery
import com.zaneschepke.wireguardautotunnel.core.tether.PersistentAdbConnection
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.state.TunnelState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class AdbForwardingHandler(
    private val activeTunnels: StateFlow<Map<Int, TunnelState>>,
    private val settingsRepository: GeneralSettingRepository,
    applicationScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : KoinComponent {

    private val context: Application by inject()
    private var connection: PersistentAdbConnection? = null
    private var connectionJob: Job? = null
    
    init {
        applicationScope.launch(ioDispatcher) {
            combine(
                activeTunnels.map { it.isNotEmpty() }.distinctUntilChanged(),
                settingsRepository.flow.map { it.isAdbForwardingEnabled }.distinctUntilChanged(),
            ) { tunnelActive, adbEnabled ->
                tunnelActive && adbEnabled
            }.distinctUntilChanged().collect { shouldRun ->
                if (shouldRun) {
                    Timber.i("ADB forwarding: starting persistent connection manager")
                    startPersistentConnection(applicationScope, ioDispatcher)
                } else {
                    Timber.i("ADB forwarding: stopping")
                    stopPersistentConnection()
                }
            }
        }
    }

    private fun startPersistentConnection(applicationScope: CoroutineScope, ioDispatcher: CoroutineDispatcher) {
        stopPersistentConnection()
        
        // Find the WireGuard interface IP
        val wgIp = findTunInterfaceIp()
        if (wgIp == null) {
            Timber.w("ADB forwarding: could not find WireGuard interface IP - is tunnel active?")
            return
        }
        
        // Create and start the persistent connection
        connection = PersistentAdbConnection(
            adbInterface = "127.0.0.1",
            adbPort = 5555,
            wgInterface = wgIp,
            applicationScope = applicationScope,
            ioDispatcher = ioDispatcher
        )
        
        connection?.start()
    }

    private fun stopPersistentConnection() {
        connection?.stop()
        connection = null
    }

    /**
     * Finds the IPv4 address of the WireGuard tun interface.
     */
    private fun findTunInterfaceIp(): String? {
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                if (!ni.name.startsWith("tun") && !ni.name.startsWith("wg")) continue
                for (addr in ni.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: java.lang.Exception) {
            Timber.d("ADB forwarding: tun interface scan failed: ${e.message}")
        }
        return null
    }
}