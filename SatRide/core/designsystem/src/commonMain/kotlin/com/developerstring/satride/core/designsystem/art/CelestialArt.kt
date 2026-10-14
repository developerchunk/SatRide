package com.developerstring.satride.core.designsystem.art

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import kotlin.math.cos
import kotlin.math.sin

/*
 * Flat-vector celestial bodies. Every prop is 2–4 solid fills of one hue — depth from palette
 * steps, never gradients — per docs/design/kurzgesagt-style.md.
 *
 * Each body exists twice: a `DrawScope.drawKurz*` primitive taking explicit colours (so it can
 * be baked into an [androidx.compose.ui.graphics.ImageBitmap] — see PlanetBitmaps.kt — or
 * embedded in other scenes), and a thin themed composable wrapper.
 */

/** A ringed toy planet. */
fun DrawScope.drawKurzPlanet(body: Color, ring: Color, shadow: Color) {
    val r = size.minDimension * 0.30f
    val c = Offset(size.width / 2f, size.height / 2f)
    val bodyPath = Path().apply { addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }

    // Back half of the ring, behind the body.
    rotate(degrees = -18f, pivot = c) {
        drawOval(
            color = ring,
            topLeft = Offset(c.x - r * 1.7f, c.y - r * 0.42f),
            size = Size(r * 3.4f, r * 0.84f),
            style = Stroke(width = r * 0.22f),
        )
    }

    drawCircle(body, radius = r, center = c)
    clipPath(bodyPath) {
        // Terminator: a darker circle pushed off-centre reads as the shadowed side.
        drawCircle(lerp(body, shadow, 0.45f), radius = r, center = Offset(c.x + r * 0.45f, c.y + r * 0.28f))
        // Craters on the lit side.
        val crater = lerp(body, shadow, 0.25f)
        drawCircle(crater, radius = r * 0.16f, center = Offset(c.x - r * 0.35f, c.y - r * 0.30f))
        drawCircle(crater, radius = r * 0.10f, center = Offset(c.x - r * 0.05f, c.y + r * 0.15f))
        drawCircle(crater, radius = r * 0.08f, center = Offset(c.x - r * 0.55f, c.y + r * 0.28f))
    }
    // Front half of the ring passes over the lower body.
    rotate(degrees = -18f, pivot = c) {
        val arcRect = Rect(c.x - r * 1.7f, c.y - r * 0.42f, c.x + r * 1.7f, c.y + r * 0.42f)
        drawArc(
            color = ring,
            startAngle = 10f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = arcRect.topLeft,
            size = arcRect.size,
            style = Stroke(width = r * 0.22f),
        )
    }
}

@Composable
fun KurzPlanet(
    modifier: Modifier = Modifier,
    body: Color = SatRideTheme.accents.lavender,
    ring: Color = SatRideTheme.accents.sunshine,
) {
    val shadow = SatRideTheme.accents.shadow
    Canvas(modifier) { drawKurzPlanet(body, ring, shadow) }
}

/** The reference-frame moon: light disc, tonal craters, dark right-side terminator. */
fun DrawScope.drawKurzMoon(lit: Color, shadow: Color) {
    val r = size.minDimension * 0.45f
    val c = Offset(size.width / 2f, size.height / 2f)
    val disc = Path().apply { addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }

    drawCircle(lit, radius = r, center = c)
    clipPath(disc) {
        val crater = lerp(lit, shadow, 0.22f)
        drawCircle(crater, radius = r * 0.20f, center = Offset(c.x - r * 0.30f, c.y + r * 0.35f))
        drawCircle(crater, radius = r * 0.12f, center = Offset(c.x - r * 0.55f, c.y - r * 0.15f))
        drawCircle(crater, radius = r * 0.09f, center = Offset(c.x - r * 0.10f, c.y - r * 0.45f))
        drawCircle(crater, radius = r * 0.07f, center = Offset(c.x + r * 0.05f, c.y + r * 0.05f))
        // Night side: flat dark lune over the right half.
        drawCircle(lerp(lit, shadow, 0.82f), radius = r, center = Offset(c.x + r * 0.85f, c.y))
    }
}

@Composable
fun KurzMoon(modifier: Modifier = Modifier, lit: Color = Color(0xFFB9C4E8)) {
    val shadow = SatRideTheme.accents.shadow
    Canvas(modifier) { drawKurzMoon(lit, shadow) }
}

/**
 * A friendly flat Earth, seen with Africa facing you.
 *
 * Delegates to [drawSpinningKurzEarth] at a fixed rotation rather than keeping a second, simpler
 * drawing of the same planet. It used to scatter five discs across the ocean, which is what a
 * planet looks like only if you have never seen one; more to the point, the two Earths in the app
 * drifted the moment one of them grew coastlines, and the onboarding scenes and the splash are
 * meant to be showing the same world.
 *
 * The 20° offset puts Africa and Europe square in the middle, which is the most legible face the
 * globe has — and the one people recognise fastest.
 */
