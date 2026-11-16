package com.tailcat.vpn.ui.screens.home.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import com.tailcat.vpn.core.model.TransportType
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.ui.theme.AmberConnecting
import com.tailcat.vpn.ui.theme.AmberGlow
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.EmeraldGlow
import com.tailcat.vpn.ui.theme.GlassHighlight
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.RedGlow
import com.tailcat.vpn.ui.theme.SurfaceDark
import com.tailcat.vpn.ui.theme.SurfaceElevated
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.VioletDerp
import com.tailcat.vpn.ui.theme.VioletGlow
import com.tailcat.vpn.ui.theme.rememberReducedMotion

/**
 * The connect / disconnect control.
 *
 * Drawn in a single draw pass instead of nested clipped boxes. Colors animate only
 * when the tunnel state changes; the pulse and the spinner exist only while
 * connecting and are read in the draw phase, so an idle ring does no
 * recomposition or per-frame work at all. With animations turned off in the
 * system settings nothing moves.
 *
 * The semantics (role, "Connect VPN" / "Disconnect VPN", state description)
 * are relied on by the emulator scripts and TalkBack; keep them.
 */
@Composable
fun PowerToggleRing(
    state: TunnelState,
    transportType: TransportType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnecting = state == TunnelState.CONNECTING || state == TunnelState.RECONNECTING
    val isConnected = state == TunnelState.CONNECTED
    val isDegraded = state == TunnelState.DEGRADED
    val reducedMotion = rememberReducedMotion()

    val activeColor by animateColorAsState(
        targetValue = when (state) {
            TunnelState.DISCONNECTED -> BorderSubtle
            TunnelState.CONNECTING, TunnelState.RECONNECTING -> AmberConnecting
            TunnelState.CONNECTED -> if (transportType == TransportType.DERP_RELAY) VioletDerp else EmeraldConnected
            TunnelState.DEGRADED -> RedDegraded
        },
        animationSpec = tween(350),
        label = "activeColor"
    )
    val glowColor by animateColorAsState(
        targetValue = when (state) {
            TunnelState.DISCONNECTED -> Color.Transparent
            TunnelState.CONNECTING, TunnelState.RECONNECTING -> AmberGlow
            TunnelState.CONNECTED -> if (transportType == TransportType.DERP_RELAY) VioletGlow else EmeraldGlow
            TunnelState.DEGRADED -> RedGlow
        },
        animationSpec = tween(350),
        label = "glowColor"
    )
    val iconTint by animateColorAsState(
        targetValue = if (state == TunnelState.DISCONNECTED) TextMuted else activeColor,
        animationSpec = tween(350),
        label = "iconTint"
    )

    // 0..1, advancing only while connecting. The State is read inside the draw
    // lambda below, so each frame invalidates drawing, never composition.
    val phase: State<Float> = if (isConnecting && !reducedMotion) {
        rememberInfiniteTransition(label = "connecting").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
            label = "phase"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale = animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale.value
                scaleY = pressScale.value
            }
            .clip(CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = if (isConnected || isConnecting) "Disconnect VPN" else "Connect VPN"
                stateDescription = state.name.lowercase().replace('_', ' ')
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    // Built once per size / color change, not per frame.
                    val radius = size.minDimension / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val glow = Brush.radialGradient(
                        0.55f to glowColor,
                        1f to Color.Transparent,
                        center = center,
                        radius = radius
                    )
                    val disc = Brush.verticalGradient(
                        colors = listOf(SurfaceElevated, SurfaceDark),
                        startY = center.y - radius * 0.67f,
                        endY = center.y + radius * 0.67f
                    )
                    val rim = Brush.verticalGradient(
                        colors = listOf(GlassHighlight, Color.Transparent),
                        startY = center.y - radius * 0.67f,
                        endY = center.y
                    )
                    val ringStroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    val trackStroke = Stroke(width = 2.dp.toPx())
                    val hairline = Stroke(width = 1.dp.toPx())
                    onDrawBehind {
                        val pulse = if (isConnecting && !reducedMotion) {
                            // Triangle wave 0..1..0 from the linear phase.
                            val t = phase.value
                            1f + 0.04f * (1f - abs(2f * t - 1f))
                        } else {
                            1f
                        }
                        if (glowColor.alpha > 0f) {
                            scale(pulse, pivot = center) { drawCircle(brush = glow, radius = radius, center = center) }
                        }

                        val ringRadius = radius * 0.836f
                        drawCircle(color = BorderSubtle, radius = ringRadius, center = center, style = trackStroke)
                        when {
                            isConnecting && !reducedMotion ->
                                drawArc(
                                    color = activeColor,
                                    startAngle = phase.value * 360f - 90f,
                                    sweepAngle = 110f,
                                    useCenter = false,
                                    topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                                    size = Size(ringRadius * 2f, ringRadius * 2f),
                                    style = ringStroke
                                )
                            isConnecting || isConnected || isDegraded ->
                                drawCircle(
                                    color = if (isConnecting) activeColor.copy(alpha = 0.6f) else activeColor,
                                    radius = ringRadius,
                                    center = center,
                                    style = ringStroke
                                )
                        }

                        val discRadius = radius * 0.673f
                        drawCircle(brush = disc, radius = discRadius, center = center)
                        drawCircle(brush = rim, radius = discRadius, center = center, style = hairline)
                        drawCircle(color = BorderSubtle, radius = discRadius, center = center, style = hairline)
                    }
                }
        )

        Icon(
            imageVector = Icons.Default.PowerSettingsNew,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(56.dp)
        )
    }
}
