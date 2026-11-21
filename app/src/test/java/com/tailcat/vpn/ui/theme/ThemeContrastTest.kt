package com.tailcat.vpn.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
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
    fun filledButtonTextIsLightAndReadable() {
        // Filled buttons draw TextPrimary on AccentFill (TailcatButtons.filled).
        val ratio = contrast(TextPrimary, AccentFill)
        assertTrue("TextPrimary on AccentFill = %.2f".format(ratio), ratio >= 4.5)
        assertTrue("button text must be the light one", luminance(TextPrimary) > luminance(AccentFill))
        // Disabled: TextMuted on SurfaceElevated, covered by the surface check above.
    }

    @Test
    fun filledButtonsStandOutFromTheBackground() {
        // WCAG 1.4.11: a control's boundary needs 3:1 against what is behind it.
        for ((name, bg) in backgrounds) {
            val ratio = contrast(AccentFill, bg)
            assertTrue("AccentFill on %s = %.2f".format(name, ratio), ratio >= 3.0)
        }
    }

    @Test
    fun mutedTextIsStillQuieterThanSecondary() {
        assertTrue(luminance(TextMuted) < luminance(TextSecondary))
        assertTrue(luminance(TextSecondary) < luminance(TextPrimary))
    }

    @Test
    fun contentRolesAreLightText() {
        // Material's dark scheme uses dark text on bright accents. None of
        // those roles may be a background color or darker than muted text.
        val scheme = TailcatColorScheme
        val roles = mapOf(
            "onPrimary" to scheme.onPrimary,
            "onSecondary" to scheme.onSecondary,
            "onTertiary" to scheme.onTertiary,
            "onBackground" to scheme.onBackground,
            "onSurface" to scheme.onSurface,
            "onSurfaceVariant" to scheme.onSurfaceVariant,
            "onPrimaryContainer" to scheme.onPrimaryContainer,
            "onSecondaryContainer" to scheme.onSecondaryContainer,
            "onTertiaryContainer" to scheme.onTertiaryContainer,
            "inverseOnSurface" to scheme.inverseOnSurface,
            "onError" to scheme.onError,
            "onErrorContainer" to scheme.onErrorContainer,
            "onPrimaryFixed" to scheme.onPrimaryFixed,
            "onPrimaryFixedVariant" to scheme.onPrimaryFixedVariant,
            "onSecondaryFixed" to scheme.onSecondaryFixed,
            "onSecondaryFixedVariant" to scheme.onSecondaryFixedVariant,
            "onTertiaryFixed" to scheme.onTertiaryFixed,
            "onTertiaryFixedVariant" to scheme.onTertiaryFixedVariant
        )
        val floor = luminance(TextMuted)
        val backgrounds = setOf(BgDark, BgTop, BgBottom, SurfaceDark, SurfaceElevated)
        val failures = buildList {
            for ((name, color) in roles) {
                if (luminance(color) + 1e-9 < floor) {
                    add("$name is darker than muted text")
                }
                if (color in backgrounds) add("$name is a background color")
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertEquals(TextPrimary, scheme.onPrimary)
        assertEquals(TextPrimary, scheme.onSecondary)
        assertEquals(TextPrimary, scheme.onPrimaryFixed)
        assertEquals(AccentFill, scheme.primaryFixed)
    }
}