fun DrawScope.drawKurzEarth(ocean: Color, land: Color, glow: Color, shadow: Color) {
    drawSpinningKurzEarth(ocean = ocean, land = land, glow = glow, shadow = shadow, turns = -20f / 360f)
}

@Composable
fun KurzEarth(modifier: Modifier = Modifier) {
    val a = SatRideTheme.accents
    Canvas(modifier) { drawKurzEarth(a.skyBlue, a.mint, a.tracking, a.shadow) }
}

/**
 * A continent, as a closed coastline in real globe coordinates: longitude east, latitude north,
 * both in degrees.
 *
 * Outlines rather than discs. Circles the size of a continent read as bubbles floating on the
 * ocean however carefully they are arranged — there is no coastline in a circle, and a coastline
 * is the only thing that makes a blue disc read as the Earth. These are drawn as smoothed closed
 * curves through their vertices, so a dozen points per landmass is enough to give every one of
 * them capes, gulfs and a taper.
 *
 * The shapes are recognisable rather than accurate: this is the same flat-vector language as the
 * rest of the art, and a projection-accurate coastline would be both unreadable at 160dp and out
 * of keeping with everything around it.
 */
private class Landmass(val outline: List<Pair<Float, Float>>)

// Every outline is kept under about 85° of longitude, and the real continents that are wider
// than that are cut into neighbouring pieces which share a border and read as one mass.
//
// This is a hard constraint, not tidiness. Vertices past the limb are clamped onto it (see
// [drawSpinningKurzEarth]); for a shape narrower than a hemisphere the clamped stretch hugs the
// edge and looks like a coast rounding the horizon. For one that is wider, more than half of it
// collapses onto the limb at once and the fill jumps straight across the middle of the globe —
// a single Eurasia outline spanning 169° drew a flat slab over the whole top of the planet.
private val EarthLandmasses = listOf(
    // Africa: bulge in the north-west, taper to a southern cape.
    Landmass(
        listOf(
            -18f to 34f, 0f to 36f, 14f to 33f, 32f to 31f, 43f to 12f, 51f to 12f,
            42f to -2f, 40f to -16f, 33f to -26f, 25f to -34f, 18f to -34f, 12f to -23f,
            9f to -5f, 0f to 5f, -8f to 4f, -16f to 12f, -17f to 22f,
        ),
    ),
    // Europe, meeting western Asia at 40°E.
    Landmass(
        listOf(
            -10f to 36f, -2f to 43f, 3f to 51f, 0f to 58f, 10f to 64f, 24f to 70f,
            40f to 68f, 44f to 58f, 40f to 48f, 32f to 44f, 22f to 40f, 10f to 38f, 0f to 37f,
        ),
    ),
    // Western and central Asia, meeting the eastern half at 98°E.
    Landmass(
        listOf(
            40f to 68f, 60f to 71f, 82f to 73f, 98f to 70f, 102f to 58f, 98f to 46f,
            90f to 34f, 78f to 28f, 70f to 36f, 62f to 44f, 50f to 42f, 44f to 52f, 40f to 60f,
        ),
    ),
    // Eastern Asia, down to the south-east peninsulas.
    Landmass(
        listOf(
            98f to 70f, 118f to 72f, 138f to 68f, 150f to 60f, 142f to 52f, 130f to 42f,
            122f to 31f, 112f to 22f, 102f to 20f, 96f to 30f, 98f to 46f, 100f to 58f,
        ),
    ),
    // North America, cut down the middle at 112°W.
    Landmass(
        listOf(
            -168f to 64f, -150f to 69f, -130f to 71f, -112f to 72f, -102f to 68f, -100f to 55f,
            -104f to 45f, -112f to 38f, -120f to 34f, -126f to 42f, -134f to 54f, -152f to 58f,
        ),
    ),
    Landmass(
        listOf(
            -112f to 72f, -92f to 73f, -74f to 70f, -58f to 60f, -56f to 47f, -70f to 42f,
            -80f to 32f, -88f to 28f, -97f to 26f, -102f to 38f, -106f to 52f, -110f to 64f,
        ),
    ),
    // The isthmus down to the southern continent.
    Landmass(
        listOf(
            -100f to 20f,
            -92f to 17f,
            -84f to 10f,
            -77f to 8f,
            -82f to 15f,
            -90f to 21f,
            -97f to 25f,
        ),
    ),
    // South America: wide in the north, drawn out to a point in the south.
    Landmass(
        listOf(
            -80f to 8f, -66f to 11f, -52f to 5f, -35f to -6f, -38f to -18f, -48f to -28f,
            -56f to -35f, -62f to -46f, -70f to -54f, -74f to -46f, -72f to -32f,
            -71f to -18f, -78f to -6f, -80f to 2f,
        ),
    ),
    // Australia, and the islands north of it, so the Pacific face is never empty.
    Landmass(
        listOf(
            114f to -22f, 122f to -17f, 132f to -12f, 142f to -11f, 148f to -20f,
            150f to -28f, 145f to -38f, 135f to -35f, 124f to -34f, 115f to -34f,
        ),
    ),
    Landmass(
        listOf(
            95f to 6f,
            104f to 2f,
            115f to -2f,
            118f to -8f,
            108f to -8f,
            98f to -2f,
        ),
    ),
    // Greenland, which keeps the north pole busy.
    Landmass(
        listOf(
            -45f to 60f,
            -30f to 68f,
            -22f to 76f,
            -32f to 82f,
            -50f to 82f,
            -58f to 75f,
            -54f to 66f,
        ),
    ),
)

