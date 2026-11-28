package com.tailcat.vpn.ui.screens.share

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailcat.vpn.core.exitnode.ExitNodeStats
import com.tailcat.vpn.core.exitnode.GuestDevice
import com.tailcat.vpn.core.exitnode.PendingDevice
import com.tailcat.vpn.core.exitnode.deviceShortId
import com.tailcat.vpn.core.metrics.TrafficFormat
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.service.ExitNodeAdmission
import com.tailcat.vpn.service.ExitNodeRelay
import com.tailcat.vpn.service.ExitNodeUiState
import com.tailcat.vpn.ui.SecureWindowWhile
import com.tailcat.vpn.ui.screens.home.QrMatrixCanvas
import com.tailcat.vpn.ui.screens.home.encodeTokenQr
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.TailcatButtons
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.TailcatShapes
import com.tailcat.vpn.ui.theme.TailcatText
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.YellowWarning
import com.tailcat.vpn.ui.theme.panel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Share this phone's connection as an exit gateway. A separate screen on
 * purpose: the home screen is the client (a ring that connects *to* a
 * gateway), and this is the opposite role, so it gets its own state, copy and
 * safety messaging instead of a mode switch on the connect ring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    onNavigateBack: () -> Unit,
    viewModel: ShareViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val duration by viewModel.duration.collectAsStateWithLifecycle()
    val admission by viewModel.admission.collectAsStateWithLifecycle()
    val relay by viewModel.relay.collectAsStateWithLifecycle()
    val tunnelState by viewModel.tunnelState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.start()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbar.showSnackbar(it) }
    }
    // The code on screen is a credential: no screenshots or recents preview
    // while it is shown.
    SecureWindowWhile(state is ExitNodeUiState.Sharing)

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Share this phone", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when (val s = state) {
                ExitNodeUiState.Idle -> SetupContent(
                    duration = duration,
                    admission = admission,
                    relay = relay,
                    blockedReason = when {
                        !viewModel.availability.isAvailable -> viewModel.availability.message
                        tunnelState != TunnelState.DISCONNECTED ->
                            "Disconnect the VPN first. Sharing and the VPN cannot run simultaneously."
                        else -> null
                    },
                    onDuration = viewModel::selectDuration,
                    onAdmission = viewModel::selectAdmission,
                    onRelayEnabled = viewModel::selectRelayEnabled,
                    onRelayHostname = viewModel::setRelayHostname,
                    onRelayDerpPort = viewModel::setRelayDerpPort,
                    onRelayStunPort = viewModel::setRelayStunPort,
                    onStart = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.start()
                        }
                    }
                )
                ExitNodeUiState.Starting -> StartingContent()
                is ExitNodeUiState.Sharing -> SharingContent(
                    token = s.token,
                    stats = s.stats,
                    onApprove = viewModel::approve,
                    onDeny = viewModel::deny,
                    onRevoke = viewModel::revoke,
                    onStop = viewModel::stop,
                    onCopied = { scope.launch { snackbar.showSnackbar("Code copied to clipboard") } }
                )
                is ExitNodeUiState.Ended -> EndedContent(s.message, viewModel::acknowledgeEnded)
            }
        }
    }
}

// ---- setup ------------------------------------------------------------------

