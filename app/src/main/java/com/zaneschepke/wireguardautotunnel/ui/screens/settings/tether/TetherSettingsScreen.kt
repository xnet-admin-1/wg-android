package com.zaneschepke.wireguardautotunnel.ui.screens.settings.tether

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.PortableWifiOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zaneschepke.wireguardautotunnel.core.tether.DeviceDiscovery
import com.zaneschepke.wireguardautotunnel.core.tether.PortForwarder
import com.zaneschepke.wireguardautotunnel.core.tether.TetheredDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TetherSettingsScreen(
    isTetherEnabled: Boolean,
    onTetherToggle: (Boolean) -> Unit,
    protectSocket: (java.net.Socket) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices = remember { mutableStateListOf<TetheredDevice>() }
    var scanning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null)
                Column {
                    Text("Share VPN with tethered devices", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Route tethered clients through WireGuard",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Switch(checked = isTetherEnabled, onCheckedChange = onTetherToggle)
        }

        if (!isTetherEnabled) return

        // Device list header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tethered Devices", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = {
                scanning = true
                scope.launch {
                    val found = withContext(Dispatchers.IO) {
                        DeviceDiscovery.findTetheredDevices(context, protectSocket)
                    }
                    devices.clear()
                    devices.addAll(found)
                    scanning = false
                }
            }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Scan")
            }
        }

        if (scanning) {
            Text("Scanning...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }

        if (devices.isEmpty() && !scanning) {
            Text("No devices found. Tap refresh to scan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices) { device ->
                DeviceCard(device, protectSocket)
            }
        }
    }
}

@Composable
private fun DeviceCard(device: TetheredDevice, protectSocket: (java.net.Socket) -> Unit) {
    var portSpec by remember { mutableStateOf("") }
    var forwarding by remember { mutableStateOf(PortForwarder.isActive(device.ip)) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DeviceHub, contentDescription = null)
                    Text(device.ip, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = portSpec,
                    onValueChange = { portSpec = it },
                    label = { Text("Ports (e.g. 22,80,8080-8090)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Switch(
                    checked = forwarding,
                    onCheckedChange = { enabled ->
                        if (enabled && portSpec.isNotBlank()) {
                            PortForwarder.protectSocket = protectSocket
                            PortForwarder.startForDevice(device.ip, portSpec)
                            forwarding = true
                        } else {
                            PortForwarder.stopForDevice(device.ip)
                            forwarding = false
                        }
                    },
                )
            }
        }
    }
}
