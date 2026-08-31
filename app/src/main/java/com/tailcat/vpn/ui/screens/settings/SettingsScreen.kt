package com.tailcat.vpn.ui.screens.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BgDark
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.SurfaceDark
import com.tailcat.vpn.ui.theme.SurfaceElevated
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary

data class AppInfoItem(
    val packageName: String,
    val appName: String,
    val isExcluded: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val store = remember { TailcatApplication.instance.preferencesStore }

    var selectedTab by remember { mutableIntStateOf(0) }

    var killSwitch by remember { mutableStateOf(store.isKillSwitchEnabled) }
    var autoDerp by remember { mutableStateOf(store.isAutoDerpOnEnterpriseWifi) }
    var mtuText by remember { mutableStateOf(store.defaultMtu.toString()) }
    var mssText by remember { mutableStateOf(store.defaultTcpMss.toString()) }

    var excludedApps by remember { mutableStateOf(store.splitTunnelExcludedApps) }

    val installedApps = remember {
        val pm = context.packageManager
        val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val launcherApps = pm.queryIntentActivities(mainIntent, 0).map { resolveInfo ->
            AppInfoItem(
                packageName = resolveInfo.activityInfo.packageName,
                appName = resolveInfo.loadLabel(pm).toString(),
                isExcluded = excludedApps.contains(resolveInfo.activityInfo.packageName)
            )
        }.distinctBy { it.packageName }.sortedBy { it.appName }
        launcherApps
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("Settings & Split Tunneling", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
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
                    text = { Text("Network & Security") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Split Tunneling (${excludedApps.size})") }
                )
            }

            if (selectedTab == 0) {
                // Network Settings Tab
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // Kill-Switch Card
                    SettingToggleCard(
                        title = "Kill-Switch (Always-On Mode)",
                        subtitle = "Blocks all unencrypted traffic if tunnel disconnects",
                        icon = Icons.Default.Security,
                        checked = killSwitch,
                        onCheckedChange = {
                            killSwitch = it
                            store.isKillSwitchEnabled = it
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Auto-DERP Card
                    SettingToggleCard(
                        title = "Auto-DERP on Enterprise Wi-Fi",
                        subtitle = "Forces DERP on 802.1x/isolated APs to prevent packet blackholing",
                        icon = Icons.Default.Tune,
                        checked = autoDerp,
                        onCheckedChange = {
                            autoDerp = it
                            store.isAutoDerpOnEnterpriseWifi = it
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Invariants (MTU / MSS)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = "MTU",
                                    tint = AccentCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Tunnel Invariants (Double-Tunnel Safety)",
                                    style = MaterialTheme.typography.titleMedium.copy(color = TextPrimary)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = mtuText,
                                    onValueChange = {
                                        mtuText = it
                                        it.toIntOrNull()?.let { v -> store.defaultMtu = v }
                                    },
                                    label = { Text("TUN MTU (1280)") },
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = mssText,
                                    onValueChange = {
                                        mssText = it
                                        it.toIntOrNull()?.let { v -> store.defaultTcpMss = v }
                                    },
                                    label = { Text("TCP MSS (1120)") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Card 4: About & Legal
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "About",
                                    tint = AccentCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "About & Legal",
                                    style = MaterialTheme.typography.titleMedium.copy(color = TextPrimary)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Tailcat VPN for Android • v1.0.0",
                                style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Licensed under Apache License 2.0. Strict Zero-Log architecture with decentralized on-device WireGuard + Magicsock processing.",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, lineHeight = 18.sp)
                            )
                        }
                    }
                }
            } else {
                // Split Tunneling App List Tab
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    item {
                        Text(
                            text = "Checked apps will bypass the VPN tunnel and connect directly via your local network.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                            modifier = Modifier.padding(bottom = 12.dp, top = 6.dp)
                        )
                    }

                    items(installedApps) { app ->
                        val isExcluded = excludedApps.contains(app.packageName)

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
                                    val updated = if (isExcluded) {
                                        excludedApps - app.packageName
                                    } else {
                                        excludedApps + app.packageName
                                    }
                                    excludedApps = updated
                                    store.splitTunnelExcludedApps = updated
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Apps,
                                    contentDescription = "App",
                                    tint = AccentCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = app.appName,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            color = TextPrimary,
                                            fontSize = 15.sp
                                        )
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelMedium.copy(color = TextMuted)
                                    )
                                }
                            }

                            Checkbox(
                                checked = isExcluded,
                                onCheckedChange = { checked ->
                                    val updated = if (checked) {
                                        excludedApps + app.packageName
                                    } else {
                                        excludedApps - app.packageName
                                    }
                                    excludedApps = updated
                                    store.splitTunnelExcludedApps = updated
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

@Composable
private fun SettingToggleCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(color = TextPrimary)
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    )
                }
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = BgDark,
                    checkedTrackColor = AccentCyan,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = SurfaceElevated
                )
            )
        }
    }
}
