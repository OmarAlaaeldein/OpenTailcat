package com.tailcat.vpn.ui.screens.home

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailcat.vpn.core.NetworkType
import com.tailcat.vpn.core.model.GatewayProfile
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.ui.screens.home.components.PowerToggleRing
import com.tailcat.vpn.ui.screens.home.components.TelemetryCard
import com.tailcat.vpn.ui.screens.home.components.TelemetryDisplay
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BgDark
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.SurfaceElevated
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.YellowWarning
import kotlinx.coroutines.flow.collectLatest

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSpeedTest: () -> Unit = {}
) {
    val context = LocalContext.current
    val tunnelState by viewModel.tunnelState.collectAsState()
    val metrics by viewModel.networkMetrics.collectAsState()
    val egressInfo by viewModel.egressInfo.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val networkType by viewModel.activeNetworkType.collectAsState()
    val lastError by viewModel.lastError.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<GatewayProfile?>(null) }

    DisposableEffect(showAddDialog) {
        val window = (context as? Activity)?.window
        if (showAddDialog) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

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
            viewModel.showMessage(e.message ?: "Android could not request VPN permission")
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
        containerColor = BgDark,
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            ProfileSelector(
                activeProfile = activeProfile,
                profiles = profiles,
                onSelectProfile = viewModel::selectProfile,
                onDeleteProfile = { pendingDelete = it },
                onAddProfile = { showAddDialog = true }
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Center Power Toggle Button
            PowerToggleRing(
                state = tunnelState,
                transportType = metrics.transportType,
                onClick = onToggleClicked,
                modifier = Modifier.size(220.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Connection Subtitle Status
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

            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = statusColor,
                    letterSpacing = 1.5.sp,
                    fontSize = 13.sp
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Bottom Telemetry Card with Public Egress IP
            TelemetryCard(
                metrics = metrics,
                egressInfo = egressInfo,
                mtu = activeProfile?.mtu ?: 1280,
                onRefreshIp = viewModel::refreshIp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(28.dp))
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