/**
 * The Earth, turning.
 *
 * Not [drawKurzEarth] under a rotation: spinning the whole disc turns the polar cap, the
 * atmosphere and the terminator with it, which reads as a picture of a planet being twirled
 * rather than as a planet. Here only the land moves: every coastline vertex is projected
 * orthographically from its own longitude and latitude, so a continent narrows as it approaches
 * the limb and rolls out of sight round the back — which is what actually sells the rotation, and
 * what a sprite sliding sideways can never do.
 *
 * [turns] is in whole revolutions; only its fractional part matters.
 */
fun DrawScope.drawSpinningKurzEarth(ocean: Color, land: Color, glow: Color, shadow: Color, turns: Float) {
    val r = size.minDimension * 0.42f
    val c = Offset(size.width / 2f, size.height / 2f)
    val disc = Path().apply { addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }

    drawCircle(glow.copy(alpha = 0.18f), radius = r * 1.12f, center = c)
    drawCircle(ocean, radius = r, center = c)

    clipPath(disc) {
        val spin = turns * 360f
        EarthLandmasses.forEach { mass ->
            // Longitudes relative to the meridian facing the viewer: "in front" is |lon| < 90.
            val lons = mass.outline.map { (lon, _) -> ((lon + spin) % 360f + 540f) % 360f - 180f }
            // Faded by how much of the outline is still on the near side, and dropped once most
            // of it has gone: clamping the stragglers onto the limb keeps a departing coast
            // intact, but applied to one that is almost entirely behind the globe it flattens
            // the whole shape onto the edge as a thread lying along it.
            val facing = lons.count { cos(it * PI_F / 180f) > 0f }.toFloat() / lons.size
            if (facing < 0.45f) return@forEach

            val points = mass.outline.mapIndexed { index, (_, latDeg) ->
                // Vertices past the limb are pulled onto it rather than dropped. Dropping them
                // cuts the coastline off with a straight chord across the middle of the disc;
                // clamping lets the shape hug the edge and slide away round it.
                val lon = lons[index].coerceIn(-90f, 90f) * PI_F / 180f
                val lat = latDeg * PI_F / 180f
                Offset(
                    c.x + r * cos(lat) * sin(lon),
                    c.y - r * sin(lat),
                )
            }

            // Fades over the last of its journey to the limb, so a continent dissolves as it
            // rounds the edge rather than blinking out at the cut-off.
            drawPath(smoothClosedPath(points), land.copy(alpha = ((facing - 0.45f) / 0.25f).coerceIn(0.35f, 1f)))
        }

        // Cap and terminator are properties of the viewer and the sun, not of the surface, so
        // they are drawn after the land and never turn with it.
        drawCircle(
            Color.White.copy(alpha = 0.9f),
            radius = r * 0.20f,
            center = Offset(c.x + r * 0.92f, c.y - r * 0.92f),
        )

        // Night, as a gradient rather than as an offset disc.
        //
        // The static [drawKurzEarth] shades with a second circle nudged off-centre, which is
        // fine there: it falls over flat blobs and gives them their only modelling. Here it was
        // wrong in a way that only showed up once the planet turned — a circle of the same
        // radius offset by a fraction of it crosses the disc on a nearly straight chord, so the
        // shading arrived as a hard line across the globe with a flat, brighter band above it.
        // It read as a rectangle laid over the top third of the screen, which is exactly what it
        // was. A gradient has no edge to notice.
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, shadow.copy(alpha = 0.34f)),
                start = Offset(c.x - r * 0.55f, c.y - r * 0.85f),
                end = Offset(c.x + r * 0.95f, c.y + r * 0.95f),
            ),
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2f, r * 2f),
        )
    }

    // A crescent of atmosphere on the lit edge, drawn over everything so land passing under it
    // still picks up the rim light.
    drawCircle(
        color = glow.copy(alpha = 0.30f),
        radius = r,
        center = c,
        style = Stroke(width = r * 0.035f),
    )
}

