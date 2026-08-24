package com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.components

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.enums.ConfigType
import com.zaneschepke.wireguardautotunnel.ui.common.functions.rememberFileExportLauncherForResult
import com.zaneschepke.wireguardautotunnel.ui.common.sheet.CustomBottomSheet
import com.zaneschepke.wireguardautotunnel.ui.common.sheet.SheetOption
import com.zaneschepke.wireguardautotunnel.util.FileUtils
import com.zaneschepke.wireguardautotunnel.util.extensions.hasSAFSupport
import java.time.Instant

@Composable
fun ExportTunnelsBottomSheet(
    onExport: (configType: ConfigType, uri: Uri?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    var shouldExport by remember { mutableStateOf(false) }

    val selectedTunnelsExportLauncher =
        rememberFileExportLauncherForResult(
            mimeType = FileUtils.ZIP_FILE_MIME_TYPE,
            onResult = { file ->
                if (file != null) {
                    onExport(ConfigType.WG, file)
                } else onDismiss()
            },
        )

    fun handleFileExport() {
        if (context.hasSAFSupport(FileUtils.ZIP_FILE_MIME_TYPE)) {
            val fileName = "wg_export_${Instant.now().epochSecond}.zip"
            selectedTunnelsExportLauncher.launch(fileName)
        } else {
            onExport(ConfigType.WG, null)
        }
    }

    LaunchedEffect(shouldExport) {
        if (shouldExport) {
            handleFileExport()
            shouldExport = false
        }
    }

    CustomBottomSheet(
        listOf(
            SheetOption(
                Icons.Outlined.FolderZip,
                stringResource(R.string.export_tunnels_wireguard),
                onClick = {
                    shouldExport = true
                },
            ),
        )
    ) {
        onDismiss()
    }
}
