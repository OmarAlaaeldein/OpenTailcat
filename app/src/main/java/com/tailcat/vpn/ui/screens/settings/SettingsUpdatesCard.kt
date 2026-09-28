package com.tailcat.vpn.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tailcat.vpn.BuildConfig
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.YellowWarning

@Composable
internal fun SettingsUpdatesCard(
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val state by viewModel.updateState.collectAsState()

    SettingsCard(icon = Icons.Default.SystemUpdate, title = "Updates") {
        when (val s = state) {
            is UpdateState.Idle -> {
                Text(
                    "Current: v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.checkForUpdate() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                    ) { Text("Check for update") }
                    OutlinedButton(onClick = { viewModel.openReleasePage(context) }) {
                        Text("Release page")
                    }
                }
            }
            is UpdateState.Checking -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AccentCyan
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Checking GitHub releases…", style = MaterialTheme.typography.bodySmall)
                }
            }
            is UpdateState.UpToDate -> {
                Text(
                    "You're up to date (v${s.current}).",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.resetUpdate() }) { Text("Check again") }
                    OutlinedButton(onClick = { viewModel.openReleasePage(context) }) {
                        Text("Release page")
                    }
                }
            }
            is UpdateState.Available -> {
                Text(
                    "Update available: ${s.release.normalizedVersion()} (installed v${BuildConfig.VERSION_NAME})",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = AccentCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                if (s.release.notes.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        s.release.notes.take(400),
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.downloadUpdate(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                    ) { Text("Download") }
                    OutlinedButton(onClick = { viewModel.openReleasePage(context) }) {
                        Text("Open on GitHub")
                    }
                    OutlinedButton(onClick = { viewModel.resetUpdate() }) { Text("Dismiss") }
                }
            }
            is UpdateState.Downloading -> {
                LinearProgressIndicator(
                    progress = { s.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentCyan,
                    trackColor = BorderSubtle
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Downloading ${s.release.normalizedVersion()}… ${(s.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
            }
            is UpdateState.Ready -> {
                Text(
                    "APK ready. Install ${s.release.normalizedVersion()}?",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Android will ask you to allow installs from this source if needed.",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.openInstall(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                    ) { Text("Install") }
                    OutlinedButton(onClick = { viewModel.resetUpdate() }) { Text("Cancel") }
                }
            }
            is UpdateState.Error -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium.copy(color = YellowWarning)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.checkForUpdate() }) { Text("Retry") }
                    OutlinedButton(onClick = { viewModel.openReleasePage(context) }) {
                        Text("Release page")
                    }
                }
            }
        }
    }
}
