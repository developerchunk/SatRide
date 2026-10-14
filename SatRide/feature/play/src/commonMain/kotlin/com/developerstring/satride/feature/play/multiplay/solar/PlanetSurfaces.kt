package com.developerstring.satride.feature.play.multiplay.solar

import androidx.compose.ui.graphics.Color
import com.developerstring.satride.feature.play.solar.Body
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Flat-vector surfaces for the planets: a colour for every latitude and longitude.
 *
 * Nothing here is a texture. Each world is a few bands, a spot or two and a polar cap, chosen so
 * the planet is recognisable from its caricature — Jupiter's stripes and red spot, Mars's rust
 * and white caps, Neptune's dark storm — in the same illustrated register as the vector Earth.
 * Evaluated per vertex of a ~1,400-vertex sphere, so everything is a couple of sines.
 */
internal object PlanetSurfaces {

    fun color(body: Body, latDeg: Double, lonDeg: Double): Color = when (body) {
        Body.MERCURY -> {
            val v = sin(latDeg * 0.09 + 1.3) * sin(lonDeg * 0.07) + 0.5 * sin(lonDeg * 0.19 + latDeg * 0.11)
            if (v > 0.55) {
                Mercury.scale(0.82f)
            } else if (v < -0.7) {
                Mercury.scale(1.08f)
            } else {
                Mercury
            }
        }

        Body.VENUS -> {
            val swirl = sin(latDeg * 0.12 + 0.6 * sin(lonDeg * 0.05) + lonDeg * 0.01)
            Venus.scale(0.95f + 0.05f * swirl.toFloat())
        }

        Body.EARTH -> Earth

        Body.MOON -> {
            val v = sin(latDeg * 0.08 + 0.4) * cos(lonDeg * 0.05 - 0.8) + 0.6 * sin(lonDeg * 0.13 + latDeg * 0.2)
            if (v > 0.5 && latDeg > -30.0) MoonMaria else Moon
        }

        Body.MARS -> when {
            latDeg > 79.0 || latDeg < -83.0 -> Cap

            else -> {
                val v = sin(lonDeg * 0.06 + latDeg * 0.04) * cos(latDeg * 0.09) + 0.5 * sin(lonDeg * 0.17)
                if (v > 0.45) {
                    MarsDark
                } else if (v < -0.75) {
                    MarsLight
                } else {
                    Mars
                }
            }
        }

        Body.JUPITER -> {
            // The Great Red Spot sits just south of the equator.
            val dl = wrap(lonDeg - 20.0)
            val spot = (dl / 14.0) * (dl / 14.0) + ((latDeg + 22.0) / 7.0) * ((latDeg + 22.0) / 7.0)
            if (spot < 1.0) {
                RedSpot
            } else {
                val wobble = 1.6 * sin(lonDeg * 0.1 + latDeg * 0.05)
                val band = floor((latDeg + wobble + 90.0) / 12.0).toInt()
                when (((band % 3) + 3) % 3) {
                    0 -> JupiterTan
                    1 -> JupiterBrown
                    else -> JupiterCream
                }
            }
        }

        Body.SATURN -> when {
            abs(latDeg) > 75.0 -> SaturnPole

            else -> {
                val band = floor((latDeg + 90.0 + 0.8 * sin(lonDeg * 0.08)) / 14.0).toInt()
                when (((band % 3) + 3) % 3) {
                    0 -> Saturn
                    1 -> SaturnDark
                    else -> SaturnLight
                }
            }
        }

        Body.URANUS -> Uranus.scale(1f + 0.03f * sin(latDeg * 0.15).toFloat())

        Body.NEPTUNE -> {
            val dl = wrap(lonDeg - 60.0)
            val spot = (dl / 16.0) * (dl / 16.0) + ((latDeg + 20.0) / 9.0) * ((latDeg + 20.0) / 9.0)
            when {
                spot < 1.0 -> NeptuneSpot
                latDeg in -42.0..-30.0 || latDeg in 22.0..31.0 -> NeptuneDark
                latDeg in 33.0..36.0 && sin(lonDeg * 0.09) > 0.3 -> NeptuneCloud
                else -> Neptune
            }
        }

        Body.PLUTO -> {
            val dl = wrap(lonDeg - 180.0)
            val heart = (dl / 38.0) * (dl / 38.0) + ((latDeg - 15.0) / 24.0) * ((latDeg - 15.0) / 24.0)
            val v = sin(lonDeg * 0.05 + 1.0) * cos(latDeg * 0.08)
            when {
                heart < 1.0 -> PlutoHeart
                v > 0.4 -> PlutoDark
                else -> Pluto
            }
        }

        Body.SUN -> SunCore
    }

    private fun wrap(deg: Double): Double {
        var d = deg % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    private fun Color.scale(k: Float): Color =
        Color((red * k).coerceIn(0f, 1f), (green * k).coerceIn(0f, 1f), (blue * k).coerceIn(0f, 1f), alpha)

    private val Mercury = Color(0xFFB9AFA3)
    private val Venus = Color(0xFFF3D9A4)
    private val Earth = Color(0xFF2E77E0)
    private val Moon = Color(0xFFC9CDD6)
    private val MoonMaria = Color(0xFF8D93A3)
    private val Mars = Color(0xFFE0714A)
    private val MarsDark = Color(0xFFB4513A)
    private val MarsLight = Color(0xFFF0946E)
    private val Cap = Color(0xFFF6F2EA)
    private val JupiterTan = Color(0xFFE3B98A)
    private val JupiterBrown = Color(0xFFC98A5E)
    private val JupiterCream = Color(0xFFF4E3C8)
    private val RedSpot = Color(0xFFD96A4F)
    private val Saturn = Color(0xFFF0D9A6)
    private val SaturnDark = Color(0xFFE0C08A)
    private val SaturnLight = Color(0xFFF7E8C9)
    private val SaturnPole = Color(0xFFD9D3C0)
    private val Uranus = Color(0xFF9FE3EA)
    private val Neptune = Color(0xFF3F6AE0)
    private val NeptuneDark = Color(0xFF2F55C4)
    private val NeptuneSpot = Color(0xFF1E3A8A)
    private val NeptuneCloud = Color(0xFFCFE0FF)
    private val Pluto = Color(0xFFD9B99B)
    private val PlutoDark = Color(0xFF8B5E3C)
    private val PlutoHeart = Color(0xFFF3E6D6)
    private val SunCore = Color(0xFFFFF3C4)

    /** Saturn's rings, inner and outer radii in km with a flat colour and opacity each. */
    class RingBand(val innerKm: Double, val outerKm: Double, val color: Color, val alpha: Float)

    val saturnRings: List<RingBand> = listOf(
        RingBand(74_500.0, 92_000.0, Color(0xFFBFAE8E), 0.42f),
        RingBand(92_000.0, 117_500.0, Color(0xFFEADFC4), 0.92f),
        RingBand(122_200.0, 136_800.0, Color(0xFFDCCFAE), 0.78f),
        RingBand(139_400.0, 140_600.0, Color(0xFFF2EAD6), 0.35f),
    )
}
