package com.developerstring.satride.feature.arsky.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.developerstring.satride.core.designsystem.component.SatelliteGlyphKind
import com.developerstring.satride.feature.arsky.projection.ProjectedPoint
import com.developerstring.satride.feature.arsky.projection.SkyProjector
import kotlin.math.abs

// The drawing primitives of the sky layer.
//
// Every function here is a `DrawScope` extension taking primitives, and none of them allocates:
// Compose's `Offset` and `Size` are inline value classes over a `Long`, `drawLine`/`drawCircle`
// take them by value, and nothing builds a list or formats a string. That is not fastidiousness —
// a thirty-hertz loop over fifty satellites turns a single per-glyph allocation into fifteen
// hundred objects a second, and P5.3 is accepted on a flat heap.

/**
 * A satellite, drawn small.
 *
 * These are not the catalog's silhouettes shrunk down. At list size a glyph is 18 dp and can
 * carry a recognisable outline; here it is nearer 5 dp of usable radius against a live camera
 * image, where that same outline collapses into a smudge. So each class keeps one distinguishing
 * mark and drops the rest — the family resemblance to `SatelliteGlyph` is intentional, the
 * fidelity is not.
 */
internal fun DrawScope.drawSkyGlyph(
    kind: SatelliteGlyphKind,
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color,
    strokeWidth: Float,
) {
    val center = Offset(centerX, centerY)
    when (kind) {
        SatelliteGlyphKind.CREWED -> {
            drawCircle(color, radius * 0.5f, center)
            // Solar wings: the one feature that reads at any size.
            drawLine(
                color,
                Offset(centerX - radius * 1.6f, centerY),
                Offset(centerX - radius * 0.7f, centerY),
                strokeWidth,
            )
            drawLine(
                color,
                Offset(centerX + radius * 0.7f, centerY),
                Offset(centerX + radius * 1.6f, centerY),
                strokeWidth,
            )
        }

        SatelliteGlyphKind.WEATHER -> {
            drawCircle(color, radius * 0.55f, center, style = Stroke(strokeWidth))
            drawArc(
                color = color,
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(centerX - radius, centerY - radius * 0.2f),
                size = Size(radius * 2f, radius * 1.6f),
                style = Stroke(strokeWidth),
            )
        }

        SatelliteGlyphKind.AMATEUR -> {
            drawCircle(color, radius * 0.5f, center)
            drawLine(
                color,
                Offset(centerX, centerY - radius * 0.6f),
                Offset(centerX, centerY - radius * 1.8f),
                strokeWidth,
            )
        }

        SatelliteGlyphKind.CUBESAT -> {
            drawRect(
                color = color,
                topLeft = Offset(centerX - radius * 0.7f, centerY - radius * 0.7f),
                size = Size(radius * 1.4f, radius * 1.4f),
                style = Stroke(strokeWidth),
            )
        }

        SatelliteGlyphKind.NAVIGATION -> {
            drawCircle(color, radius * 0.35f, center)
            drawCircle(color, radius, center, style = Stroke(strokeWidth))
        }

        SatelliteGlyphKind.ROCKET_BODY -> {
            drawLine(
                color,
                Offset(centerX, centerY - radius),
                Offset(centerX, centerY + radius),
                strokeWidth * 2f,
            )
        }

        SatelliteGlyphKind.OTHER -> drawCircle(color, radius * 0.55f, center)
    }
}

/**
 * The soft glow every glyph sits inside.
 *
 * The accuracy budget makes this necessary rather than decorative. Compass error dominates
 * everything else at one to five degrees, so a satellite is *somewhere around* where the glyph
 * is, not exactly under it. A hard point would promise a precision the sensors cannot deliver and
 * would read as a bug the moment the user looked closely; a halo says "about here", which is
 * true.
 */
internal fun DrawScope.drawGlyphHalo(centerX: Float, centerY: Float, radius: Float, color: Color) {
    drawCircle(color.copy(alpha = 0.10f), radius * 2.4f, Offset(centerX, centerY))
    drawCircle(color.copy(alpha = 0.18f), radius * 1.5f, Offset(centerX, centerY))
}

/**
 * The horizon, and the compass points along it.
 *
 * Sampled rather than drawn as a straight line: the horizon is a great circle, and through a
 * pinhole camera that is a straight line only when the phone is perfectly level. Tilt it and the
 * horizon curves across the frame, which is exactly the cue that tells the user the overlay is
 * locked to the world rather than painted on the screen.
 */
