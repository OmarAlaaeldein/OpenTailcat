package com.tailcat.vpn.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tailcat.vpn.BuildConfig
import com.tailcat.vpn.core.update.LatestRelease
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.TailcatButtons
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
    val state by viewModel.updateState.collectAsStateWithLifecycle()
    val release = when (val current = state) {
        is UpdateState.Available -> current.release
        is UpdateState.Downloading -> current.release
        is UpdateState.Ready -> current.release
        else -> null
    }
    var showNotes by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(release) {
        if (release == null) showNotes = false
    }
    if (showNotes && release != null) {
        ReleaseNotesDialog(
            version = release.normalizedVersion(),
            notes = release.notes,
            onDismiss = { showNotes = false }
        )
    }

    SettingsCard(icon = Icons.Default.SystemUpdate, title = "Updates") {
        when (val s = state) {
            is UpdateState.Idle -> {
                Text(
                    "Current: v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
                Spacer(Modifier.height(8.dp))
                ActionRow {
                    Button(
                        onClick = { viewModel.checkForUpdate() },
                        colors = TailcatButtons.filled(),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) { ActionLabel("Check for updates") }
                    OutlinedButton(
                        onClick = { viewModel.openReleasePage(context) },
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) { ActionLabel("Release notes") }
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
                    "OpenTailcat is up to date (v${s.current}).",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                )
                Spacer(Modifier.height(8.dp))
                ActionRow {
                    OutlinedButton(onClick = { viewModel.resetUpdate() }, modifier = Modifier.weight(1f).height(40.dp)) {
                        ActionLabel("Check again")
                    }
                    OutlinedButton(
                        onClick = { viewModel.openReleasePage(context) },
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) { ActionLabel("Release notes") }
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
                ReleaseNotesAction(s.release) { showNotes = true }
                Spacer(Modifier.height(8.dp))
                ActionRow {
                    Button(
                        onClick = { viewModel.downloadUpdate(context) },
                        colors = TailcatButtons.filled(),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) { ActionLabel("Download") }
                    OutlinedButton(
                        onClick = { viewModel.openReleasePage(context) },
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) { ActionLabel("GitHub") }
                    OutlinedButton(onClick = { viewModel.resetUpdate() }, modifier = Modifier.weight(1f).height(40.dp)) {
                        ActionLabel("Dismiss")
                    }
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
                ReleaseNotesAction(s.release) { showNotes = true }
            }
            is UpdateState.Ready -> {
                Text(
                    "Update downloaded. Install ${s.release.normalizedVersion()}?",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Android will request permission to install from this source if needed.",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                )
                ReleaseNotesAction(s.release) { showNotes = true }
                Spacer(Modifier.height(8.dp))
                ActionRow {
                    Button(
                        onClick = { viewModel.openInstall(context) },
                        colors = TailcatButtons.filled(),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) { ActionLabel("Install") }
                    OutlinedButton(onClick = { viewModel.resetUpdate() }, modifier = Modifier.weight(1f).height(40.dp)) {
                        ActionLabel("Cancel")
                    }
                }
            }
            is UpdateState.Error -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium.copy(color = YellowWarning)
                )
                Spacer(Modifier.height(8.dp))
                ActionRow {
                    OutlinedButton(onClick = { viewModel.checkForUpdate() }, modifier = Modifier.weight(1f).height(40.dp)) {
                        ActionLabel("Retry")
                    }
                    OutlinedButton(
                        onClick = { viewModel.openReleasePage(context) },
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) { ActionLabel("Release notes") }
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotesAction(release: LatestRelease, onRead: () -> Unit) {
    if (release.notes.isBlank()) return
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onRead, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        ActionLabel("Release notes")
    }
}

@Composable
private fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}

@Composable
private fun ActionLabel(text: String) {
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
}
