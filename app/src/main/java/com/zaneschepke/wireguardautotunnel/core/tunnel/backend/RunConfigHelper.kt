package com.zaneschepke.wireguardautotunnel.core.tunnel.backend

import com.zaneschepke.wireguardautotunnel.domain.events.InvalidConfig
import com.zaneschepke.wireguardautotunnel.domain.model.DnsSettings
import com.zaneschepke.wireguardautotunnel.domain.model.GeneralSettings
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.repository.DnsSettingsRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.ProxySettingsRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import kotlinx.coroutines.flow.firstOrNull

class RunConfigHelper(
    private val settingsRepository: GeneralSettingRepository,
    private val proxySettingsRepository: ProxySettingsRepository,
    private val dnsSettingsRepository: DnsSettingsRepository,
    private val tunnelsRepository: TunnelRepository,
) {

    private data class PrepResult(
        val effectiveConfig: TunnelConfig,
        val generalSettings: GeneralSettings,
        val dnsSettings: DnsSettings,
    )

    private suspend fun prepare(tunnelConfig: TunnelConfig): PrepResult {
        val generalSettings = settingsRepository.getGeneralSettings()
        val dnsSettings = dnsSettingsRepository.getDnsSettings()
        val effectiveConfig =
            if (
                generalSettings.isGlobalSplitTunnelEnabled || dnsSettings.isGlobalTunnelDnsEnabled
            ) {
                val globalConfig =
                    tunnelsRepository.globalTunnelFlow.firstOrNull() ?: throw InvalidConfig()
                tunnelConfig.copyWithGlobalValues(
                    globalConfig,
                    dnsSettings.isGlobalTunnelDnsEnabled,
                    generalSettings.isGlobalSplitTunnelEnabled,
                )
            } else {
                tunnelConfig
            }
        return PrepResult(effectiveConfig, generalSettings, dnsSettings)
    }

    suspend fun buildWgRunConfig(tunnelConfig: TunnelConfig): com.wireguard.config.Config {
        val prep = prepare(tunnelConfig)
        return prep.effectiveConfig.toWgConfig()
    }
}
