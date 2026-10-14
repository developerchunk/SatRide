package com.developerstring.satride.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * "Cosmic Playground" palette — Kurzgesagt-style deep indigo space with saturated, toy-like
 * accents. See docs/design/kurzgesagt-style.md for the full art direction.
 *
 * One accent per domain so a glance tells you what kind of information you are looking at:
 * cyan = tracking/orbits, amber = RF/radio, magenta = decoded artifacts. The extra scene
 * accents (sunshine, coral, lavender, mint, skyBlue) exist for the illustrated art layer.
 */
internal object SatRidePalette {
    val Base = Color(0xFF111539)
    val Surface = Color(0xFF1B2150)
    val SurfaceElevated = Color(0xFF262E68)
    val DeepShadow = Color(0xFF0B0E2A)
    val Hairline = Color(0x24FFFFFF)

    val TextPrimary = Color(0xFFF4F6FF)
    val TextSecondary = Color(0xFFA8B3E6)
    val TextTertiary = Color(0xFF6E7AB8)

    val TrackingCyan = Color(0xFF3FE0FF)
    val TrackingCyanDim = Color(0xFF2277A0)
    val RadioAmber = Color(0xFFFFC94D)
    val RadioAmberDim = Color(0xFFA06B26)
    val ArtifactMagenta = Color(0xFFFF54C8)

    val Success = Color(0xFF43F0A0)
    val Warning = Color(0xFFFFD84D)
    val Danger = Color(0xFFFF5C74)

    // Scene accents for the illustrated art layer.
    val Sunshine = Color(0xFFFFE14D)
    val Coral = Color(0xFFFF7E5C)
    val Lavender = Color(0xFF8E7BFF)
    val Mint = Color(0xFFB9F6D8)
    val SkyBlue = Color(0xFF4D7DFF)

    // Night mode: red-on-black preserves dark adaptation for observers at a telescope.
    val NightBase = Color(0xFF0A0000)
    val NightSurface = Color(0xFF160303)
    val NightPrimary = Color(0xFFFF3B30)
    val NightTextPrimary = Color(0xFFFF6B60)
    val NightTextSecondary = Color(0xFFB33A33)
}

/**
 * Domain accents, resolved through the theme so night mode can collapse them all to red.
 */
data class SatRideAccents(
    val tracking: Color,
    val trackingDim: Color,
    val radio: Color,
    val radioDim: Color,
    val artifact: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val hairline: Color,
    // Scene accents: the illustrated art layer draws with these, never raw hex, so night
    // mode's red-on-black collapse applies to artwork too.
    val sunshine: Color,
    val coral: Color,
    val lavender: Color,
    val mint: Color,
    val skyBlue: Color,
    /** The darkest band; button edges, shadow sides of props. */
    val shadow: Color,
)
