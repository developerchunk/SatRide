package com.developerstring.satride.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * SatRide's icon set, drawn rather than imported.
 *
 * Compose Multiplatform stopped publishing `material-icons-core` after 1.7.x, and generic
 * Material glyphs would not match the thin-stroke language anyway. These are built from
 * primitives so they stay crisp at any size and can be tinted per domain accent.
 */
enum class SatRideIcon {
    Sky,
    Satellites,
    Play,
    Passes,
    Radio,
    Profile,
    Settings,
    Back,
    Location,
    Chevron,

    /** The AR camera, on. */
    Camera,

    /** The AR camera, off — the same body with a stroke through it. */
    CameraOff,
}

@Composable
fun SatRideIconGraphic(
    icon: SatRideIcon,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
) {
    Canvas(modifier.size(size)) {
        val stroke = Stroke(width = this.size.minDimension * 0.075f, cap = StrokeCap.Round)
        when (icon) {
            SatRideIcon.Sky -> drawSky(tint, stroke)
            SatRideIcon.Satellites -> drawSatellites(tint, stroke)
            SatRideIcon.Play -> drawPlay(tint, stroke)
            SatRideIcon.Passes -> drawPasses(tint, stroke)
            SatRideIcon.Profile -> drawProfile(tint, stroke)
            SatRideIcon.Radio -> drawRadio(tint, stroke)
            SatRideIcon.Settings -> drawSettings(tint, stroke)
            SatRideIcon.Back -> drawBack(tint, stroke)
            SatRideIcon.Location -> drawLocation(tint, stroke)
            SatRideIcon.Chevron -> drawChevron(tint, stroke)
            SatRideIcon.Camera -> drawCamera(tint, stroke, struck = false)
            SatRideIcon.CameraOff -> drawCamera(tint, stroke, struck = true)
        }
    }
}

/** A small constellation: what the sky view is for. */
private fun DrawScope.drawSky(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val a = Offset(w * 0.22f, h * 0.72f)
    val b = Offset(w * 0.46f, h * 0.34f)
    val c = Offset(w * 0.78f, h * 0.52f)
    drawLine(tint, a, b, stroke.width, StrokeCap.Round)
    drawLine(tint, b, c, stroke.width, StrokeCap.Round)
    listOf(a to 0.075f, b to 0.10f, c to 0.075f).forEach { (point, r) ->
        drawCircle(tint, radius = w * r, center = point)
    }
}

/** A list of objects. */
private fun DrawScope.drawSatellites(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    listOf(0.3f, 0.5f, 0.7f).forEach { fraction ->
        val y = h * fraction
        drawCircle(tint, radius = w * 0.055f, center = Offset(w * 0.24f, y))
        drawLine(tint, Offset(w * 0.42f, y), Offset(w * 0.78f, y), stroke.width, StrokeCap.Round)
    }
}

/** A planet with a satellite on its orbit: the globe game. */
private fun DrawScope.drawPlay(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.5f, h * 0.52f)
    drawCircle(tint, radius = w * 0.17f, center = center, style = stroke)
    rotate(degrees = -24f, pivot = center) {
        drawArc(
            color = tint,
            startAngle = 28f,
            sweepAngle = 305f,
            useCenter = false,
            topLeft = Offset(center.x - w * 0.36f, center.y - h * 0.17f),
            size = Size(w * 0.72f, h * 0.34f),
            style = stroke,
        )
        drawCircle(tint, radius = w * 0.06f, center = Offset(center.x + w * 0.36f, center.y))
    }
}

/** A pass: the arc a satellite traces from horizon to horizon. */
private fun DrawScope.drawPasses(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawArc(
        color = tint,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(w * 0.15f, h * 0.24f),
        size = Size(w * 0.7f, h * 0.62f),
        style = stroke,
    )
    drawLine(
        tint,
        Offset(w * 0.1f, h * 0.78f),
        Offset(w * 0.9f, h * 0.78f),
        stroke.width * 0.8f,
        StrokeCap.Round,
    )
    drawCircle(tint, radius = w * 0.085f, center = Offset(w * 0.5f, h * 0.28f))
}

/** Broadcast: a source and its expanding wavefronts. */
private fun DrawScope.drawRadio(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.28f, h * 0.5f)
    drawCircle(tint, radius = w * 0.085f, center = center)
    listOf(0.26f, 0.44f, 0.62f).forEach { radius ->
        drawArc(
            color = tint,
            startAngle = -55f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(center.x - w * radius, center.y - w * radius),
            size = Size(w * radius * 2, w * radius * 2),
            style = stroke,
        )
    }
}

/**
 * A head and shoulders, in the same thin line art as the rest of the set.
 *
 * Drawn as an arc rather than a filled silhouette so it reads at 24 dp against the dark theme, and
 * so it survives night mode where colour carries nothing and only outline does.
 */
