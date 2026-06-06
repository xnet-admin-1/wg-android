package com.zaneschepke.wireguardautotunnel.ui.screens.support

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zaneschepke.wireguardautotunnel.ui.common.label.VersionFooter

@Composable
fun SupportScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        VersionFooter()
    }
}
