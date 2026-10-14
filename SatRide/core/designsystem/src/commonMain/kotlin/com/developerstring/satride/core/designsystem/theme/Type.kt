package com.developerstring.satride.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The two bundled families that carry the illustrated look: **Baloo 2** (round, chunky,
 * game-like) for display/headline/title/label, **Nunito** for body text. Both OFL; licenses in
 * docs/design/.
 */
internal class SatRideFontFamilies(val heading: FontFamily, val body: FontFamily)

/**
 * Platform seam for font loading. Desktop and iOS load through Compose Resources; Android reads
 * the same TTFs from APK assets, because Compose Resources does not package `commonMain`
 * resources for the Android target under the AGP KMP plugin (the same gap the catalog snapshot
 * works around — see androidApp/build.gradle.kts).
 */
@Composable
internal expect fun satRideFontFamilies(): SatRideFontFamilies

/**
 * Heavy, friendly hierarchy for the illustrated restyle. Composable because resource fonts
 * resolve through composition; [SatRideTheme] builds this once and hands it to MaterialTheme.
 */
@Composable
internal fun rememberSatRideTypography(): Typography {
    val fonts = satRideFontFamilies()
    val heading = fonts.heading
    val body = fonts.body

    return Typography(
        displayLarge = TextStyle(
            fontFamily = heading,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 44.sp,
            lineHeight = 52.sp,
        ),
        displayMedium = TextStyle(
            fontFamily = heading,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 34.sp,
            lineHeight = 42.sp,
        ),
        headlineLarge = TextStyle(
            fontFamily = heading,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            lineHeight = 36.sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = heading,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 30.sp,
        ),
        titleLarge = TextStyle(
            fontFamily = heading,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            lineHeight = 26.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = heading,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        ),
        bodyLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(
            fontFamily = heading,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.2.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = heading,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            letterSpacing = 0.8.sp,
        ),
    )
}

/**
 * Styles for live numerics (frequencies, az/el, countdowns).
 *
 * Monospace is not decorative here: proportional digits change width as they tick, which makes
 * a 1 Hz readout visibly jitter. Every changing number in SatRide uses one of these.
 */
object NumericTextStyles {
    val large = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    )
    val medium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    )
    val small = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    )
}