private fun DrawScope.drawProfile(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawCircle(tint, radius = w * 0.19f, center = Offset(w * 0.5f, h * 0.34f), style = stroke)
    drawArc(
        color = tint,
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(w * 0.18f, h * 0.56f),
        size = Size(w * 0.64f, h * 0.62f),
        style = stroke,
    )
}

/** Sliders rather than a gear: this app's settings are mostly continuous values. */
private fun DrawScope.drawSettings(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    listOf(0.35f to 0.62f, 0.65f to 0.38f).forEach { (fraction, knobX) ->
        val y = h * fraction
        drawLine(tint, Offset(w * 0.16f, y), Offset(w * 0.84f, y), stroke.width, StrokeCap.Round)
        drawCircle(tint, radius = w * 0.1f, center = Offset(w * knobX, y), style = stroke)
    }
}

/**
 * A back arrow.
 *
 * Three strokes from one point, so the head stays attached to the shaft at any size — an arrow
 * drawn as a separate chevron drifts away from its line when the stroke width scales.
 */
private fun DrawScope.drawBack(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val start = Offset(w * 0.25f, h * 0.5f)
    drawLine(tint, start, Offset(w * 0.75f, h * 0.5f), stroke.width, StrokeCap.Round)
    drawLine(tint, start, Offset(w * 0.45f, h * 0.3f), stroke.width, StrokeCap.Round)
    drawLine(tint, start, Offset(w * 0.45f, h * 0.7f), stroke.width, StrokeCap.Round)
}

/**
 * A map pin: a teardrop with a hole punched in it.
 *
 * The hole is drawn as a stroked circle rather than a filled one in the background colour, so the
 * icon stays correct on the glass panels it sits on — those are translucent, and a "hole" painted
 * in an opaque colour would show as a solid dot over whatever is behind.
 */
private fun DrawScope.drawLocation(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.5f, h * 0.4f)
    val radius = w * 0.26f

    // The head: everything except the bottom wedge, which the point closes off.
    drawArc(
        color = tint,
        startAngle = 35f,
        sweepAngle = 290f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = stroke,
    )

    // The point, from where the arc's two ends stop down to the tip.
    val tip = Offset(w * 0.5f, h * 0.86f)
    val rad = (35.0 * kotlin.math.PI / 180.0).toFloat()
    val right = Offset(center.x + radius * kotlin.math.cos(rad), center.y + radius * kotlin.math.sin(rad))
    val left = Offset(center.x - radius * kotlin.math.cos(rad), center.y + radius * kotlin.math.sin(rad))
    drawLine(tint, right, tip, stroke.width, StrokeCap.Round)
    drawLine(tint, left, tip, stroke.width, StrokeCap.Round)

    drawCircle(tint, radius = w * 0.09f, center = center, style = stroke)
}

/**
 * A downward chevron, for a row that expands.
 *
 * Drawn rather than typed as "▾": the text glyph's size and baseline vary by platform font, so it
 * sat at a different height on Android and desktop and never quite lined up with the row.
 */
private fun DrawScope.drawChevron(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawLine(tint, Offset(w * 0.28f, h * 0.42f), Offset(w * 0.5f, h * 0.62f), stroke.width, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.72f, h * 0.42f), Offset(w * 0.5f, h * 0.62f), stroke.width, StrokeCap.Round)
}

/**
 * A camera body with a lens, optionally struck through.
 *
 * The two states are one drawing plus a diagonal rather than two unrelated glyphs, so "off" reads
 * as the same control in a different state — which is what it is — instead of as a different
 * control. The strike is drawn last so it sits over the lens.
 */
private fun DrawScope.drawCamera(tint: Color, stroke: Stroke, struck: Boolean) {
    val w = size.width
    val h = size.height

    // The viewfinder bump on the top edge, then the body.
    val bodyTop = h * 0.32f
    val bodyBottom = h * 0.78f
    val bodyLeft = w * 0.12f
    val bodyRight = w * 0.88f

    val body = Path().apply {
        moveTo(bodyLeft, bodyBottom)
        lineTo(bodyLeft, bodyTop)
        lineTo(w * 0.34f, bodyTop)
        lineTo(w * 0.42f, h * 0.20f)
        lineTo(w * 0.58f, h * 0.20f)
        lineTo(w * 0.66f, bodyTop)
        lineTo(bodyRight, bodyTop)
        lineTo(bodyRight, bodyBottom)
        close()
    }
    drawPath(body, tint, style = stroke)

    drawCircle(
        color = tint,
        radius = w * 0.16f,
        center = Offset(w * 0.5f, (bodyTop + bodyBottom) / 2f),
        style = stroke,
    )

    if (struck) {
        drawLine(
            color = tint,
            start = Offset(w * 0.16f, h * 0.86f),
            end = Offset(w * 0.84f, h * 0.14f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}
