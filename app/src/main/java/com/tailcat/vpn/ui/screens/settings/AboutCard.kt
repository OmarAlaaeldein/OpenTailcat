package com.tailcat.vpn.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tailcat.vpn.BuildConfig
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary

@Composable
internal fun AboutCard() {
    var showLicenses by remember { mutableStateOf(false) }

    SettingsCard(icon = Icons.Default.Info, title = "About & legal") {
        Text(
            "OpenTailcat • v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Apache License 2.0. Gateway tokens are encrypted on this device.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { showLicenses = true }) {
            Text("Open-source licenses")
        }
    }

    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
}
