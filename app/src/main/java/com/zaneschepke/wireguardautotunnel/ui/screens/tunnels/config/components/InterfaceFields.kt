package com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.config.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import com.wireguard.crypto.KeyPair
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.LocalIsAndroidTV
import com.zaneschepke.wireguardautotunnel.ui.common.functions.rememberClipboardHelper
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import com.zaneschepke.wireguardautotunnel.ui.state.InterfaceProxy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceFields(
    isGlobalConfig: Boolean,
    interfaceState: InterfaceProxy,
    showScripts: Boolean,
    onInterfaceChange: (InterfaceProxy) -> Unit,
    showKey: Boolean,
) {
    val locale = Locale.current.platformLocale
    val keyboardController = LocalSoftwareKeyboardController.current
    val isTv = LocalIsAndroidTV.current
    val clipboardManager = rememberClipboardHelper()
    val keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() })
    val keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
    var showPrivateKey by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(showKey) { showPrivateKey = showKey }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!isGlobalConfig)
            ConfigurationTextBox(
                value = interfaceState.privateKey,
                hint =
                    stringResource(R.string.hint_template, stringResource(R.string.base64_key))
                        .lowercase(locale),
                onValueChange = { onInterfaceChange(interfaceState.copy(privateKey = it)) },
                label = stringResource(R.string.private_key),
                modifier = Modifier.fillMaxWidth(),
                visualTransformation =
                    if (showPrivateKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailing =
                    if (!isTv) {
                        { modifier ->
                            CompositionLocalProvider(
                                LocalMinimumInteractiveComponentSize provides 4.dp
                            ) {
                                Row(modifier = Modifier.padding(end = 4.dp)) {
                                    IconButton(
                                        onClick = { showPrivateKey = !showPrivateKey },
                                        modifier,
                                    ) {
                                        Icon(
                                            Icons.Outlined.RemoveRedEye,
                                            stringResource(R.string.show_password),
                                        )
                                    }
                                    IconButton(
                                        enabled = true,
                                        onClick = {
                                            val keypair = KeyPair()
                                            onInterfaceChange(
                                                interfaceState.copy(
                                                    privateKey = keypair.privateKey.toBase64(),
                                                    publicKey = keypair.publicKey.toBase64(),
                                                )
                                            )
                                        },
                                    ) {
                                        Icon(
                                            Icons.Rounded.Refresh,
                                            stringResource(R.string.rotate_keys),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    } else null,
                enabled = true,
                singleLine = true,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
            )
        if (!isGlobalConfig)
            ConfigurationTextBox(
                value = interfaceState.publicKey,
                hint =
                    stringResource(R.string.hint_template, stringResource(R.string.base64_key))
                        .lowercase(locale),
                onValueChange = { onInterfaceChange(interfaceState.copy(publicKey = it)) },
                label = stringResource(R.string.public_key),
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailing =
                    if (!isTv) {
                        { _ ->
                            IconButton(
                                onClick = { clipboardManager.copy(interfaceState.publicKey) }
                            ) {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    stringResource(R.string.copy_public_key),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    } else null,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
            )
        if (!isGlobalConfig)
            ConfigurationTextBox(
                value = interfaceState.addresses,
                onValueChange = { onInterfaceChange(interfaceState.copy(addresses = it)) },
                label = stringResource(R.string.addresses),
                hint =
                    stringResource(
                            R.string.hint_template,
                            stringResource(R.string.comma_separated).lowercase(),
                        )
                        .lowercase(locale),
                modifier = Modifier.fillMaxWidth(),
            )
        if (!isGlobalConfig)
            ConfigurationTextBox(
                value = interfaceState.listenPort,
                onValueChange = { onInterfaceChange(interfaceState.copy(listenPort = it)) },
                label = stringResource(R.string.listen_port),
                hint = stringResource(R.string.random),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConfigurationTextBox(
                value = interfaceState.dnsServers,
                onValueChange = { onInterfaceChange(interfaceState.copy(dnsServers = it)) },
                label = stringResource(R.string.dns_servers),
                hint =
                    stringResource(R.string.hint_template, stringResource(R.string.comma_separated))
                        .lowercase(locale),
                modifier = Modifier.weight(3f),
            )
            if (!isGlobalConfig)
                ConfigurationTextBox(
                    value = interfaceState.mtu,
                    onValueChange = { onInterfaceChange(interfaceState.copy(mtu = it)) },
                    label = stringResource(R.string.mtu),
                    hint = stringResource(R.string.auto).lowercase(locale),
                    modifier = Modifier.weight(2f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
        }
        if (showScripts) {
            ConfigurationTextBox(
                value = interfaceState.preUp,
                onValueChange = { onInterfaceChange(interfaceState.copy(preUp = it)) },
                label = stringResource(R.string.pre_up),
                hint =
                    stringResource(
                        R.string.hint_template,
                        stringResource(R.string.comma_separated).lowercase(locale),
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            ConfigurationTextBox(
                value = interfaceState.postUp,
                onValueChange = { onInterfaceChange(interfaceState.copy(postUp = it)) },
                label = stringResource(R.string.post_up),
                hint =
                    stringResource(
                        R.string.hint_template,
                        stringResource(R.string.comma_separated).lowercase(locale),
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            ConfigurationTextBox(
                value = interfaceState.preDown,
                onValueChange = { onInterfaceChange(interfaceState.copy(preDown = it)) },
                label = stringResource(R.string.pre_down),
                hint =
                    stringResource(
                        R.string.hint_template,
                        stringResource(R.string.comma_separated).lowercase(locale),
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            ConfigurationTextBox(
                value = interfaceState.postDown,
                onValueChange = { onInterfaceChange(interfaceState.copy(postDown = it)) },
                label = stringResource(R.string.post_down),
                hint =
                    stringResource(
                        R.string.hint_template,
                        stringResource(R.string.comma_separated).lowercase(locale),
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
