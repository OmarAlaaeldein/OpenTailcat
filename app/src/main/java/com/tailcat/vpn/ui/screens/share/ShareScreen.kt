package com.tailcat.vpn.ui.screens.share

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbar.showSnackbar(it) }
    }
    // The code on screen is a credential: no screenshots or recents preview
    // while it is shown.
    SecureWhile(state is ExitNodeUiState.Sharing)

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
                            "Disconnect the VPN first. Sharing and the VPN cannot run together."
                        else -> null
                    },
                    onDuration = viewModel::selectDuration,
                    onAdmission = viewModel::selectAdmission,
                    onRelayEnabled = viewModel::selectRelayEnabled,
                    onRelayHostname = viewModel::setRelayHostname,
                    onRelayDerpPort = viewModel::setRelayDerpPort,
                    onRelayStunPort = viewModel::setRelayStunPort,
                    onStart = viewModel::start
                )
                ExitNodeUiState.Starting -> StartingContent()
                is ExitNodeUiState.Sharing -> SharingContent(
                    token = s.token,
                    stats = s.stats,
                    onApprove = viewModel::approve,
                    onDeny = viewModel::deny,
                    onRevoke = viewModel::revoke,
                    onStop = viewModel::stop
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
        Text("Let someone use this phone's connection", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "They scan a code and their traffic leaves through this phone. Sites see your IP " +
                "address, and everything they do counts against your data plan and battery.",
            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
        )
    }

    Card {
        Eyebrow("HOW LONG")
        Spacer(Modifier.height(8.dp))
        // Four timed choices fill a phone's width, so "no limit" gets its own row.
        val (timed, untimed) = ShareDuration.entries.partition { it.isTimed }
        for (options in listOf(timed, untimed)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    FilterChip(
                        selected = option == duration,
                        onClick = { onDuration(option) },
                        label = { Text(option.label) }
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (duration.isTimed) {
                "The code stops working when time runs out, and sharing ends by itself."
            } else {
                "Sharing lasts until you tap Stop sharing. It also ends if Android closes the app, " +
                    "and the code then stops working. Each new device still needs your approval."
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
            body = "A new device waits until you tap Approve. A leaked code alone is not enough.",
            recommended = true,
            onClick = { onAdmission(ExitNodeAdmission.APPROVAL) }
        )
        AdmissionOption(
            selected = admission == ExitNodeAdmission.OPEN,
            title = "Anyone with the code",
            body = if (duration.isTimed) {
                "No approval step. Anyone who sees the code can connect until it expires."
            } else {
                "Needs a time limit, so a leaked code cannot be used forever. Pick a duration above."
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
        "Keep the screen on or the phone charging for the best results. If you use Always-on VPN " +
            "with \u2018Block connections without VPN\u2019, turn that off while sharing: Android would " +
            "block this app's own connection.",
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
            "A guest reaches this phone through a relay. Automatic picks the nearest public one; " +
                "your own keeps that traffic on a server you control.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )
        AdmissionOption(
            selected = !relay.enabled,
            title = "Automatic",
            body = "Nearest public relay, chosen by this phone's latency. Works on any network.",
            recommended = true,
            onClick = { onEnabled(false) }
        )
        AdmissionOption(
            selected = relay.enabled,
            title = "My own relay",
            body = "It must be reachable from the guest and serve a valid TLS certificate for its name.",
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
                    ?: "The name is baked into the code, so guests need no setup.",
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
    onStop: () -> Unit
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

    CodeCard(token, openAdmission = stats.openAdmission)

    if (stats.pending.isNotEmpty()) {
        Card(border = YellowWarning.copy(alpha = 0.5f)) {
            Eyebrow("WAITING FOR YOU")
            if (stats.suspiciousRequests) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "More than one device is asking. You gave this code to one person, so the " +
                        "code may have been shared. Approve only a device you recognise.",
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
                if (stats.openAdmission) "No one has connected yet."
                else "No approved devices yet. A device appears above when it asks to connect.",
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
                "${stats.policyRejections} attempt(s) to reach this phone or its network were blocked.",
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
        "Stopping discards this code's keys, so it can never be used again.",
        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun CodeCard(token: String, openAdmission: Boolean) {
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
            OutlinedButton(onClick = { copySensitive(context, token) }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copy code")
            }
        }
        Spacer(Modifier.height(10.dp))
        if (openAdmission) {
            Notice("Anyone who sees this code can use your connection until time runs out.", RedDegraded)
        } else {
            Text(
                "Show this to the person you are sharing with. Their device will appear below for " +
                    "you to approve.",
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
            Text("asking to connect", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
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
                    device.flows == 0L -> "approved, not connected yet"
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
            "The code you shared no longer works.",
            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
        )
    }
    Button(
        onClick = onDone,
        colors = TailcatButtons.filled(),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) { Text("Done") }
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

@Composable
private fun SecureWhile(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = (context as? Activity)?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (enabled) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
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
