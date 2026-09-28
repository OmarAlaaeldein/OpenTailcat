package com.tailcat.vpn.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailcat.vpn.core.model.GatewayProfile
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.SurfaceDark
import com.tailcat.vpn.ui.theme.SurfaceElevated
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary

/** Chip naming the active gateway, with a menu to switch, delete, or pair one. */
@Composable
fun ProfileSelector(
    activeProfile: GatewayProfile?,
    profiles: List<GatewayProfile>,
    onSelectProfile: (GatewayProfile) -> Unit,
    onDeleteProfile: (GatewayProfile) -> Unit,
    onAddProfile: () -> Unit
) {
    var showProfileDropdown by remember { mutableStateOf(false) }

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
                        onAddProfile()
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
                            onSelectProfile(profile)
                            showProfileDropdown = false
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    onDeleteProfile(profile)
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
                        onAddProfile()
                    }
                )
            }
        }
    }
}

@Composable
fun DeleteProfileDialog(
    profile: GatewayProfile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated,
        title = { Text("Delete ${profile.name}?", color = TextPrimary) },
        text = {
            Text(
                "Its token is removed from this device and cannot be recovered here. " +
                    "A running VPN on this gateway is disconnected.",
                color = TextSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = RedDegraded)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
