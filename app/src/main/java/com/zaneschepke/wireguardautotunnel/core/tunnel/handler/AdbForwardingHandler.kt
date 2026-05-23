package com.zaneschepke.wireguardautotunnel.core.tunnel.handler

import com.zaneschepke.wireguardautotunnel.core.tether.AdbForwarder
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.state.TunnelState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class AdbForwardingHandler(
    private val activeTunnels: StateFlow<Map<Int, TunnelState>>,
    private val settingsRepository: GeneralSettingRepository,
    applicationScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) {
    private var watchJob: Job? = null

    init {
        applicationScope.launch(ioDispatcher) {
            combine(
                activeTunnels.map { it.isNotEmpty() }.distinctUntilChanged(),
                settingsRepository.flow.map { it.isAdbForwardingEnabled }.distinctUntilChanged(),
            ) { tunnelActive, adbEnabled ->
                tunnelActive && adbEnabled
            }.distinctUntilChanged().collect { shouldRun ->
                if (shouldRun) {
                    Timber.i("ADB forwarding: starting port watcher")
                    watchJob?.cancel()
                    watchJob = launch {
                        while (isActive) {
                            AdbForwarder.start()
                            delay(PORT_CHECK_INTERVAL_MS)
                        }
                    }
                } else {
                    Timber.i("ADB forwarding: stopping")
                    watchJob?.cancel()
                    watchJob = null
                    AdbForwarder.stop()
                }
            }
        }
    }

    companion object {
        private const val PORT_CHECK_INTERVAL_MS = 15_000L
    }
}
