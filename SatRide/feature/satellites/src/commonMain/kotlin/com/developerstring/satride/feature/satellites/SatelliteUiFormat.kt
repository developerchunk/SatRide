package com.developerstring.satride.feature.satellites

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.developerstring.satride.core.designsystem.component.SatelliteGlyphKind
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.model.PassQuality
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.common.asCompassPoint as commonCompassPoint
import com.developerstring.satride.core.common.asMegahertz as commonMegahertz
import com.developerstring.satride.core.common.format as commonFormat

/**
 * Presentation vocabulary shared by the catalog and the detail screen.
 *
 * Small and boring on purpose. The alternative — each screen labelling classes and colouring
 * badges its own way — is how two screens end up calling the same satellite different things.
 */

internal val SatClass.label: String
    get() = when (this) {
        SatClass.CREWED -> "Crewed"
        SatClass.WEATHER -> "Weather"
        SatClass.AMATEUR -> "Amateur"
        SatClass.CUBESAT -> "CubeSat"
        SatClass.NAVIGATION -> "Navigation"
        SatClass.ROCKET_BODY -> "Rocket body"
        SatClass.OTHER -> "Object"
    }

@Composable
internal fun SatClass.accent(): Color = when (this) {
    SatClass.CREWED -> SatRideTheme.accents.success
    SatClass.WEATHER -> SatRideTheme.accents.tracking
    SatClass.AMATEUR, SatClass.CUBESAT -> SatRideTheme.accents.radio
    SatClass.NAVIGATION -> SatRideTheme.accents.trackingDim
    SatClass.ROCKET_BODY, SatClass.OTHER -> SatRideTheme.accents.trackingDim
}

/** The glyph a class is drawn as, in lists and in the sky view alike. */
internal val SatClass.glyph: SatelliteGlyphKind
    get() = when (this) {
        SatClass.CREWED -> SatelliteGlyphKind.CREWED
        SatClass.WEATHER -> SatelliteGlyphKind.WEATHER
        SatClass.AMATEUR -> SatelliteGlyphKind.AMATEUR
        SatClass.CUBESAT -> SatelliteGlyphKind.CUBESAT
        SatClass.NAVIGATION -> SatelliteGlyphKind.NAVIGATION
        SatClass.ROCKET_BODY -> SatelliteGlyphKind.ROCKET_BODY
        SatClass.OTHER -> SatelliteGlyphKind.OTHER
    }

internal val PassQuality.label: String
    get() = when (this) {
        PassQuality.EXCELLENT -> "Excellent"
        PassQuality.GOOD -> "Good"
        PassQuality.MARGINAL -> "Low"
    }

@Composable
internal fun PassQuality.accent(): Color = when (this) {
    PassQuality.EXCELLENT -> SatRideTheme.accents.success
    PassQuality.GOOD -> SatRideTheme.accents.warning
    PassQuality.MARGINAL -> SatRideTheme.accents.trackingDim
}

/*
 * Number formatting moved to `core:common` in P6: a notification body has to say "max 78°" the
 * same way a catalog row does, and the alert scheduler cannot depend on a feature. Re-exported
 * here so the screens in this module keep their existing call sites.
 */
internal fun Double.format(decimals: Int): String = commonFormat(decimals)

internal fun Double.asCompassPoint(): String = commonCompassPoint()

internal fun Long.asMegahertz(): String = commonMegahertz()