/**
 * A closed curve through [points], rounded at every vertex.
 *
 * Each segment is a quadratic whose control point is the vertex itself and whose ends are the
 * midpoints of the two segments meeting there. Joining the vertices with straight lines instead
 * gives a coastline made of visible facets, which at these sizes looks like a mistake rather than
 * like a style.
 */
private fun smoothClosedPath(points: List<Offset>): Path {
    val path = Path()
    if (points.size < 3) return path

    fun midpoint(a: Offset, b: Offset) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    var start = midpoint(points.last(), points.first())
    path.moveTo(start.x, start.y)
    for (index in points.indices) {
        val control = points[index]
        val end = midpoint(control, points[(index + 1) % points.size])
        path.quadraticTo(control.x, control.y, end.x, end.y)
        start = end
    }
    path.close()
    return path
}

@Composable
fun SpinningKurzEarth(turns: () -> Float, modifier: Modifier = Modifier) {
    val a = SatRideTheme.accents
    // `turns` is read inside the draw lambda, so a new frame redraws without recomposing.
    Canvas(modifier) { drawSpinningKurzEarth(a.skyBlue, a.mint, a.tracking, a.shadow, turns()) }
}

private const val PI_F = 3.1415927f

/** A banded gas giant with a storm spot — warm bands on a sunny body. */
fun DrawScope.drawKurzGasGiant(body: Color, band: Color, shadow: Color) {
    val r = size.minDimension * 0.44f
    val c = Offset(size.width / 2f, size.height / 2f)
    val disc = Path().apply { addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }

    drawCircle(body, radius = r, center = c)
    clipPath(disc) {
        // Horizontal bands, slightly tilted so the planet reads as leaning into its spin.
        rotate(degrees = -8f, pivot = c) {
            fun bandAt(yFrac: Float, hFrac: Float, alpha: Float) = drawRoundRect(
                color = band.copy(alpha = alpha),
                topLeft = Offset(c.x - r * 1.4f, c.y + r * yFrac - r * hFrac / 2f),
                size = Size(r * 2.8f, r * hFrac),
                cornerRadius = CornerRadius(r * hFrac / 2f),
            )
            bandAt(-0.55f, 0.22f, 0.55f)
            bandAt(-0.12f, 0.30f, 0.85f)
            bandAt(0.30f, 0.18f, 0.60f)
            bandAt(0.62f, 0.24f, 0.45f)
            // The storm spot, sitting proud of its band.
            drawCircle(lerp(band, shadow, 0.25f), radius = r * 0.16f, center = Offset(c.x + r * 0.38f, c.y + r * 0.28f))
            drawCircle(band.copy(alpha = 0.85f), radius = r * 0.11f, center = Offset(c.x + r * 0.38f, c.y + r * 0.28f))
        }
        // Terminator.
        drawCircle(shadow.copy(alpha = 0.35f), radius = r, center = Offset(c.x + r * 0.55f, c.y + r * 0.30f))
    }
}

@Composable
fun KurzGasGiant(
    modifier: Modifier = Modifier,
    body: Color = SatRideTheme.accents.sunshine,
    band: Color = SatRideTheme.accents.coral,
) {
    val shadow = SatRideTheme.accents.shadow
    Canvas(modifier) { drawKurzGasGiant(body, band, shadow) }
}

/** Concentric expanding wave arcs — the RF motif. [progress] is a 0→1 cycle. */
@Composable
fun RadioWaves(
    modifier: Modifier = Modifier,
    color: Color = SatRideTheme.accents.radio,
    progress: Float,
    waves: Int = 3,
) {
    Canvas(modifier) {
        drawRadioWaves(color, progress, waves)
    }
}

/** Same waves as a DrawScope primitive so machines can embed them. */
fun DrawScope.drawRadioWaves(color: Color, progress: Float, waves: Int = 3) {
    val maxR = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    repeat(waves) { i ->
        val t = (progress + i / waves.toFloat()) % 1f
        val r = maxR * (0.25f + 0.75f * t)
        val alpha = (1f - t) * 0.8f
        drawArc(
            color = color.copy(alpha = alpha),
            startAngle = -68f,
            sweepAngle = 136f,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = maxR * 0.07f, cap = StrokeCap.Round),
        )
    }
}
