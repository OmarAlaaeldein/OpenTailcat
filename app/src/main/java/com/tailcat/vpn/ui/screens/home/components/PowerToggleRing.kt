package com.tailcat.vpn.ui.screens.home.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tailcat.vpn.core.model.TransportType
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.AmberConnecting
import com.tailcat.vpn.ui.theme.AmberGlow
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.EmeraldGlow
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.SurfaceDark
import com.tailcat.vpn.ui.theme.SurfaceElevated
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.VioletDerp
import com.tailcat.vpn.ui.theme.VioletGlow

@Composable
fun PowerToggleRing(
    state: TunnelState,
    transportType: TransportType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnecting = state == TunnelState.CONNECTING || state == TunnelState.RECONNECTING
    val isConnected = state == TunnelState.CONNECTED

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnecting || isConnected) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val activeColor by animateColorAsState(
        targetValue = when (state) {
            TunnelState.DISCONNECTED -> BorderSubtle
            TunnelState.CONNECTING, TunnelState.RECONNECTING -> AmberConnecting
            TunnelState.CONNECTED -> if (transportType == TransportType.DERP_RELAY) VioletDerp else EmeraldConnected
            TunnelState.DEGRADED -> RedDegraded
        },
        label = "activeColor"
    )

    val glowColor by animateColorAsState(
        targetValue = when (state) {
            TunnelState.DISCONNECTED -> Color.Transparent
            TunnelState.CONNECTING, TunnelState.RECONNECTING -> AmberGlow
            TunnelState.CONNECTED -> if (transportType == TransportType.DERP_RELAY) VioletGlow else EmeraldGlow
            TunnelState.DEGRADED -> Color(0x33FF5252)
        },
        label = "glowColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(240.dp)
            .clip(CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = if (isConnected || isConnecting) "Disconnect VPN" else "Connect VPN"
                stateDescription = state.name.lowercase().replace('_', ' ')
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        // Outer Glow Layer
        Box(
            modifier = Modifier
                .size(220.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(glowColor)
        )

        // Middle Ring
        Box(
            modifier = Modifier
                .size(190.dp)
                .clip(CircleShape)
                .background(SurfaceDark)
                .border(width = 3.dp, color = activeColor, shape = CircleShape)
        )

        // Center Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape)
                .background(SurfaceElevated)
                .border(width = 1.dp, color = BorderSubtle, shape = CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = if (isConnected || isConnecting) activeColor else TextMuted,
                modifier = Modifier.size(64.dp)
            )
        }
    }
}
