package com.tailcat.vpn.ui.screens.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailcat.vpn.BuildConfig
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BgDark
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.SurfaceDark
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.YellowWarning
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

data class AppInfoItem(val packageName: String, val appName: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit = {}) {
    val context = LocalContext.current
    val app = remember { TailcatApplication.instance }
    val store = app.preferencesStore
    val engineAvailability = app.tunnelEngine.availability
    val updatesViewModel: SettingsViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(updatesViewModel) {
        updatesViewModel.uiEvent.collect { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var mtuText by remember { mutableStateOf(store.defaultMtu.toString()) }
    var dnsText by remember { mutableStateOf(store.defaultDns) }
    var excludedApps by remember { mutableStateOf(store.splitTunnelExcludedApps) }
    var appQuery by remember { mutableStateOf("") }
    var debugMode by remember { mutableStateOf(store.debugMode) }

    // Every package installed for this user, not only apps with a launcher
    // icon; headless/background apps must be selectable for exclusions.
    val installedApps = remember {
        val pm = context.packageManager
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }
        apps.map { info ->
            AppInfoItem(
                packageName = info.packageName,
                appName = runCatching { info.loadLabel(pm).toString() }
                    .getOrDefault(info.packageName)
            )
        }
            .filterNot { it.packageName == context.packageName }
            .filter { it.appName.isNotBlank() }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }
    val visibleApps = installedApps.filter { item ->
        appQuery.isBlank() ||
            item.appName.contains(appQuery, ignoreCase = true) ||
            item.packageName.contains(appQuery, ignoreCase = true)
    }

    Scaffold(
        containerColor = BgDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = SurfaceDark,
                contentColor = AccentCyan
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Connection") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Apps (${excludedApps.size})") }
                )
            }

            if (selectedTab == 0) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
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

                    SettingsCard(icon = Icons.Default.Security, title = "Always-on & kill switch") {
                        val lockdown = remember(context) {
                            com.tailcat.vpn.service.LockdownProbe.alwaysOnLockdownConfigured(
                                context.contentResolver,
                                context.packageName
                            )
                        }
                        Text(
                            when (lockdown) {
                                true -> "Status: Always-on VPN with ‘Block connections without VPN’ appears ON for this app."
                                false -> "Status: Always-on lockdown is not enabled for this app."
                                null -> "Status: Lockdown settings could not be read on this device."
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (lockdown == true) EmeraldConnected else TextSecondary
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Recommended on Android 10+: turn on Always-on VPN and ‘Block connections without VPN’ for stronger leak protection. Connect still works without them. Note: with lockdown on, Android blocks checked apps from using the network entirely.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                            }
                        ) {
                            Text("Open Android VPN settings")
                        }
                    }

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
                                onCheckedChange = { checked ->
                                    debugMode = checked
                                    store.debugMode = checked
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AccentCyan,
                                    uncheckedColor = BorderSubtle
                                )
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "When on, failure banners name the cause and append a telemetry snapshot (state, transport, health age, counters). Off by default; never changes routing or lockdown.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }

                    SettingsCard(icon = Icons.Default.Dns, title = "Defaults for new profiles") {
                        Text(
                            "MTU 1280 is the safe default for mobile and nested tunnels.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = mtuText,
                            onValueChange = { input ->
                                if (input.length <= 4 && input.all(Char::isDigit)) {
                                    mtuText = input
                                    input.toIntOrNull()?.takeIf { it in 1280..1500 }?.let {
                                        store.defaultMtu = it
                                    }
                                }
                            },
                            label = { Text("TUN MTU (1280–1500)") },
                            supportingText = {
                                if (mtuText.toIntOrNull() !in 1280..1500) {
                                    Text("Enter a value from 1280 to 1500")
                                }
                            },
                            isError = mtuText.toIntOrNull() !in 1280..1500,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(12.dp))
                        val dnsValidation = remember(dnsText) { com.tailcat.vpn.core.dns.DnsValidator.validate(dnsText) }
                        OutlinedTextField(
                            value = dnsText,
                            onValueChange = { input ->
                                dnsText = input
                                if (com.tailcat.vpn.core.dns.DnsValidator.isValid(input)) {
                                    store.defaultDns = input.trim()
                                }
                            },
                            label = { Text("Default DNS Resolver IP") },
                            supportingText = {
                                if (dnsValidation is com.tailcat.vpn.core.dns.DnsValidationResult.Invalid) {
                                    Text(dnsValidation.reason, color = RedDegraded, fontSize = 11.sp)
                                } else {
                                    Text("Default DNS for new profiles (e.g. 1.1.1.1, 9.9.9.9)", color = TextSecondary, fontSize = 11.sp)
                                }
                            },
                            isError = dnsValidation is com.tailcat.vpn.core.dns.DnsValidationResult.Invalid,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    SettingsCard(icon = Icons.Default.Dns, title = "Active profile DNS") {
                        val activeProfile by app.profileRepository.activeProfile.collectAsState()
                        var activeDnsText by remember(activeProfile?.id) {
                            mutableStateOf(activeProfile?.customDns ?: store.defaultDns)
                        }
                        var activePolicy by remember(activeProfile?.id) {
                            mutableStateOf(
                                activeProfile?.dnsPolicy
                                    ?: com.tailcat.vpn.core.model.DnsPolicy.PROFILE_RESOLVER
                            )
                        }
                        var activeDnsMessage by remember { mutableStateOf<String?>(null) }

                        if (activeProfile == null) {
                            Text(
                                "Pair a gateway profile first.",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                            )
                        } else {
                            Text(
                                activeProfile!!.name,
                                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                            )
                            Spacer(Modifier.height(8.dp))
                            val activeDnsValidation = remember(activeDnsText) {
                                com.tailcat.vpn.core.dns.DnsValidator.validate(activeDnsText)
                            }
                            OutlinedTextField(
                                value = activeDnsText,
                                onValueChange = { activeDnsText = it; activeDnsMessage = null },
                                label = { Text("Resolver IP") },
                                isError = activeDnsValidation is com.tailcat.vpn.core.dns.DnsValidationResult.Invalid,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            listOf(
                                com.tailcat.vpn.core.model.DnsPolicy.PROFILE_RESOLVER to "Profile resolver",
                                com.tailcat.vpn.core.model.DnsPolicy.FORCED_RESOLVER to "Forced resolver"
                            ).forEach { (policy, label) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activePolicy = policy }
                                ) {
                                    RadioButton(
                                        selected = activePolicy == policy,
                                        onClick = { activePolicy = policy },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = AccentCyan,
                                            unselectedColor = BorderSubtle
                                        )
                                    )
                                    Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                enabled = activeDnsValidation is com.tailcat.vpn.core.dns.DnsValidationResult.Valid,
                                onClick = {
                                    val result = app.profileRepository.updateProfileDns(
                                        profileId = activeProfile!!.id,
                                        customDns = activeDnsText,
                                        dnsPolicy = activePolicy
                                    )
                                    activeDnsMessage = result.fold(
                                        onSuccess = {
                                            val reconnect = app.tunnelController.tunnelState.value !=
                                                com.tailcat.vpn.core.model.TunnelState.DISCONNECTED
                                            if (reconnect) {
                                                "DNS saved. Reconnect for the new resolver to apply."
                                            } else {
                                                "DNS saved for ${activeProfile!!.name}."
                                            }
                                        },
                                        onFailure = { it.message ?: "Could not save DNS settings" }
                                    )
                                }
                            ) {
                                Text("Save profile DNS")
                            }
                            activeDnsMessage?.let { msg ->
                                Spacer(Modifier.height(6.dp))
                                Text(msg, style = MaterialTheme.typography.bodySmall.copy(color = AccentCyan))
                            }
                        }
                    }

                    SettingsUpdatesCard(viewModel = updatesViewModel)

                    SettingsCard(icon = Icons.Default.Info, title = "About & legal") {
                        Text(
                            "OpenTailcat • v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Apache License 2.0. Gateway tokens are encrypted on this device.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    OutlinedTextField(
                        value = appQuery,
                        onValueChange = { appQuery = it },
                        singleLine = true,
                        placeholder = { Text("Search apps", color = TextMuted) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp, vertical = 0.dp)
                    ) {
                    item {
                        val originalExclusions = remember { store.splitTunnelExcludedApps }
                        Text(
                            "Checked apps bypass the VPN and use the device network directly. Changes apply the next time the tunnel starts. The tunnel is not leak-free while any app is checked.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                            modifier = Modifier.padding(bottom = 12.dp, top = 6.dp)
                        )
                        val tunnelState by app.tunnelController.tunnelState.collectAsState()
                        if (tunnelState != com.tailcat.vpn.core.model.TunnelState.DISCONNECTED &&
                            excludedApps != originalExclusions
                        ) {
                            Text(
                                "Exclusion list changed while the tunnel is up. Disconnect and Connect again for it to apply.",
                                style = MaterialTheme.typography.bodySmall.copy(color = YellowWarning),
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        } else if (tunnelState != com.tailcat.vpn.core.model.TunnelState.DISCONNECTED) {
                            Text(
                                "Tunnel is running. New exclusions take effect on the next Connect.",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                    }

                    items(visibleApps, key = { it.packageName }) { item ->
                        val isExcluded = item.packageName in excludedApps
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceDark)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                                .clickable {
                                    excludedApps = if (isExcluded) {
                                        excludedApps - item.packageName
                                    } else {
                                        excludedApps + item.packageName
                                    }
                                    store.splitTunnelExcludedApps = excludedApps
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Icon(Icons.Default.Apps, null, tint = AccentCyan, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.appName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    item.packageName,
                                    style = MaterialTheme.typography.labelMedium.copy(color = TextMuted)
                                )
                            }
                            Checkbox(
                                checked = isExcluded,
                                onCheckedChange = { checked ->
                                    excludedApps = if (checked) {
                                        excludedApps + item.packageName
                                    } else {
                                        excludedApps - item.packageName
                                    }
                                    store.splitTunnelExcludedApps = excludedApps
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AccentCyan,
                                    uncheckedColor = BorderSubtle
                                )
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsUpdatesCard(
    viewModel: SettingsViewModel
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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

@Composable
private fun SettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium.copy(color = TextPrimary))
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
