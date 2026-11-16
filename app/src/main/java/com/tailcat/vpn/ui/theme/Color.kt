package com.tailcat.vpn.ui.theme

import androidx.compose.ui.graphics.Color

// Backdrop: a shared vertical gradient drawn once behind every screen.
val BgTop = Color(0xFF0B1018)
val BgBottom = Color(0xFF070A0F)

/** Solid fallback / on-accent content color. */
val BgDark = Color(0xFF080C12)

// Surfaces. Panels draw SurfaceDark with a translucent highlight on top
// ("glass" without real-time blur, which is expensive on the API 26 baseline).
val SurfaceDark = Color(0xFF141B25)
val SurfaceElevated = Color(0xFF1C2532)
val BorderSubtle = Color(0xFF2A3647)

/** Hairline top edge of a panel. */
val GlassHighlight = Color(0x14FFFFFF)

val AccentCyan = Color(0xFF2DE0F2)

val EmeraldConnected = Color(0xFF2FE38A)
val EmeraldGlow = Color(0x4D2FE38A)

val AmberConnecting = Color(0xFFFFC247)
val AmberGlow = Color(0x4DFFC247)
val YellowWarning = Color(0xFFFFC247)

val VioletDerp = Color(0xFFB79CFF)
val VioletGlow = Color(0x4DB79CFF)

val RedDegraded = Color(0xFFFF6B6B)
val RedGlow = Color(0x4DFF6B6B)

// Text. Every foreground on every background used by the app is at least
// 4.5:1 (WCAG AA); ThemeContrastTest enforces it.
val TextPrimary = Color(0xFFF1F6FC)
val TextSecondary = Color(0xFFAAB4C0)
val TextMuted = Color(0xFF8896A6)