internal fun DrawScope.drawHorizon(projector: SkyProjector, color: Color, strokeWidth: Float) {
    var previous = ProjectedPoint.CULLED
    var azimuth = 0
    while (azimuth <= 360) {
        val point = projector.project(azimuth.toDouble(), 0.0)
        if (point.isVisible && previous.isVisible && !isWrapped(previous, point, size.width)) {
            drawLine(color, Offset(previous.x, previous.y), Offset(point.x, point.y), strokeWidth)
        }
        previous = point
        azimuth += HORIZON_STEP_DEG
    }
}

/** Tick marks at the eight compass points, longer for the four cardinals. */
internal fun DrawScope.drawCardinalTicks(
    projector: SkyProjector,
    color: Color,
    strokeWidth: Float,
    tickLength: Float,
) {
    var index = 0
    while (index < 8) {
        val azimuth = index * 45.0
        val base = projector.project(azimuth, 0.0)
        if (base.isVisible) {
            val cardinal = index % 2 == 0
            val length = if (cardinal) tickLength else tickLength * 0.5f
            val top = projector.project(azimuth, 0.0)
            drawLine(
                color.copy(alpha = if (cardinal) 0.9f else 0.5f),
                Offset(top.x, top.y - length),
                Offset(base.x, base.y + length * 0.3f),
                strokeWidth,
            )
        }
        index++
    }
}

/**
 * A satellite's path across the sky, fading away from the present.
 *
 * Drawn segment by segment rather than as one `Path` so each piece can carry its own alpha. A
 * gradient brush would be one draw call instead of twenty, but it would need a new `Brush` object
 * per satellite per frame — the trade is a handful of extra draw calls against an allocation in
 * the hot loop, and the draw calls are cheaper.
 */
internal fun DrawScope.drawTrail(
    projector: SkyProjector,
    azimuths: FloatArray,
    elevations: FloatArray,
    nowIndex: Int,
    color: Color,
    strokeWidth: Float,
    maxAlpha: Float,
) {
    if (azimuths.size < 2) return

    var previous = projector.project(azimuths[0].toDouble(), elevations[0].toDouble())
    for (i in 1 until azimuths.size) {
        val point = projector.project(azimuths[i].toDouble(), elevations[i].toDouble())
        if (point.isVisible && previous.isVisible && !isWrapped(previous, point, size.width)) {
            // Distance from the present, normalised, so the arc is brightest at the satellite
            // and dissolves at both ends. A uniformly bright trail reads as a drawn line; a
            // fading one reads as motion.
            val distance = abs(i - nowIndex).toFloat() / azimuths.size
            val alpha = (maxAlpha * (1f - distance * 1.8f)).coerceAtLeast(0f)
            if (alpha > 0.01f) {
                drawLine(
                    color.copy(alpha = alpha),
                    Offset(previous.x, previous.y),
                    Offset(point.x, point.y),
                    strokeWidth,
                )
            }
        }
        previous = point
    }
}

/**
 * The arrow that points at an off-screen target.
 *
 * Drawn at the edge of a circle centred on the screen, rotated to [bearingDeg] — degrees
 * clockwise from up, which is what `SkyProjector.screenAngleTo` produces.
 */
internal fun DrawScope.drawGuideArrow(
    bearingDeg: Float,
    color: Color,
    radius: Float,
    armLength: Float,
    strokeWidth: Float,
) {
    val radians = bearingDeg * kotlin.math.PI.toFloat() / 180f
    // Screen y grows downwards, so the vertical component is negated to make 0° point up.
    val dirX = kotlin.math.sin(radians)
    val dirY = -kotlin.math.cos(radians)
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val tipX = centerX + dirX * radius
    val tipY = centerY + dirY * radius

    // Perpendicular, for the two barbs.
    val perpX = -dirY
    val perpY = dirX

    drawLine(
        color,
        Offset(tipX - dirX * armLength + perpX * armLength * 0.6f, tipY - dirY * armLength + perpY * armLength * 0.6f),
        Offset(tipX, tipY),
        strokeWidth,
    )
    drawLine(
        color,
        Offset(tipX - dirX * armLength - perpX * armLength * 0.6f, tipY - dirY * armLength - perpY * armLength * 0.6f),
        Offset(tipX, tipY),
        strokeWidth,
    )
}

/**
 * Whether two consecutive samples sit on opposite sides of the projection.
 *
 * A pinhole projection sends directions more than ninety degrees apart to opposite sides of the
 * screen, so a polyline crossing behind the camera would be drawn as a stroke straight across the
 * view. Both endpoints project validly, so culling by visibility alone does not catch it — the
 * only signal is that the jump is implausibly large for one step of the sample.
 */
private fun isWrapped(from: ProjectedPoint, to: ProjectedPoint, width: Float): Boolean =
    abs(to.x - from.x) > width * WRAP_FRACTION

private const val HORIZON_STEP_DEG = 3
private const val WRAP_FRACTION = 0.5f
