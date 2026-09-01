package com.tailcat.vpn.ui.screens.home

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.tailcat.vpn.core.NetworkType
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.core.token.TokenParser
import com.tailcat.vpn.core.token.TokenValidationState
import com.tailcat.vpn.ui.screens.home.components.PowerToggleRing
import com.tailcat.vpn.ui.screens.home.components.TelemetryCard
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BgDark
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.SurfaceDark
import com.tailcat.vpn.ui.theme.SurfaceElevated
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary
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
    var showProfileDropdown by remember { mutableStateOf(false) }

    val isDeviceOffline = networkType == NetworkType.NONE

    // Android VpnService Permission Launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.toggleVpn()
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
                viewModel.toggleVpn()
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
        if (tunnelState != TunnelState.DISCONNECTED && tunnelState != TunnelState.DEGRADED) {
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Column {
                        Text(
                            text = "OpenTailcat",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Text(
                            text = "Control-Plane-Free Mesh VPN",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onNavigateToSpeedTest,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceDark)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
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
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceDark)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
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
                            .clip(RoundedCornerShape(12.dp))
                            .background(RedDegraded.copy(alpha = 0.15f))
                            .border(1.dp, RedDegraded.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
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
                    visible = !viewModel.engineAvailability.isAvailable,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    StatusBanner(
                        message = viewModel.engineAvailability.message,
                        color = YellowWarning,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                AnimatedVisibility(
                    visible = lastError != null && viewModel.engineAvailability.isAvailable,
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
    ) { innerPadding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Profile Selector Dropdown Chip
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
                        .clickable { showProfileDropdown = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = "Profile",
                        tint = if (activeProfile != null) AccentCyan else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = activeProfile?.name ?: "No Gateway Paired",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (activeProfile != null) TextPrimary else TextMuted,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Select",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = showProfileDropdown,
                    onDismissRequest = { showProfileDropdown = false },
                    modifier = Modifier.background(SurfaceElevated)
                ) {
                    if (profiles.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No saved profiles", color = TextSecondary) },
                            onClick = {
                                showProfileDropdown = false
                                showAddDialog = true
                            }
                        )
                    } else {
                        profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = profile.name,
                                            color = if (profile.id == activeProfile?.id) AccentCyan else TextPrimary,
                                            fontWeight = if (profile.id == activeProfile?.id) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = "Region ${profile.derpRegionId ?: "Default"}",
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.selectProfile(profile)
                                    showProfileDropdown = false
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            viewModel.deleteProfile(profile.id)
                                            showProfileDropdown = false
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "Delete ${profile.name}",
                                            tint = RedDegraded
                                        )
                                    }
                                }
                            )
                        }
                        HorizontalDivider(color = BorderSubtle)
                        DropdownMenuItem(
                            text = { Text("Pair another gateway", color = AccentCyan) },
                            leadingIcon = {
                                Icon(Icons.Default.Add, contentDescription = null, tint = AccentCyan)
                            },
                            onClick = {
                                showProfileDropdown = false
                                showAddDialog = true
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Center Power Toggle Button
            PowerToggleRing(
                state = tunnelState,
                transportType = metrics.transportType,
                onClick = onToggleClicked,
                modifier = Modifier.size(240.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Connection Subtitle Status
            val statusLabel = when {
                isDeviceOffline && tunnelState == TunnelState.DISCONNECTED -> "OFFLINE • NO INTERNET"
                !viewModel.engineAvailability.isAvailable && tunnelState == TunnelState.DISCONNECTED -> "ENGINE REQUIRED • VPN DISABLED"
                tunnelState == TunnelState.CONNECTED -> "SECURE & ENCRYPTED"
                tunnelState == TunnelState.CONNECTING -> "ESTABLISHING WIREGUARD TUNNEL..."
                tunnelState == TunnelState.RECONNECTING -> "ROAMING / RECONNECTING..."
                tunnelState == TunnelState.DEGRADED -> "DEGRADED • RELAYING"
                else -> "TAP TO CONNECT"
            }

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

            Spacer(modifier = Modifier.height(32.dp))

            // Bottom Telemetry Card with Public Egress IP
            TelemetryCard(
                metrics = metrics,
                egressInfo = egressInfo,
                mtu = activeProfile?.mtu ?: 1280,
                onRefreshIp = viewModel::refreshIp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(36.dp))
        }
    }

    // Add Profile Dialog with Live Token Validation & Offline Indication
    if (showAddDialog) {
        var tokenInput by remember { mutableStateOf("") }
        var nameInput by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        val validationState = remember(tokenInput) {
            TokenParser.validate(tokenInput)
        }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = SurfaceElevated,
            title = {
                Text("Pair Gateway Token", color = TextPrimary)
            },
            text = {
                Column {
                    Text(
                        text = "Paste a Tailcat connection token (tc...) to establish a direct P2P WireGuard tunnel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Offline Warning Badge inside Dialog
                    if (isDeviceOffline) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(YellowWarning.copy(alpha = 0.15f))
                                .border(1.dp, YellowWarning.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = "Offline Notice",
                                    tint = YellowWarning,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Device is offline. Token will save locally, but connection requires internet.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = YellowWarning,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = {
                            tokenInput = it
                            errorMessage = null
                        },
                        label = { Text("Connection Token (tc...)") },
                        supportingText = {
                            Text(
                                "Generated by an exit gateway (e.g. tailcat serve exit-node)",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Gateway Name (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Live Token Validation Preview
                    when (validationState) {
                        is TokenValidationState.Valid -> {
                            val parsed = validationState.parsed
                            val expText = if (parsed.expirationFormatted != null) " • Exp: ${parsed.expirationFormatted}" else ""
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(EmeraldConnected.copy(alpha = 0.12f))
                                    .border(1.dp, EmeraldConnected.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Valid",
                                        tint = EmeraldConnected,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${parsed.regionDisplayName} • Key: ${parsed.serverKeyShort}$expText",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = EmeraldConnected,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            }
                        }
                        is TokenValidationState.Expired -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(RedDegraded.copy(alpha = 0.15f))
                                    .border(1.dp, RedDegraded.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = "Expired",
                                        tint = RedDegraded,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Token expired on ${validationState.expiredDate}. Request a new token.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = RedDegraded,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        }
                        is TokenValidationState.Invalid -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(RedDegraded.copy(alpha = 0.12f))
                                    .border(1.dp, RedDegraded.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = "Invalid",
                                        tint = RedDegraded,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = validationState.reason,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = RedDegraded,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }
                        TokenValidationState.Empty -> {
                            // Do nothing
                        }
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(errorMessage!!, color = RedDegraded, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val result = viewModel.addProfileFromToken(nameInput, tokenInput)
                        if (result.isSuccess) {
                            showAddDialog = false
                        } else {
                            errorMessage = result.exceptionOrNull()?.message ?: "Invalid token"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    enabled = validationState is TokenValidationState.Valid
                ) {
                    Text("Save & Pair", color = BgDark)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun StatusBanner(
    message: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
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
