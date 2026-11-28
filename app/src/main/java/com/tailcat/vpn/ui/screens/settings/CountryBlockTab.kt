package com.tailcat.vpn.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.geo.CountryInfo
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.TailcatButtons
import com.tailcat.vpn.ui.theme.TailcatShapes
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.YellowWarning
import com.tailcat.vpn.ui.theme.panel

/**
 * Country filter: checked countries' IP ranges are refused by the tunnel.
 * [countries] is null while the bundled data loads; [loadFailed] means it could
 * not be read. A previously saved selection is kept either way.
 */
@Composable
internal fun CountryBlockTab(
    app: TailcatApplication,
    countries: List<CountryInfo>?,
    loadFailed: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    blocked: Set<String>,
    onBlockedChange: (Set<String>) -> Unit
) {
    val visible = remember(countries, query) {
        val all = countries.orEmpty()
        val needle = query.trim()
        if (needle.isEmpty()) {
            all
        } else {
            all.filter {
                it.name.contains(needle, ignoreCase = true) || it.code.equals(needle, ignoreCase = true)
            }
        }
    }
    val blockedNames = remember(countries, blocked) {
        countries.orEmpty().filter { it.code in blocked }.map { it.name }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Search countries", color = TextMuted) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            item {
                val tunnelState by app.tunnelController.tunnelState.collectAsStateWithLifecycle()
                val applied by app.tunnelController.appliedBlockedCountries.collectAsStateWithLifecycle()
                Text(
                    "Connections from apps to blocked countries are refused and fail immediately. " +
                        "This filter is best effort: ranges are attributed to the country where a " +
                        "registry allocated them, so sites served from CDNs or cloud providers in " +
                        "other countries are not blocked. The list updates only with app releases. " +
                        "If the configured DNS resolver is in a blocked country, the connection will " +
                        "not start. Changes apply the next time the tunnel starts.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                    modifier = Modifier.padding(bottom = 12.dp, top = 6.dp)
                )
                if (tunnelState != TunnelState.DISCONNECTED &&
                    applied != null && blocked != applied
                ) {
                    Text(
                        "The country list changed while the tunnel is active. Disconnect and reconnect to apply it.",
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
                if (loadFailed) {
                    Text(
                        "The country data could not be read. " +
                            if (blocked.isEmpty()) {
                                "Reinstall the app to restore it."
                            } else {
                                "The selection is preserved, but connections will not start until the data is restored or the selection is cleared."
                            },
                        style = MaterialTheme.typography.bodySmall.copy(color = YellowWarning),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                } else if (countries == null) {
                    Text(
                        "Loading countries…",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                if (blocked.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Text(
                            // Names need the data; fall back to the saved codes.
                            "Blocking: " + blockedNames.ifEmpty { blocked.sorted() }.joinToString(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onBlockedChange(emptySet()) }) {
                            Text("Clear", color = AccentCyan)
                        }
                    }
                }
            }

            items(visible, key = { it.code }) { item ->
                val isBlocked = item.code in blocked
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .panel(TailcatShapes.Tile)
                        .clip(TailcatShapes.Tile)
                        .toggleable(
                            value = isBlocked,
                            role = Role.Checkbox,
                            onValueChange = { checked ->
                                onBlockedChange(if (checked) blocked + item.code else blocked - item.code)
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(item.flag, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${item.code} · ${"%,d".format(item.prefixCount)} ranges",
                            style = MaterialTheme.typography.labelMedium.copy(color = TextMuted)
                        )
                    }
                    Checkbox(
                        checked = isBlocked,
                        onCheckedChange = null,
                        colors = TailcatButtons.checkbox(uncheckedColor = TextMuted)
                    )
                }
            }
        }
    }
}
