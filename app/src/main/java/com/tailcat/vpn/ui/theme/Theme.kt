package com.tailcat.vpn.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Every `on*` role is a light text token. Material's dark scheme puts dark
 * text on its bright primary, and this app used to do the same with [BgDark].
 * [BgDark] is a background only. [AccentCyan] stays the accent for text,
 * outlines, radios and field focus; it is too bright to carry light text, so
 * filled controls use [AccentFill] with [TextPrimary] ([TailcatButtons]).
 */
internal val TailcatColorScheme = darkColorScheme(
    primary = AccentCyan,
    secondary = EmeraldConnected,
    tertiary = VioletDerp,
    background = BgDark,
    surface = SurfaceDark,
    onPrimary = TextPrimary,
    onSecondary = TextPrimary,
    onTertiary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    primaryContainer = AccentFill,
    onPrimaryContainer = TextPrimary,
    // Selected chips: the same fill as filled buttons, not Material's purple.
    secondaryContainer = AccentFill,
    onSecondaryContainer = TextPrimary,
    tertiaryContainer = SurfaceElevated,
    onTertiaryContainer = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    // Dialogs and menus draw on these; Material's defaults are purple-tinted.
    surfaceContainerLowest = BgDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceElevated,
    surfaceContainerHighest = SurfaceElevated,
    surfaceTint = AccentCyan,
    outline = BorderSubtle,
    outlineVariant = BorderSubtle,
    // Material's default snackbar is "inverse": a light bar with dark text.
    // In this app it stays dark with light text.
    inverseSurface = SurfaceElevated,
    inverseOnSurface = TextPrimary,
    inversePrimary = AccentCyan,
    error = RedDegraded,
    onError = TextPrimary,
    errorContainer = SurfaceElevated,
    onErrorContainer = TextPrimary,
    // Fixed roles keep one tone in both themes. Material's are a light fill
    // with dark text; here they match the filled-button pair.
    primaryFixed = AccentFill,
    primaryFixedDim = AccentFill,
    onPrimaryFixed = TextPrimary,
    onPrimaryFixedVariant = TextSecondary,
    secondaryFixed = AccentFill,
    secondaryFixedDim = AccentFill,
    onSecondaryFixed = TextPrimary,
    onSecondaryFixedVariant = TextSecondary,
    tertiaryFixed = SurfaceElevated,
    tertiaryFixedDim = SurfaceElevated,
    onTertiaryFixed = TextPrimary,
    onTertiaryFixedVariant = TextSecondary
)

@Composable
fun TailcatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TailcatColorScheme,
        typography = Typography,
        content = content
    )
}

object TailcatButtons {
    /** The one filled-button style: light text on [AccentFill]. */
    @Composable
    fun filled(): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = AccentFill,
        contentColor = TextPrimary,
        disabledContainerColor = SurfaceElevated,
        disabledContentColor = TextMuted
    )

    /**
     * Checked box is [AccentFill] with a [TextPrimary] mark. Material draws
     * the mark with onPrimary on primary, and [AccentCyan] cannot carry light text.
     */
    @Composable
    fun checkbox(uncheckedColor: Color = BorderSubtle): CheckboxColors =
        CheckboxDefaults.colors(
            checkedColor = AccentFill,
            checkmarkColor = TextPrimary,
            uncheckedColor = uncheckedColor,
            disabledCheckedColor = SurfaceElevated,
            disabledUncheckedColor = uncheckedColor
        )
}
