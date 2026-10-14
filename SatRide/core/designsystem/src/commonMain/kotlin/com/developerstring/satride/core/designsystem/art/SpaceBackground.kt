package com.developerstring.satride.core.designsystem.art

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * The indigo space stage every illustrated scene stands on: a vertical gradient with a field of
 * twinkling stars. Content composes over it.
 */
@Composable
fun SpaceBackground(
    modifier: Modifier = Modifier,
    /** Tint mixed into the lower half; pages shift mood by passing their scene accent. */
    tint: Color = Color.Unspecified,
    starCount: Int = 90,
    content: @Composable () -> Unit = {},
) {
    val base = MaterialTheme.colorScheme.background
    val shadow = SatRideTheme.accents.shadow
    val bottom = if (tint == Color.Unspecified) base else lerp(base, tint, 0.18f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(shadow, base, bottom))),
    ) {
        StarField(starCount = starCount, modifier = Modifier.fillMaxSize())
        content()
    }
}

/**
 * Twinkling stars. Deterministic layout (seeded LCG, no `Random`) so the field is stable across
 * recompositions and snapshot tests; only the twinkle phase animates.
 *
 * [twinkle] false freezes the field entirely: scenes captured as a glass backdrop layer want a
 * static star canvas, because any animation invalidates the captured layer every frame and
 * forces every glass shader downstream to re-run.
 */
@Composable
fun StarField(modifier: Modifier = Modifier, starCount: Int = 90, seed: Int = 42, twinkle: Boolean = true) {
    val twinklePhase = if (twinkle) rememberSceneCycle(periodMillis = 3600, label = "twinkle") else 0.35f
    val starColor = SatRideTheme.accents.mint
    val white = Color.White

    Canvas(modifier) {
        var state = seed
        fun next(): Float {
            state = state * 1103515245 + 12345
            return ((state ushr 16) and 0x7FFF) / 32767f
        }

        repeat(starCount) { index ->
            val x = next() * size.width
            val y = next() * size.height
            val radius = 0.8f + next() * 1.8f
            val base = 0.25f + next() * 0.55f
            val phase = next()
            val alpha = base * (0.55f + 0.45f * sin((twinklePhase + phase) * 2f * PI.toFloat()))
            val color = if (index % 7 == 0) starColor else white

            if (index % 11 == 0) {
                // Sparkle star: a small four-point cross.
                val arm = radius * 3f
                drawLine(color.copy(alpha = alpha), Offset(x - arm, y), Offset(x + arm, y), strokeWidth = radius * 0.7f)
                drawLine(color.copy(alpha = alpha), Offset(x, y - arm), Offset(x, y + arm), strokeWidth = radius * 0.7f)
            } else {
                drawCircle(color.copy(alpha = alpha), radius = radius, center = Offset(x, y))
            }
        }
    }
}

/**
 * The scalloped cloud bank from the reference frames: two overlapping rows of circles hugging
 * the bottom edge. Draw inside any Canvas; heights are fractions of the draw size.
 */
fun DrawScope.drawCloudBank(front: Color, back: Color) {
    val w = size.width
    val h = size.height

    // Back row: smaller, lighter puffs peeking over the front row.
    val backRadius = h * 0.42f
    var x = -backRadius * 0.2f
    var i = 0
    while (x < w + backRadius) {
        val r = backRadius * (0.75f + 0.35f * pseudo(i, 3))
        drawCircle(back, radius = r, center = Offset(x, h * 0.72f))
        x += r * 1.15f
        i++
    }

    // Front row: bigger puffs anchored below the edge so only their crowns show.
    val frontRadius = h * 0.62f
    x = -frontRadius * 0.4f
    i = 100
    while (x < w + frontRadius) {
        val r = frontRadius * (0.7f + 0.4f * pseudo(i, 5))
        drawCircle(front, radius = r, center = Offset(x, h * 1.05f))
        x += r * 1.05f
        i++
    }
}

/** Cheap deterministic jitter for hand-drawn-feeling rows. */
private fun pseudo(index: Int, salt: Int): Float {
    val v = (index * 73_856_093) xor (salt * 19_349_663)
    return ((v ushr 8) and 0xFFFF) / 65535f
}
