package com.tailcat.vpn.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    secondary = EmeraldConnected,
    tertiary = VioletDerp,
    background = BgDark,
    surface = SurfaceDark,
    onPrimary = BgDark,
    onSecondary = BgDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    primaryContainer = AccentFill,
    onPrimaryContainer = TextPrimary,
    // Selected chips: the same fill as filled buttons, not Material's purple.
    secondaryContainer = AccentFill,
    onSecondaryContainer = TextPrimary,
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
    inversePrimary = AccentCyan
)

@Composable
fun TailcatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
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
}
