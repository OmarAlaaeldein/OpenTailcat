package com.tailcat.vpn.ui.screens.home

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailcat.vpn.core.NetworkType
import com.tailcat.vpn.core.model.GatewayProfile
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.ui.SecureWindowWhile
import com.tailcat.vpn.ui.screens.home.components.PowerToggleRing
import com.tailcat.vpn.ui.screens.home.components.TelemetryCard
import com.tailcat.vpn.ui.screens.home.components.TelemetryDisplay
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.TailcatShapes
import com.tailcat.vpn.ui.theme.TailcatText
import com.tailcat.vpn.ui.theme.SurfaceElevated
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.YellowWarning
import com.tailcat.vpn.ui.theme.panel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSpeedTest: () -> Unit = {},
    onNavigateToShare: () -> Unit = {}
) {
    val context = LocalContext.current
    val tunnelState by viewModel.tunnelState.collectAsStateWithLifecycle()
    val metrics by viewModel.networkMetrics.collectAsStateWithLifecycle()
    val egressInfo by viewModel.egressInfo.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val networkType by viewModel.activeNetworkType.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val isSharing by viewModel.isSharing.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<GatewayProfile?>(null) }

    SecureWindowWhile(showAddDialog)

    val isDeviceOffline = networkType == NetworkType.NONE

    // Android VpnService Permission Launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connect()
        } else {
            viewModel.showMessage("VPN permission was not granted. No connection was started.")
        }
    }

    val requestVpnConsent: () -> Unit = {
        try {
            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                viewModel.connect()
            }
        } catch (e: Exception) {
            viewModel.showMessage(e.message ?: "Android could not request VPN permission.")
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        // Notification denial does not prevent a foreground VPN, but Android may only show it
        // in the active-apps surface. Continue to the system VPN consent screen either way.
        requestVpnConsent()
    }

    val onToggleClicked: () -> Unit = {
        if (tunnelState != TunnelState.DISCONNECTED) {
            // CONNECTED / DEGRADED / CONNECTING / RECONNECTING all stop on tap.
            viewModel.toggleVpn()
        } else if (activeProfile == null && profiles.isEmpty()) {
            showAddDialog = true
        } else if (viewModel.canStartTunnel()) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestVpnConsent()
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    containerColor = SurfaceElevated,
                    contentColor = TextPrimary,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(data.visuals.message, fontSize = 13.sp)
                }
            }
        },
        topBar = {
            HomeTopBar(
                isDeviceOffline = isDeviceOffline,
                engineAvailability = viewModel.engineAvailability,
                lastError = lastError,
                onNavigateToSpeedTest = onNavigateToSpeedTest,
                onNavigateToSettings = onNavigateToSettings
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            // Profile, status, card, and share stay a fixed stack. The ring
            // takes the space that remains, down to a floor, so a longer
            // status or a shorter phone scrolls instead of resizing the controls.
            val shareBlock = 88.dp
            val aboveShare = maxHeight - shareBlock
            val ring = (aboveShare - 292.dp).coerceIn(180.dp, minOf(maxWidth, 340.dp))
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    ProfileSelector(
                        activeProfile = activeProfile,
                        profiles = profiles,
                        onSelectProfile = viewModel::selectProfile,
                        onDeleteProfile = { pendingDelete = it },
                        onAddProfile = { showAddDialog = true }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    PowerToggleRing(
                        state = tunnelState,
                        transportType = metrics.transportType,
                        onClick = onToggleClicked,
                        modifier = Modifier.size(ring)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val statusLabel = TelemetryDisplay.statusLabel(
                        state = tunnelState,
                        deviceOffline = isDeviceOffline,
                        engineAvailable = viewModel.engineAvailability.isAvailable
                    )

                    val statusColor = when {
                        isDeviceOffline && tunnelState == TunnelState.DISCONNECTED -> RedDegraded
                        !viewModel.engineAvailability.isAvailable && tunnelState == TunnelState.DISCONNECTED -> YellowWarning
                        tunnelState == TunnelState.CONNECTED -> EmeraldConnected
                        tunnelState == TunnelState.CONNECTING || tunnelState == TunnelState.RECONNECTING -> AccentCyan
                        tunnelState == TunnelState.DEGRADED -> YellowWarning
                        else -> TextMuted
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = statusLabel,
                            color = statusColor,
                            style = TailcatText.Status,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TelemetryCard(
                        metrics = metrics,
                        egressInfo = egressInfo,
                        mtu = activeProfile?.mtu ?: 1280,
                        onRefreshIp = viewModel::refreshIp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }

                HomeShareAction(
                    isSharing = isSharing,
                    onClick = onNavigateToShare,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                )
            }
        }
    }

    pendingDelete?.let { profile ->
        DeleteProfileDialog(
            profile = profile,
            onConfirm = {
                viewModel.deleteProfile(profile.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }

    // Add Profile Dialog with Live Token Validation & Offline Indication
    if (showAddDialog) {
        AddProfileDialog(
            isDeviceOffline = isDeviceOffline,
            onSave = viewModel::addProfileFromToken,
            onDismiss = { showAddDialog = false }
        )
    }
}

/** The other role, held in the open space under the connection card. */
@Composable
private fun HomeShareAction(
    isSharing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (isSharing) EmeraldConnected else TextSecondary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .panel(
                shape = TailcatShapes.Card,
                border = if (isSharing) EmeraldConnected.copy(alpha = 0.55f) else BorderSubtle
            )
            .clip(TailcatShapes.Card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Share this phone",
                style = MaterialTheme.typography.titleMedium,
                color = if (isSharing) EmeraldConnected else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
            Text(
                text = if (isSharing) {
                    "A sharing session is running."
                } else {
                    "Temporary exit node for another device."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
        }
    }
}
