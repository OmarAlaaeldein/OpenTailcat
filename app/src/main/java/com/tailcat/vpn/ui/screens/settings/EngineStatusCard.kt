package com.tailcat.vpn.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tailcat.vpn.service.EngineAvailability
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.YellowWarning

@Composable
internal fun EngineStatusCard(engineAvailability: EngineAvailability) {
    SettingsCard(
        icon = Icons.Default.SettingsEthernet,
        title = "Native tunnel engine"
    ) {
        val color = if (engineAvailability.isAvailable) EmeraldConnected else RedDegraded
        Text(
            text = engineAvailability.message,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = color,
                fontWeight = FontWeight.SemiBold
            )
        )
        if (engineAvailability.testRouting) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Test-routing build: data-plane capabilities are implemented and enabled for Connect. Phase 8 physical leak acceptance is still pending — not a production/leak-free claim.",
                style = MaterialTheme.typography.bodySmall.copy(color = YellowWarning)
            )
        }
        if (!engineAvailability.isAvailable) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Connections stay disabled until a compatible libtailcat AAR advertises a working WireGuard and Magicsock data plane.",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        }
    }
}
