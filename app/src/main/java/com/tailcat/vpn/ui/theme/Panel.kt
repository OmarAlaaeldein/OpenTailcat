package com.tailcat.vpn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Corner radii shared by every surface so the app reads as one system. */
object TailcatShapes {
    val Card = RoundedCornerShape(16.dp)
    val Tile = RoundedCornerShape(12.dp)
    val Chip = RoundedCornerShape(20.dp)
    val Control = RoundedCornerShape(12.dp)
}

/**
 * A "glass" surface: tonal fill, a faint top highlight, and a hairline border,
 * all in a single draw pass. It replaces the `clip + background + border`
 * chain (three modifier nodes, two of them clipping layers) and caches the
 * outline and brush per size, so nothing is allocated while drawing.
 *
 * It does not clip content. Clickable surfaces should add
 * `.clip(shape).clickable { }` after it so the ripple stays inside the corners.
 */
fun Modifier.panel(
    shape: Shape = TailcatShapes.Card,
    fill: Color = SurfaceDark,
    border: Color = BorderSubtle
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val highlight = Brush.verticalGradient(
        colors = listOf(GlassHighlight, Color.Transparent),
        startY = 0f,
        endY = size.height * 0.6f
    )
    val hairline = Stroke(width = 1.dp.toPx())
    onDrawBehind {
        drawOutline(outline, color = fill)
        drawOutline(outline, brush = highlight)
        drawOutline(outline, color = border, style = hairline)
    }
}
