package com.tailcat.vpn.ui.screens.speedtest.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.tailcat.vpn.core.speedtest.SpeedTestStage
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.TailcatText
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.VioletDerp
import com.tailcat.vpn.ui.theme.rememberReducedMotion
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private const val MAX_SPEED_MBPS = 150f
private const val START_ANGLE = 150f
private const val SWEEP_ANGLE = 240f
private const val TOTAL_TICKS = 12

@Composable
fun SpeedometerGauge(
    currentSpeedMbps: Double,
    stage: SpeedTestStage,
    modifier: Modifier = Modifier
) {
    val targetRatio = (currentSpeedMbps.toFloat() / MAX_SPEED_MBPS).coerceIn(0f, 1f)
    // Held as a State and read only inside the draw lambda: the needle moves
    // without recomposing anything.
    val animatedRatio = animateFloatAsState(
        targetValue = targetRatio,
        animationSpec = if (rememberReducedMotion()) tween(0) else tween(350, easing = FastOutSlowInEasing),
        label = "NeedleSpeed"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(240.dp)
    ) {
        Spacer(
            modifier = Modifier
                .size(220.dp)
                .drawWithCache {
                    // Everything below is rebuilt only when the size changes.
                    val strokeWidth = 11.dp.toPx()
                    val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                    val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
                    val track = Stroke(width = strokeWidth, cap = StrokeCap.Round)

                    // Sweep gradients run clockwise from 3 o'clock. The arc spans
                    // 150°..390° (wrapping through 0°), so the stops put cyan at
                    // its start and violet at its end with no seam at 0°.
                    val startFraction = START_ANGLE / 360f
                    val endFraction = (START_ANGLE + SWEEP_ANGLE - 360f) / 360f
                    val gradient = Brush.sweepGradient(
                        0f to VioletDerp,
                        endFraction to VioletDerp,
                        startFraction to AccentCyan,
                        0.75f to EmeraldConnected,
                        1f to VioletDerp
                    )

                    val center = Offset(size.width / 2, size.height / 2)
                    val outer = (size.width / 2) - strokeWidth - 10.dp.toPx()
                    val major = 10.dp.toPx()
                    val minor = 5.dp.toPx()
                    val tickStarts = Array(TOTAL_TICKS + 1) { Offset.Zero }
                    val tickEnds = Array(TOTAL_TICKS + 1) { Offset.Zero }
                    for (i in 0..TOTAL_TICKS) {
                        val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * i / TOTAL_TICKS).toDouble())
                        val inner = if (i % 3 == 0) outer - major else outer - minor
                        tickStarts[i] = Offset(
                            (center.x + inner * cos(angle)).toFloat(),
                            (center.y + inner * sin(angle)).toFloat()
                        )
                        tickEnds[i] = Offset(
                            (center.x + outer * cos(angle)).toFloat(),
                            (center.y + outer * sin(angle)).toFloat()
                        )
                    }
                    val majorWidth = 3.dp.toPx()
                    val minorWidth = 1.5.dp.toPx()
                    val idleTick = TextSecondary.copy(alpha = 0.5f)
                    val inactiveArc = BorderSubtle.copy(alpha = 0.6f)

                    onDrawBehind {
                        val ratio = animatedRatio.value

                        drawArc(
                            color = inactiveArc,
                            startAngle = START_ANGLE,
                            sweepAngle = SWEEP_ANGLE,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = track
                        )

                        val activeSweep = SWEEP_ANGLE * ratio
                        if (activeSweep > 0f) {
                            drawArc(
                                brush = gradient,
                                startAngle = START_ANGLE,
                                sweepAngle = activeSweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = track
                            )
                        }

                        for (i in 0..TOTAL_TICKS) {
                            drawLine(
                                color = if (i / TOTAL_TICKS.toFloat() <= ratio) AccentCyan else idleTick,
                                start = tickStarts[i],
                                end = tickEnds[i],
                                strokeWidth = if (i % 3 == 0) majorWidth else minorWidth,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }
        )

        // Center content
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val stageLabel = when (stage) {
                SpeedTestStage.IDLE -> "READY"
                SpeedTestStage.MEASURING_PING -> "MEASURING PING"
                SpeedTestStage.TESTING_DOWNLOAD -> "DOWNLOAD"
                SpeedTestStage.TESTING_UPLOAD -> "UPLOAD"
                SpeedTestStage.COMPLETED -> "TEST COMPLETE"
                SpeedTestStage.FAILED -> "FAILED"
            }
            val stageColor = when (stage) {
                SpeedTestStage.MEASURING_PING, SpeedTestStage.TESTING_DOWNLOAD -> AccentCyan
                SpeedTestStage.TESTING_UPLOAD -> VioletDerp
                SpeedTestStage.COMPLETED -> EmeraldConnected
                else -> TextSecondary
            }

            Text(text = stageLabel, color = stageColor, style = TailcatText.Eyebrow)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = String.format(Locale.US, "%.1f", currentSpeedMbps),
                color = TextPrimary,
                style = TailcatText.Display
            )
            Text(text = "Mbps", color = TextSecondary, style = TailcatText.Eyebrow)
        }
    }
}
