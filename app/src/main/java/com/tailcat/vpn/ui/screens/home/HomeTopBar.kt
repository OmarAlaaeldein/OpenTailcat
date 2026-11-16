package com.tailcat.vpn.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tailcat.vpn.service.EngineAvailability
import com.tailcat.vpn.ui.theme.panel
import com.tailcat.vpn.ui.theme.TailcatShapes
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.YellowWarning

/** Title row with navigation buttons, then the offline, engine, and error banners. */
@Composable
fun HomeTopBar(
    isDeviceOffline: Boolean,
    engineAvailability: EngineAvailability,
    lastError: String?,
    onNavigateToSpeedTest: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Edge-to-edge: keep the title clear of the status bar / cutout.
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Column {
                Text(
                    text = "OpenTailcat",
                    style = MaterialTheme.typography.headlineLarge
                )
                Text(
                    text = "Private gateway for Tailcat",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onNavigateToSpeedTest,
                    modifier = Modifier
                        .panel(TailcatShapes.Control)
                        .clip(TailcatShapes.Control)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Speed Test",
                        tint = AccentCyan
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .panel(TailcatShapes.Control)
                        .clip(TailcatShapes.Control)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = TextSecondary
                    )
                }
            }
        }

        // Offline Alarm Banner
        AnimatedVisibility(
            visible = isDeviceOffline,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .panel(TailcatShapes.Tile, fill = RedDegraded.copy(alpha = 0.15f), border = RedDegraded.copy(alpha = 0.5f))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SignalWifiOff,
                        contentDescription = "Offline",
                        tint = RedDegraded,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "No Internet Connection • Connect to Wi-Fi or mobile data",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = RedDegraded,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !engineAvailability.isAvailable,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            StatusBanner(
                message = engineAvailability.message,
                color = YellowWarning,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        AnimatedVisibility(
            visible = lastError != null && engineAvailability.isAvailable,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            StatusBanner(
                message = lastError.orEmpty(),
                color = RedDegraded,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun StatusBanner(
    message: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .panel(TailcatShapes.Tile, fill = color.copy(alpha = 0.14f), border = color.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.WarningAmber,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall.copy(
                color = color,
                fontWeight = FontWeight.Medium
            )
        )
    }
}
