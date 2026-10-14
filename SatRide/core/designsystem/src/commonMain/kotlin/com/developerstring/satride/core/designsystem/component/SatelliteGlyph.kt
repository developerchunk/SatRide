package com.developerstring.satride.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The shapes SatRide draws satellites as.
 *
 * Deliberately abstract rather than photorealistic: at glyph size a rendered spacecraft is an
 * illegible smudge, while a silhouette that differs in *outline* is readable at 16 dp and again at
 * a few pixels in the sky view. Each one is distinguishable by shape alone, so the class survives
 * being drawn in a single accent colour — or in red night mode, where colour carries nothing.
 *
 * Takes its own enum rather than the domain's `SatClass`, so the design system stays free of
 * domain types and the same glyphs can serve the AR layer in P5.
 */
enum class SatelliteGlyphKind {
    /** A crewed station: a body with two solar wings. */
    CREWED,

    /** A weather satellite: a body with a dish. */
    WEATHER,

    /** An amateur satellite: a body with a whip antenna. */
    AMATEUR,

    /** A CubeSat: a plain square, which is what one actually is. */
    CUBESAT,

    /** Navigation: a body in a ring. */
    NAVIGATION,

    /** A spent stage: a bare cylinder, deliberately the dullest shape here. */
    ROCKET_BODY,

    /** Anything unclassified. */
    OTHER,
}

/**
 * Draws a satellite class glyph.
 *
 * Thin-stroke line art matching the rest of the icon set; the stroke scales with the requested
 * size so it stays even at any dimension.
 */
@Composable
fun SatelliteGlyph(kind: SatelliteGlyphKind, tint: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier.size(size)) {
        val stroke = Stroke(width = this.size.minDimension * STROKE_FRACTION)
        when (kind) {
            SatelliteGlyphKind.CREWED -> drawCrewed(tint, stroke)
            SatelliteGlyphKind.WEATHER -> drawWeather(tint, stroke)
            SatelliteGlyphKind.AMATEUR -> drawAmateur(tint, stroke)
            SatelliteGlyphKind.CUBESAT -> drawCubeSat(tint, stroke)
            SatelliteGlyphKind.NAVIGATION -> drawNavigation(tint, stroke)
            SatelliteGlyphKind.ROCKET_BODY -> drawRocketBody(tint, stroke)
            SatelliteGlyphKind.OTHER -> drawOther(tint, stroke)
        }
    }
}

private const val STROKE_FRACTION = 0.09f

/** Body plus two wings — the silhouette everyone recognises as a station. */
private fun DrawScope.drawCrewed(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawRect(
        color = tint,
        topLeft = Offset(w * 0.4f, h * 0.34f),
        size = Size(w * 0.2f, h * 0.32f),
        style = stroke,
    )
    drawRect(
        color = tint,
        topLeft = Offset(w * 0.06f, h * 0.4f),
        size = Size(w * 0.24f, h * 0.2f),
        style = stroke,
    )
    drawRect(
        color = tint,
        topLeft = Offset(w * 0.7f, h * 0.4f),
        size = Size(w * 0.24f, h * 0.2f),
        style = stroke,
    )
}

/** Body with a dish pointed down, the way an imager looks at the Earth. */
private fun DrawScope.drawWeather(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawRect(
        color = tint,
        topLeft = Offset(w * 0.34f, h * 0.16f),
        size = Size(w * 0.32f, h * 0.3f),
        style = stroke,
    )
    drawArc(
        color = tint,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(w * 0.22f, h * 0.44f),
        size = Size(w * 0.56f, h * 0.44f),
        style = stroke,
    )
    drawLine(
        color = tint,
        start = Offset(w * 0.5f, h * 0.46f),
        end = Offset(w * 0.5f, h * 0.66f),
        strokeWidth = stroke.width,
    )
}

/** Body with a whip antenna — the amateur satellite's defining feature. */
private fun DrawScope.drawAmateur(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawRect(
        color = tint,
        topLeft = Offset(w * 0.3f, h * 0.46f),
        size = Size(w * 0.4f, h * 0.34f),
        style = stroke,
    )
    drawLine(
        color = tint,
        start = Offset(w * 0.5f, h * 0.46f),
        end = Offset(w * 0.5f, h * 0.12f),
        strokeWidth = stroke.width,
    )
    drawLine(
        color = tint,
        start = Offset(w * 0.34f, h * 0.24f),
        end = Offset(w * 0.66f, h * 0.24f),
        strokeWidth = stroke.width,
    )
}

/** A CubeSat is literally a cube, so the glyph is a square with its diagonal brace. */
private fun DrawScope.drawCubeSat(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawRect(
        color = tint,
        topLeft = Offset(w * 0.26f, h * 0.26f),
        size = Size(w * 0.48f, h * 0.48f),
        style = stroke,
    )
    drawLine(
        color = tint,
        start = Offset(w * 0.26f, h * 0.5f),
        end = Offset(w * 0.74f, h * 0.5f),
        strokeWidth = stroke.width,
    )
}

/** Body inside a ring: a constellation member holding a fixed slot. */
private fun DrawScope.drawNavigation(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawCircle(color = tint, radius = w * 0.36f, center = center, style = stroke)
    drawRect(
        color = tint,
        topLeft = Offset(w * 0.42f, h * 0.42f),
        size = Size(w * 0.16f, h * 0.16f),
        style = stroke,
    )
}

/** A bare cylinder — the dullest shape in the set, which is the point. */
private fun DrawScope.drawRocketBody(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawRect(
        color = tint,
        topLeft = Offset(w * 0.38f, h * 0.2f),
        size = Size(w * 0.24f, h * 0.6f),
        style = stroke,
    )
}

private fun DrawScope.drawOther(tint: Color, stroke: Stroke) {
    drawCircle(color = tint, radius = size.width * 0.24f, center = center, style = stroke)
}
