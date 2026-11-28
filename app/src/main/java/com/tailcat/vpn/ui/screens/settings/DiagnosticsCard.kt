package com.tailcat.vpn.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.TailcatButtons
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary

@Composable
internal fun DiagnosticsCard(debugMode: Boolean, onDebugModeChange: (Boolean) -> Unit) {
    SettingsCard(icon = Icons.Default.BugReport, title = "Diagnostics") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = debugMode,
                    role = Role.Checkbox,
                    onValueChange = onDebugModeChange
                )
        ) {
            Text(
                "Detailed failure reports",
                style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = debugMode,
                onCheckedChange = null,
                colors = TailcatButtons.checkbox(uncheckedColor = BorderSubtle)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "When enabled, failure banners include the cause and a telemetry snapshot (state, " +
                "transport, health age, and counters). From the next connection, the engine also " +
                "writes full logs, including public and local IP addresses, to the system log. " +
                "Disabled by default; routing and lockdown are unaffected.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )
    }
}
