package com.tailcat.vpn.ui.screens.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.service.LockdownProbe
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.TextSecondary

@Composable
internal fun AlwaysOnCard() {
    val context = LocalContext.current
    SettingsCard(icon = Icons.Default.Security, title = "Always-on & kill switch") {
        val alwaysOn by TailcatApplication.instance.tunnelController
            .alwaysOnStatus.collectAsStateWithLifecycle()
        Text(
            LockdownProbe.statusText(Build.VERSION.SDK_INT, alwaysOn),
            style = MaterialTheme.typography.bodySmall.copy(
                color = if (LockdownProbe.isProtected(alwaysOn)) {
                    EmeraldConnected
                } else {
                    TextSecondary
                }
            )
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Recommended: enable Always-on VPN and ‘Block connections without VPN’ (Android 8 or later). " +
                "Without them, apps use the device network whenever the VPN is off or reconnecting; " +
                "Connect still works. With lockdown enabled, Android blocks apps excluded from the " +
                "tunnel from using the network at all.",
            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            }
        ) {
            Text("Open Android VPN settings")
        }
    }
}
