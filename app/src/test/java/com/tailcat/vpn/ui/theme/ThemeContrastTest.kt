package com.tailcat.vpn.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every text/accent token must meet WCAG AA (4.5:1) on every background the
 * app actually draws it on. The old muted text color measured 3.4-4.3:1.
 */
class ThemeContrastTest {

    private fun channel(c: Float): Double =
        if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private val backgrounds = mapOf(
        "BgTop" to BgTop,
        "BgBottom" to BgBottom,
        "SurfaceDark" to SurfaceDark,
        "SurfaceElevated" to SurfaceElevated
    )

    private val foregrounds = mapOf(
        "TextPrimary" to TextPrimary,
        "TextSecondary" to TextSecondary,
        "TextMuted" to TextMuted,
        "AccentCyan" to AccentCyan,
        "EmeraldConnected" to EmeraldConnected,
        "AmberConnecting" to AmberConnecting,
        "YellowWarning" to YellowWarning,
        "VioletDerp" to VioletDerp,
        "RedDegraded" to RedDegraded
    )

    @Test
    fun textAndAccentsMeetAaOnEverySurface() {
        val failures = buildList {
            for ((fn, fg) in foregrounds) for ((bn, bg) in backgrounds) {
                val ratio = contrast(fg, bg)
                if (ratio < 4.5) add("%s on %s = %.2f".format(fn, bn, ratio))
            }
        }
        assertTrue("Below 4.5:1:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun onAccentContentIsReadable() {
        // Filled buttons draw BgDark on the accent.
        val ratio = contrast(BgDark, AccentCyan)
        assertTrue("BgDark on AccentCyan = %.2f".format(ratio), ratio >= 4.5)
    }

    @Test
    fun mutedTextIsStillQuieterThanSecondary() {
        assertTrue(luminance(TextMuted) < luminance(TextSecondary))
        assertTrue(luminance(TextSecondary) < luminance(TextPrimary))
    }
}
