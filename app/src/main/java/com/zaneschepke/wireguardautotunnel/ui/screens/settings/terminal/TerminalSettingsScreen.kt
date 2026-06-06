package com.zaneschepke.wireguardautotunnel.ui.screens.settings.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adb
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.terminal.ProotBootstrap
import com.zaneschepke.wireguardautotunnel.core.terminal.ProotExecutor
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.button.ThemedSwitch
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun TerminalSettingsScreen(onOpenTerminal: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var isInstalled by remember { mutableStateOf(ProotBootstrap.isInstalled(ctx)) }
    var isWorking by remember { mutableStateOf(false) }
    var statusLog by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var pairingPort by remember { mutableStateOf("") }
    var pairResult by remember { mutableStateOf("") }

    val settingsState by viewModel.container.stateFlow.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Alpine Environment Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Terminal, contentDescription = null)
                    Text("Alpine Terminal", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    if (isInstalled) "Environment ready. Includes adb, shell tools." else "Not installed. Tap Setup to download Alpine Linux (~15MB).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            isWorking = true; statusLog = ""
                            scope.launch(Dispatchers.IO) {
                                val ok = ProotBootstrap.setup(ctx) { line -> statusLog = line }
                                isInstalled = ok; isWorking = false
                            }
                        },
                        enabled = !isWorking && !isInstalled,
                    ) { Text("Setup") }
                    OutlinedButton(
                        onClick = {
                            isWorking = true; statusLog = ""
                            scope.launch(Dispatchers.IO) {
                                val ok = ProotBootstrap.rebuild(ctx) { line -> statusLog = line }
                                isInstalled = ok; isWorking = false
                            }
                        },
                        enabled = !isWorking,
                    ) { Text("Rebuild") }
                    if (isInstalled) {
                        FilledTonalButton(onClick = onOpenTerminal) { Text("Open Terminal") }
                    }
                }
                if (isWorking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (statusLog.isNotBlank()) Text(statusLog, style = MaterialTheme.typography.bodySmall)
            }
        }

        // ADB Self-Pair Card
        if (isInstalled) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("ADB Self-Pair", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Pair with on-device ADB to enable persistent remote access. Open Wireless Debugging → Pair device, then enter the code and port below.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = pairingPort, onValueChange = { pairingPort = it.filter { c -> c.isDigit() } },
                            label = { Text("Port") }, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                        )
                        OutlinedTextField(
                            value = pairingCode, onValueChange = { pairingCode = it },
                            label = { Text("Pairing Code") }, modifier = Modifier.weight(1.5f),
                            singleLine = true,
                        )
                    }
                    Button(
                        onClick = {
                            isWorking = true; pairResult = "Pairing..."
                            scope.launch(Dispatchers.IO) {
                                pairResult = pairAndPersist(ctx, pairingPort, pairingCode)
                                isWorking = false
                            }
                        },
                        enabled = !isWorking && pairingCode.isNotBlank() && pairingPort.isNotBlank(),
                    ) { Text("Pair & Persist ADB") }
                    if (pairResult.isNotBlank()) {
                        Text(pairResult, style = MaterialTheme.typography.bodySmall,
                            color = if (pairResult.contains("Success")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // ADB Forwarding Toggle
        if (!settingsState.isLoading) {
            SurfaceRow(
                leading = { Icon(Icons.Filled.Adb, contentDescription = null) },
                trailing = {
                    ThemedSwitch(
                        checked = settingsState.settings.isAdbForwardingEnabled,
                        onClick = { viewModel.setAdbForwardingEnabled(it) },
                    )
                },
                title = stringResource(R.string.adb_over_vpn),
                onClick = {
                    viewModel.setAdbForwardingEnabled(!settingsState.settings.isAdbForwardingEnabled)
                },
                description = { DescriptionText(stringResource(R.string.adb_over_vpn_description)) },
            )
        }
    }
}

private fun pairAndPersist(ctx: android.content.Context, port: String, code: String): String {
    // Step 1: Pair
    val pairOut = ProotExecutor.exec(ctx, "adb pair 127.0.0.1:$port $code", timeoutMs = 30_000)
    if (!pairOut.contains("Successfully paired", ignoreCase = true)) {
        return "Pair failed: $pairOut"
    }

    // Step 2: Connect to adbd
    val connectOut = ProotExecutor.exec(ctx, "adb connect 127.0.0.1:5555", timeoutMs = 15_000)
    if (!connectOut.contains("connected", ignoreCase = true)) {
        return "Paired but connect failed: $connectOut"
    }

    // Step 3: Switch adbd to persistent TCP mode on port 5555
    val tcpipOut = ProotExecutor.exec(ctx, "adb tcpip 5555", timeoutMs = 15_000)

    return if (tcpipOut.contains("restarting in TCP mode", ignoreCase = true)) {
        "Success! ADB persisted on port 5555 (survives Wi-Fi disconnect)."
    } else {
        "Paired + connected, but tcpip failed: $tcpipOut"
    }
}