@Composable
private fun SetupContent(
    duration: ShareDuration,
    admission: ExitNodeAdmission,
    relay: ShareRelay,
    blockedReason: String?,
    onDuration: (ShareDuration) -> Unit,
    onAdmission: (ExitNodeAdmission) -> Unit,
    onRelayEnabled: (Boolean) -> Unit,
    onRelayHostname: (String) -> Unit,
    onRelayDerpPort: (String) -> Unit,
    onRelayStunPort: (String) -> Unit,
    onStart: () -> Unit
) {
    Card {
        Text("Share this phone's internet connection", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Guests scan a code, and their traffic exits through this phone. Destinations see " +
                "this phone's IP address, and guest traffic uses this phone's data plan and battery.",
            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
        )
    }

    Card {
        Eyebrow("HOW LONG")
        Spacer(Modifier.height(8.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ShareDuration.entries.forEach { option ->
                FilterChip(
                    selected = option == duration,
                    onClick = { onDuration(option) },
                    label = { Text(option.label) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (duration.isTimed) {
                "The code expires when the selected time elapses, and sharing stops automatically."
            } else {
                "Sharing continues until Stop sharing is selected. It also ends if Android closes " +
                    "the app, after which the code is invalid. Each new device still requires approval."
            },
            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
        )
    }

    Card {
        Eyebrow("WHO CAN CONNECT")
        Spacer(Modifier.height(4.dp))
        AdmissionOption(
            selected = admission == ExitNodeAdmission.APPROVAL,
            title = "Approve each device",
            body = "New devices remain pending until approved. A leaked code alone is not sufficient.",
            recommended = true,
            onClick = { onAdmission(ExitNodeAdmission.APPROVAL) }
        )
        AdmissionOption(
            selected = admission == ExitNodeAdmission.OPEN,
            title = "No approval required",
            body = if (duration.isTimed) {
                "No approval step. Anyone with the code can connect until it expires."
            } else {
                "Requires a time limit so a leaked code cannot be used indefinitely."
            },
            recommended = false,
            enabled = duration.isTimed,
            onClick = { onAdmission(ExitNodeAdmission.OPEN) }
        )
    }

    RelayCard(
        relay = relay,
        onEnabled = onRelayEnabled,
        onHostname = onRelayHostname,
        onDerpPort = onRelayDerpPort,
        onStunPort = onRelayStunPort
    )

    Text(
        "Keep the screen on or the device charging. If Always-on VPN with " +
            "\u2018Block connections without VPN\u2019 is enabled, disable it while sharing; Android would " +
            "otherwise block this app's own connection.",
        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
        modifier = Modifier.padding(horizontal = 4.dp)
    )

    if (blockedReason != null) Notice(blockedReason, YellowWarning)

    Button(
        onClick = onStart,
        enabled = blockedReason == null && relay.problem == null,
        colors = TailcatButtons.filled(),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Text("Start sharing")
    }
}

/**
 * Relay choice. The default is invisible to the host on purpose: one tap starts
 * sharing. Naming a relay is for operators who run one, and it is embedded in
 * the code, so the guest needs no matching setting.
 */
@Composable
private fun RelayCard(
    relay: ShareRelay,
    onEnabled: (Boolean) -> Unit,
    onHostname: (String) -> Unit,
    onDerpPort: (String) -> Unit,
    onStunPort: (String) -> Unit
) {
    val hostProblem = ExitNodeRelay.validateHostname(relay.hostname)
    val derpOk = relay.derpPort.toIntOrNull()?.let { it in 1..65535 } == true
    val stunOk = relay.stunPort.toIntOrNull()?.let { it in 1..65535 } == true

    Card {
        Eyebrow("RELAY")
        Spacer(Modifier.height(4.dp))
        Text(
            "Guests reach this phone through a relay. Automatic selects the nearest public relay; " +
                "a custom relay keeps that traffic on an operator-controlled server.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )
        AdmissionOption(
            selected = !relay.enabled,
            title = "Automatic",
            body = "Nearest public relay, selected by measured latency. Works on any network.",
            recommended = true,
            onClick = { onEnabled(false) }
        )
        AdmissionOption(
            selected = relay.enabled,
            title = "Custom relay",
            body = "The relay must be reachable by guests and serve a valid TLS certificate for its host name.",
            recommended = false,
            onClick = { onEnabled(true) }
        )
        if (relay.enabled) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = relay.hostname,
                onValueChange = onHostname,
                label = { Text("Relay host name") },
                placeholder = { Text("derp.example.com") },
                singleLine = true,
                isError = hostProblem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = relay.derpPort,
                    onValueChange = onDerpPort,
                    label = { Text("DERP port") },
                    singleLine = true,
                    isError = !derpOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = relay.stunPort,
                    onValueChange = onStunPort,
                    label = { Text("STUN port") },
                    singleLine = true,
                    isError = !stunOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                relay.problem
                    ?: "The host name is embedded in the code, so guests need no additional configuration.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = if (relay.problem != null) RedDegraded else TextMuted
                )
            )
        }
    }
}

@Composable
private fun AdmissionOption(
    selected: Boolean,
    title: String,
    body: String,
    recommended: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            modifier = Modifier.padding(top = 2.dp, end = 12.dp)
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) TextPrimary else TextMuted)
                )
                if (recommended) {
                    Spacer(Modifier.width(8.dp))
                    Text("RECOMMENDED", style = TailcatText.Eyebrow.copy(color = EmeraldConnected))
                }
            }
            Text(body, style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
        }
    }
}

