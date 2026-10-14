package com.developerstring.satride.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.art.KurzEarth
import com.developerstring.satride.core.designsystem.art.KurzGroundStation
import com.developerstring.satride.core.designsystem.art.KurzMoon
import com.developerstring.satride.core.designsystem.art.KurzPlanet
import com.developerstring.satride.core.designsystem.art.KurzRocket
import com.developerstring.satride.core.designsystem.art.KurzSatellite
import com.developerstring.satride.core.designsystem.art.RadioWaves
import com.developerstring.satride.core.designsystem.art.drawCloudBank
import com.developerstring.satride.core.designsystem.art.floating
import com.developerstring.satride.core.designsystem.art.rememberSceneCycle
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The five illustrated scenes of onboarding. Each fills its page and loops its own gentle
 * animation; the pager provides entrance motion.
 */

/** Earth with a satellite circling it on a dashed orbit. */
@Composable
internal fun WelcomeScene(modifier: Modifier = Modifier) {
    val orbit = rememberSceneCycle(periodMillis = 9000, label = "welcomeOrbit")
    val blink = rememberSceneCycle(periodMillis = 1600, label = "welcomeBlink")
    val orbitColor = SatRideTheme.accents.tracking

    Box(modifier, contentAlignment = Alignment.Center) {
        KurzEarth(Modifier.size(220.dp))
        // Dashed orbit ellipse.
        Canvas(Modifier.size(300.dp)) {
            drawOval(
                color = orbitColor.copy(alpha = 0.5f),
                topLeft = Offset(0f, size.height * 0.30f),
                size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.40f),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f)),
                ),
            )
        }
        KurzSatellite(
            blink = blink,
            modifier = Modifier
                .size(72.dp)
                .offset {
                    val angle = orbit * 2f * PI.toFloat()
                    IntOffset(
                        (cos(angle) * 150.dp.toPx()).roundToInt(),
                        (sin(angle) * 60.dp.toPx()).roundToInt(),
                    )
                },
        )
    }
}

/** A satellite caught in cyan target brackets — the AR promise. */
@Composable
internal fun SkyScene(modifier: Modifier = Modifier) {
    val blink = rememberSceneCycle(periodMillis = 1400, label = "skyBlink")
    val pulse = rememberSceneCycle(periodMillis = 2200, label = "skyPulse")
    val accent = SatRideTheme.accents.tracking

    Box(modifier, contentAlignment = Alignment.Center) {
        KurzPlanet(
            Modifier
                .size(110.dp)
                .offset(x = (-110).dp, y = (-120).dp)
                .floating(amplitude = 5.dp, phase = 0.4f),
        )
        KurzSatellite(
            blink = blink,
            modifier = Modifier
                .size(150.dp)
                .floating(amplitude = 9.dp),
        )
        // Target brackets breathing around the satellite.
        Canvas(Modifier.size(230.dp)) {
            val grow = 1f + 0.05f * sin(pulse * 2f * PI.toFloat())
            val half = size.minDimension / 2f * 0.88f * grow
            val c = Offset(size.width / 2f, size.height / 2f)
            val arm = half * 0.36f
            val w = 4.dp.toPx()
            listOf(
                Offset(-1f, -1f),
                Offset(1f, -1f),
                Offset(-1f, 1f),
                Offset(1f, 1f),
            ).forEach { corner ->
                val cx = c.x + corner.x * half
                val cy = c.y + corner.y * half
                drawLine(
                    accent,
                    Offset(cx, cy),
                    Offset(cx - corner.x * arm, cy),
                    strokeWidth = w,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    accent,
                    Offset(cx, cy),
                    Offset(cx, cy - corner.y * arm),
                    strokeWidth = w,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** Ground station calling up to a passing moon — pass predictions. */
@Composable
internal fun PassesScene(modifier: Modifier = Modifier) {
    val waves = rememberSceneCycle(periodMillis = 2600, label = "passWaves")

    Box(modifier, contentAlignment = Alignment.Center) {
        KurzMoon(
            Modifier
                .size(110.dp)
                .offset(x = 100.dp, y = (-130).dp)
                .floating(amplitude = 4.dp, phase = 0.7f),
        )
        KurzGroundStation(
            waves = waves,
            modifier = Modifier
                .size(240.dp)
                .offset(y = 40.dp),
        )
    }
}

/** Amber waves everywhere — the RF layer. */
@Composable
internal fun RadioScene(modifier: Modifier = Modifier) {
    val waves = rememberSceneCycle(periodMillis = 2400, label = "radioWaves")
    val blink = rememberSceneCycle(periodMillis = 1800, label = "radioBlink")

    Box(modifier, contentAlignment = Alignment.Center) {
        RadioWaves(
            progress = waves,
            modifier = Modifier.size(280.dp),
        )
        KurzSatellite(
            blink = blink,
            modifier = Modifier
                .size(130.dp)
                .floating(amplitude = 7.dp),
        )
    }
}

/** The rocket on the pad, flame roaring, clouds below. */
@Composable
internal fun LaunchScene(modifier: Modifier = Modifier) {
    val flame = rememberSceneCycle(periodMillis = 900, label = "launchFlame")
    val mint = SatRideTheme.accents.mint
    val skyBlue = SatRideTheme.accents.skyBlue

    Box(modifier, contentAlignment = Alignment.Center) {
        KurzRocket(
            flame = 0.5f + 0.5f * flame,
            modifier = Modifier
                .size(220.dp)
                .floating(amplitude = 5.dp, periodMillis = 1400),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(90.dp)
                .align(Alignment.BottomCenter),
        ) {
            drawCloudBank(front = skyBlue.copy(alpha = 0.85f), back = mint.copy(alpha = 0.55f))
        }
    }
}
