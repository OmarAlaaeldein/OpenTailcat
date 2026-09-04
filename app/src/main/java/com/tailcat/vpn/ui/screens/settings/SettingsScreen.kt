package com.tailcat.vpn.ui.screens.settings

import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
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
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

data class AppInfoItem(val packageName: String, val appName: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit = {}) {
    val context = LocalContext.current
    val app = remember { TailcatApplication.instance }
    val store = app.preferencesStore
    val engineAvailability = app.tunnelEngine.availability

    var selectedTab by remember { mutableIntStateOf(0) }
    var mtuText by remember { mutableStateOf(store.defaultMtu.toString()) }
    var dnsText by remember { mutableStateOf(store.defaultDns) }
    var excludedApps by remember { mutableStateOf(store.splitTunnelExcludedApps) }

    val installedApps = remember {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        launcherApps.getActivityList(null, Process.myUserHandle())
            .map { activity ->
                AppInfoItem(
                    packageName = activity.applicationInfo.packageName,
                    appName = activity.label.toString()
                )
            }
            .filterNot { it.packageName == context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    Scaffold(
        containerColor = BgDark,
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
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
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
                        if (!engineAvailability.isAvailable) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Connections stay disabled until a compatible libtailcat AAR advertises a working WireGuard and Magicsock data plane.",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                            )
                        }
                    }

                    SettingsCard(icon = Icons.Default.Security, title = "Always-on & kill switch") {
                        Text(
                            "Android owns these protections. Select Tailcat as the Always-on VPN, then enable ‘Block connections without VPN’ in system settings.",
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
                            "Apache License 2.0. Gateway tokens are encrypted on this device. Development test build — not leak-free, not a production VPN.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    item {
                        Text(
                            "Checked apps bypass the VPN. Changes apply the next time the tunnel starts.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                            modifier = Modifier.padding(bottom = 12.dp, top = 6.dp)
                        )
                    }

                    items(installedApps, key = { it.packageName }) { item ->
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

@Composable
private fun SettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
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
                Icon(icon, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium.copy(color = TextPrimary))
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
