package com.tailcat.vpn.ui.screens.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.service.SplitTunnelExclusions
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.TailcatButtons
import com.tailcat.vpn.ui.theme.TailcatShapes
import com.tailcat.vpn.ui.theme.panel
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.YellowWarning

data class AppInfoItem(
    val packageName: String,
    val appName: String,
    val uid: Int = -1,
    /** Other apps sharing this UID: Android excludes them together. */
    val sharedWith: List<String> = emptyList()
)

/** Loads every installed package; runs off the main thread (hundreds of labels). */
internal fun loadInstalledApps(context: Context): List<AppInfoItem> {
    val pm = context.packageManager
    val apps = if (Build.VERSION.SDK_INT >= 33) {
        pm.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
    }
    val labelled = apps
        .filterNot { it.packageName == context.packageName }
        .map { info ->
            Triple(
                info.packageName,
                runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName),
                info.uid
            )
        }
        .filter { it.second.isNotBlank() }
        .distinctBy { it.first }
    val peers = SplitTunnelExclusions.sharedUidPeers(labelled)
    return labelled
        .map { (pkg, name, uid) -> AppInfoItem(pkg, name, uid, peers[pkg].orEmpty()) }
        .sortedBy { it.appName.lowercase() }
}

/** Split-tunnel picker: checked apps bypass the VPN. */
@Composable
internal fun AppExclusionsTab(
    app: TailcatApplication,
    installedApps: List<AppInfoItem>?,
    appQuery: String,
    onAppQueryChange: (String) -> Unit,
    excludedApps: Set<String>,
    onExcludedAppsChange: (Set<String>) -> Unit
) {
    // Re-filtered only when the list or the query changes, not on every
    // recomposition (the list can hold hundreds of apps).
    val visibleApps = remember(installedApps, excludedApps, appQuery) {
        val installed = installedApps.orEmpty()
        val installedPackages = installed.mapTo(HashSet(installed.size)) { it.packageName }
        val uninstalledExcluded = (excludedApps - installedPackages)
            .sorted()
            .map { pkg -> AppInfoItem(packageName = pkg, appName = "$pkg (not installed)") }
        (uninstalledExcluded + installed).filter { item ->
            appQuery.isBlank() ||
                item.appName.contains(appQuery, ignoreCase = true) ||
                item.packageName.contains(appQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        OutlinedTextField(
            value = appQuery,
            onValueChange = onAppQueryChange,
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
                val appliedExclusions by app.tunnelController.appliedExclusions.collectAsStateWithLifecycle()
                Text(
                    "Selected apps bypass the VPN and use the device network directly. Changes apply " +
                        "the next time the tunnel starts. The tunnel is not leak-free while any app is excluded.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                    modifier = Modifier.padding(bottom = 12.dp, top = 6.dp)
                )
                val tunnelState by app.tunnelController.tunnelState.collectAsStateWithLifecycle()
                if (tunnelState != TunnelState.DISCONNECTED &&
                    appliedExclusions != null && excludedApps != appliedExclusions
                ) {
                    Text(
                        "The exclusion list changed while the tunnel is active. Disconnect and reconnect to apply it.",
                        style = MaterialTheme.typography.bodySmall.copy(color = YellowWarning),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                } else if (tunnelState != TunnelState.DISCONNECTED) {
                    Text(
                        "Changes take effect on the next connection.",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                if (installedApps == null) {
                    Text(
                        "Loading installed apps…",
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
                        .panel(TailcatShapes.Tile)
                        .clip(TailcatShapes.Tile)
                        .toggleable(
                            value = isExcluded,
                            role = Role.Checkbox,
                            onValueChange = { checked ->
                                onExcludedAppsChange(
                                    if (checked) {
                                        excludedApps + item.packageName
                                    } else {
                                        excludedApps - item.packageName
                                    }
                                )
                            }
                        )
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
                        if (item.sharedWith.isNotEmpty()) {
                            Text(
                                "Shares its network identity with ${item.sharedWith.take(3).joinToString()}" +
                                    (if (item.sharedWith.size > 3) " and ${item.sharedWith.size - 3} more" else "") +
                                    "; excluding one excludes all of them.",
                                style = MaterialTheme.typography.labelMedium.copy(color = YellowWarning)
                            )
                        }
                        if (SplitTunnelExclusions.isSystemUid(item.uid)) {
                            Text(
                                "System component (uid ${item.uid}).",
                                style = MaterialTheme.typography.labelMedium.copy(color = YellowWarning)
                            )
                        }
                    }
                    Checkbox(
                        checked = isExcluded,
                        onCheckedChange = null,
                        colors = TailcatButtons.checkbox(uncheckedColor = TextMuted)
                    )
                }
            }
        }
    }
}
