package com.tailcat.vpn.ui.screens.speedtest.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailcat.vpn.core.speedtest.SpeedTestStage
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.VioletDerp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SpeedometerGauge(
    currentSpeedMbps: Double,
    stage: SpeedTestStage,
    modifier: Modifier = Modifier
) {
    val maxSpeed = 150f
    val sweepAngle = 240f
    val startAngle = 150f

    // Clamp speed to ratio
    val targetRatio = (currentSpeedMbps.toFloat() / maxSpeed).coerceIn(0f, 1f)
    val animatedRatio by animateFloatAsState(
        targetValue = targetRatio,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "NeedleSpeed"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(260.dp)
    ) {
        Canvas(modifier = Modifier.size(240.dp)) {
            val strokeWidth = 14.dp.toPx()
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

            // 1. Background Inactive Arc
            drawArc(
                color = BorderSubtle.copy(alpha = 0.5f),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // 2. Active Gradient Arc
            val activeSweep = sweepAngle * animatedRatio
            if (activeSweep > 0) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to AccentCyan,
                        0.5f to EmeraldConnected,
                        1.0f to VioletDerp
                    ),
                    startAngle = startAngle,
                    sweepAngle = activeSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // 3. Ticks around the dial
            val center = Offset(size.width / 2, size.height / 2)
            val radius = (size.width / 2) - strokeWidth - 10.dp.toPx()
            val totalTicks = 12

            for (i in 0..totalTicks) {
                val tickFraction = i / totalTicks.toFloat()
                val tickAngle = Math.toRadians((startAngle + sweepAngle * tickFraction).toDouble())
                val innerRadius = if (i % 3 == 0) radius - 10.dp.toPx() else radius - 5.dp.toPx()

                val start = Offset(
                    (center.x + innerRadius * cos(tickAngle)).toFloat(),
                    (center.y + innerRadius * sin(tickAngle)).toFloat()
                )
                val end = Offset(
                    (center.x + radius * cos(tickAngle)).toFloat(),
                    (center.y + radius * sin(tickAngle)).toFloat()
                )

                val tickColor = if (tickFraction <= animatedRatio) AccentCyan else TextSecondary.copy(alpha = 0.4f)
                drawLine(
                    color = tickColor,
                    start = start,
                    end = end,
                    strokeWidth = if (i % 3 == 0) 3.dp.toPx() else 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        // Center Content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val stageLabel = when (stage) {
                SpeedTestStage.IDLE -> "READY"
                SpeedTestStage.MEASURING_PING -> "PINGING..."
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

            Text(
                text = stageLabel,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = stageColor,
                    letterSpacing = 1.2.sp,
                    fontSize = 12.sp
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = String.format(java.util.Locale.US, "%.1f", currentSpeedMbps),
                style = MaterialTheme.typography.displayLarge.copy(
                    color = TextPrimary,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            Text(
                text = "Mbps",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            )
        }
    }
}
