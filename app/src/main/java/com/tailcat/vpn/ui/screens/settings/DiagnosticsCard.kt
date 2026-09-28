package com.tailcat.vpn.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary

@Composable
internal fun DiagnosticsCard(debugMode: Boolean, onDebugModeChange: (Boolean) -> Unit) {
    SettingsCard(icon = Icons.Default.BugReport, title = "Diagnostics") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Debug failure reports",
                style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = debugMode,
                onCheckedChange = onDebugModeChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = AccentCyan,
                    uncheckedColor = BorderSubtle
                )
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "When on, failure banners name the cause and append a telemetry snapshot (state, transport, health age, counters), and from the next connect the engine writes its full logs, including your public and local IP addresses, to the system log. Off by default; never changes routing or lockdown.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )
    }
}