@Composable
private fun StartingContent() {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = AccentCyan, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Starting. Connecting to a relay…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---- sharing ----------------------------------------------------------------

@Composable
private fun SharingContent(
    token: String,
    stats: ExitNodeStats,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRevoke: (String) -> Unit,
    onStop: () -> Unit,
    onCopied: () -> Unit
) {
    val now by rememberNowSec()
    val expires = stats.expiresUnixSec

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Box(Modifier.size(8.dp).background(EmeraldConnected, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text("SHARING", style = TailcatText.Status.copy(color = EmeraldConnected))
        Spacer(Modifier.weight(1f))
        Text(
            if (expires == null) "no time limit" else "${formatRemaining((expires - now).coerceAtLeast(0))} left",
            style = TailcatText.Metric.copy(color = TextSecondary)
        )
    }

    CodeCard(token, openAdmission = stats.openAdmission, onCopied = onCopied)

    if (stats.pending.isNotEmpty()) {
        Card(border = YellowWarning.copy(alpha = 0.5f)) {
            Eyebrow("PENDING APPROVAL")
            if (stats.suspiciousRequests) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "More than one device is requesting access. The code may have been shared. " +
                        "Approve only recognized devices.",
                    style = MaterialTheme.typography.bodySmall.copy(color = YellowWarning)
                )
            }
            stats.pending.forEach { PendingRow(it, onApprove, onDeny) }
        }
    }

    Card {
        Eyebrow("DEVICES")
        if (stats.guests.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (stats.openAdmission) "No devices connected."
                else "No approved devices. New devices appear above for approval.",
                style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
            )
        }
        stats.guests.forEach { GuestRow(it, onRevoke) }
    }

    Card {
        Eyebrow("TRAFFIC")
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("From guests", TrafficFormat.formatBytes(stats.bytesFromGuests))
            Stat("To guests", TrafficFormat.formatBytes(stats.bytesToGuests))
            Stat("Open flows", stats.activeFlows.toString())
        }
        if (stats.policyRejections > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${stats.policyRejections} blocked attempts to reach this phone or its network.",
                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
            )
        }
    }

    OutlinedButton(
        onClick = onStop,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = RedDegraded),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Text("Stop sharing")
    }
    Text(
        "Stopping discards the session keys; this code cannot be reused.",
        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun CodeCard(token: String, openAdmission: Boolean, onCopied: () -> Unit) {
    val context = LocalContext.current
    val matrix = remember(token) { runCatching { encodeTokenQr(token) }.getOrNull() }
    Card {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            if (matrix != null) {
                QrMatrixCanvas(matrix, Modifier.size(248.dp))
            } else {
                Text("The code is too large for a QR image. Use Copy.")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                copySensitive(context, token)
                onCopied()
            }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copy code")
            }
        }
        Spacer(Modifier.height(10.dp))
        if (openAdmission) {
            Notice("Anyone with this code can use this connection until it expires.", RedDegraded)
        } else {
            Text(
                "The guest scans this code. Their device then appears below for approval.",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        }
    }
}

@Composable
private fun PendingRow(device: PendingDevice, onApprove: (String) -> Unit, onDeny: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(deviceShortId(device.key), style = TailcatText.Metric)
            Text("connection request", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
        }
        TextButton(onClick = { onDeny(device.key) }) { Text("Deny", color = TextSecondary) }
        Button(
            onClick = { onApprove(device.key) },
            colors = TailcatButtons.filled()
        ) { Text("Approve") }
    }
}

@Composable
private fun GuestRow(device: GuestDevice, onRevoke: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(deviceShortId(device.key), style = TailcatText.Metric)
            Text(
                when {
                    device.flows == 0L -> "approved, no active connection"
                    device.activeFlows > 0 -> "${device.activeFlows} open · ${TrafficFormat.formatBytes(device.totalBytes)}"
                    else -> "idle · ${TrafficFormat.formatBytes(device.totalBytes)}"
                },
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        }
        TextButton(onClick = { onRevoke(device.key) }) { Text("Remove", color = RedDegraded) }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = TailcatText.Metric)
        Text(label, style = MaterialTheme.typography.bodySmall.copy(color = TextMuted))
    }
}

@Composable
private fun EndedContent(message: String, onDone: () -> Unit) {
    Card {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "This code is no longer valid.",
            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
        )
    }
    Button(
        onClick = onDone,
        colors = TailcatButtons.filled(),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) { Text("Close") }
}

// ---- small pieces -------------------------------------------------------------

@Composable
private fun Card(
    border: Color = BorderSubtle,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .panel(border = border)
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text, style = TailcatText.Eyebrow.copy(color = TextMuted))
}

@Composable
private fun Notice(message: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .panel(TailcatShapes.Tile, fill = color.copy(alpha = 0.14f), border = color.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Default.WarningAmber, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodySmall.copy(color = color))
    }
}

/** A ticking wall clock in seconds, so the countdown moves between engine polls. */
@Composable
private fun rememberNowSec(): State<Long> = produceState(System.currentTimeMillis() / 1000) {
    while (true) {
        value = System.currentTimeMillis() / 1000
        delay(1_000)
    }
}

internal fun formatRemaining(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** Copies the code and, on Android 13+, asks the system not to show it in the clipboard preview. */
private fun copySensitive(context: Context, token: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("OpenTailcat sharing code", token)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
}
