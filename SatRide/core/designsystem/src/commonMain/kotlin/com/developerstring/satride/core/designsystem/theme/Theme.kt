package com.developerstring.satride.core.designsystem.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * SatRide is dark-only by design: it is used outdoors at night, and a light theme would ruin
 * both the AR overlay contrast and the user's dark adaptation.
 *
 * [nightMode] switches to the red-on-black astronomy palette.
 */
@Composable
fun SatRideTheme(nightMode: Boolean = false, content: @Composable () -> Unit) {
    val colorScheme = if (nightMode) NightColorScheme else StandardColorScheme
    val accents = if (nightMode) NightAccents else StandardAccents

    MaterialTheme(
        colorScheme = colorScheme,
        typography = rememberSatRideTypography(),
        shapes = SatRideShapes,
    ) {
        // LocalContentColor must be provided explicitly: MaterialTheme does not derive it from
        // the color scheme, so without this any Text that does not name a colour keeps whatever
        // an enclosing Scaffold set. In night mode that leaves white text on the red palette,
        // which defeats the point of night mode entirely.
        CompositionLocalProvider(
            LocalSatRideAccents provides accents,
            LocalContentColor provides colorScheme.onBackground,
            content = content,
        )
    }
}

/** Domain accent colors for the active theme. */
object SatRideTheme {
    val accents: SatRideAccents
        @Composable @ReadOnlyComposable
        get() = LocalSatRideAccents.current
}

internal val LocalSatRideAccents = staticCompositionLocalOf { StandardAccents }

private val StandardColorScheme = darkColorScheme(
    primary = SatRidePalette.TrackingCyan,
    onPrimary = SatRidePalette.Base,
    secondary = SatRidePalette.RadioAmber,
    onSecondary = SatRidePalette.Base,
    tertiary = SatRidePalette.ArtifactMagenta,
    background = SatRidePalette.Base,
    onBackground = SatRidePalette.TextPrimary,
    surface = SatRidePalette.Surface,
    onSurface = SatRidePalette.TextPrimary,
    surfaceVariant = SatRidePalette.SurfaceElevated,
    onSurfaceVariant = SatRidePalette.TextSecondary,
    outline = SatRidePalette.TextTertiary,
    error = SatRidePalette.Danger,
)

private val NightColorScheme = darkColorScheme(
    primary = SatRidePalette.NightPrimary,
    onPrimary = SatRidePalette.NightBase,
    secondary = SatRidePalette.NightPrimary,
    onSecondary = SatRidePalette.NightBase,
    tertiary = SatRidePalette.NightPrimary,
    background = SatRidePalette.NightBase,
    onBackground = SatRidePalette.NightTextPrimary,
    surface = SatRidePalette.NightSurface,
    onSurface = SatRidePalette.NightTextPrimary,
    surfaceVariant = SatRidePalette.NightSurface,
    onSurfaceVariant = SatRidePalette.NightTextSecondary,
    outline = SatRidePalette.NightTextSecondary,
    error = SatRidePalette.NightPrimary,
)

private val StandardAccents = SatRideAccents(
    tracking = SatRidePalette.TrackingCyan,
    trackingDim = SatRidePalette.TrackingCyanDim,
    radio = SatRidePalette.RadioAmber,
    radioDim = SatRidePalette.RadioAmberDim,
    artifact = SatRidePalette.ArtifactMagenta,
    success = SatRidePalette.Success,
    warning = SatRidePalette.Warning,
    danger = SatRidePalette.Danger,
    hairline = SatRidePalette.Hairline,
    sunshine = SatRidePalette.Sunshine,
    coral = SatRidePalette.Coral,
    lavender = SatRidePalette.Lavender,
    mint = SatRidePalette.Mint,
    skyBlue = SatRidePalette.SkyBlue,
    shadow = SatRidePalette.DeepShadow,
)

private val NightAccents = SatRideAccents(
    tracking = SatRidePalette.NightPrimary,
    trackingDim = SatRidePalette.NightTextSecondary,
    radio = SatRidePalette.NightPrimary,
    radioDim = SatRidePalette.NightTextSecondary,
    artifact = SatRidePalette.NightPrimary,
    success = SatRidePalette.NightPrimary,
    warning = SatRidePalette.NightPrimary,
    danger = SatRidePalette.NightPrimary,
    hairline = SatRidePalette.NightTextSecondary.copy(alpha = 0.2f),
    sunshine = SatRidePalette.NightPrimary,
    coral = SatRidePalette.NightPrimary,
    lavender = SatRidePalette.NightTextSecondary,
    mint = SatRidePalette.NightTextSecondary,
    skyBlue = SatRidePalette.NightSurface,
    shadow = SatRidePalette.NightBase,
)
