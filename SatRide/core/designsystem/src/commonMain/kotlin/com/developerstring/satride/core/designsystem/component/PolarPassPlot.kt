package com.developerstring.satride.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * One sample of a pass, in the only terms a polar plot needs.
 *
 * Deliberately not the domain's `TimedTopo`: the design system stays free of domain types, which
 * is what lets this composable be rendered from a test, a preview or a screen that has no data
 * layer behind it.
 */
data class SkyPoint(val azimuthDeg: Float, val elevationDeg: Float)

/**
 * How a polar plot is drawn.
 *
 * Two sizes rather than one that scales, because they are genuinely different drawings. At 56 dp
 * the compass letters and the elevation labels are illegible smudges that only add noise, so the
 * thumbnail drops them and thickens its arc — the shape of the arc is the entire message at that
 * size.
 */
enum class PolarPlotStyle {
    /** 56 dp: shape only, no text. Used in the timeline's pass cards. */
    THUMBNAIL,

    /** 240 dp and up: full compass ring, elevation rings, labelled markers. */
    FULL,
    ;

    internal val labelled: Boolean get() = this == FULL
}

/** Default sizes named once, so the timeline and the detail screen cannot drift apart. */
object PolarPlotDefaults {
    val thumbnailSize: Dp = 56.dp
    val fullSize: Dp = 240.dp
}

/**
 * Maps a bearing and an elevation onto the unit disc of a polar plot.
 *
 * Zenith is the centre and the horizon is the rim, which is the convention every satellite
 * tracker and every ham's paper plot uses. North is up and east is right — the plot is read as a
 * map held overhead, not as a compass rose seen from outside.
 *
 * The radius is linear in elevation, not in its cosine. A stereographic projection would be
 * geometrically prettier, but it crowds every low pass into the outer millimetres of the disc,
 * and low passes are exactly the ones whose shape a user is trying to judge.
 *
 * Kept separate from the drawing so it can be checked by arithmetic rather than by eye.
 */
object PolarProjection {

    /**
     * Position on the unit disc: (0, 0) is the zenith, and a length of 1 is the horizon.
     *
     * X grows east, Y grows north — a mathematical frame, not a screen one. The flip to screen
     * coordinates happens once, in [toOffset], so nothing else has to remember it.
     */
    fun project(azimuthDeg: Float, elevationDeg: Float): Pair<Float, Float> {
        // Below the horizon is clamped rather than dropped: a pass arc that starts fractionally
        // negative should meet the rim, not vanish and leave a gap at AOS.
        val elevation = elevationDeg.coerceIn(0f, 90f)
        val radius = (90f - elevation) / 90f
        val theta = azimuthDeg * PI.toFloat() / 180f
        return (radius * sin(theta)) to (radius * cos(theta))
    }

    /** The same point in a draw scope's pixels, given a centre and the radius of the horizon. */
    fun toOffset(azimuthDeg: Float, elevationDeg: Float, center: Offset, radiusPx: Float): Offset {
        val (east, north) = project(azimuthDeg, elevationDeg)
        return Offset(center.x + east * radiusPx, center.y - north * radiusPx)
    }
}

/**
 * A pass drawn the way a tracker draws one: the arc the satellite traces across the sky bowl.
 *
 * The arc carries the whole story at a glance — a pass straight overhead is a line through the
 * middle, a scraping one hugs the rim, and which way it travels is obvious from where the AOS and
 * LOS markers sit. That is why the timeline shows a 56 dp version of it in every card rather than
 * only a maximum-elevation number.
 *
 * @param arc the sampled pass, in order. Fewer than two points draws the rings only.
 * @param live where the satellite is right now, when it is in this pass. Null otherwise.
 */
@Composable
fun PolarPassPlot(
    arc: List<SkyPoint>,
    modifier: Modifier = Modifier,
    style: PolarPlotStyle = PolarPlotStyle.FULL,
    live: SkyPoint? = null,
    accent: Color = SatRideTheme.accents.tracking,
    size: Dp = if (style == PolarPlotStyle.FULL) PolarPlotDefaults.fullSize else PolarPlotDefaults.thumbnailSize,
) {
    val ringColor = SatRideTheme.accents.hairline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    // Measured once per style rather than per frame: the compass letters never change, and a
    // timeline of pass cards would otherwise re-measure four strings per card per recomposition.
    val textMeasurer: TextMeasurer? = if (style.labelled) rememberTextMeasurer() else null

    val markers = remember(arc) { PassMarkers.from(arc) }

    Canvas(modifier.size(size)) {
        drawPolarPass(
            arc = arc,
            markers = markers,
            style = style,
            live = live,
            accent = accent,
            ringColor = ringColor,
            labelColor = labelColor,
            labelStyle = labelStyle,
            textMeasurer = textMeasurer,
        )
    }
}

/**
 * The three points of a pass that are worth naming.
 *
 * TCA is found by scanning for the highest sample rather than taken as the middle one. A pass arc
 * is sampled at a fixed cadence, so its midpoint is only the culmination when AOS and LOS happen
 * to be symmetric about it — which they are not for a pass clipped by the search window.
 */
internal data class PassMarkers(val aos: SkyPoint?, val tca: SkyPoint?, val los: SkyPoint?) {
    companion object {
        val EMPTY = PassMarkers(null, null, null)

        fun from(arc: List<SkyPoint>): PassMarkers {
            if (arc.isEmpty()) return EMPTY
            return PassMarkers(
                aos = arc.first(),
                tca = arc.maxBy { it.elevationDeg },
                los = arc.last(),
            )
        }
    }
}

@Suppress("LongParameterList")
internal fun DrawScope.drawPolarPass(
    arc: List<SkyPoint>,
    markers: PassMarkers,
    style: PolarPlotStyle,
    live: SkyPoint?,
    accent: Color,
    ringColor: Color,
    labelColor: Color,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer?,
) {
    // Room for the compass letters outside the horizon ring. The thumbnail has none, so it uses
    // the full width and the arc stays as large as it can be.
    val inset = if (style.labelled) size.minDimension * LABEL_INSET_FRACTION else 0f
    val radius = (min(size.width, size.height) - inset * 2f) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val hairline = size.minDimension * RING_STROKE_FRACTION

    drawCircle(color = accent.copy(alpha = 0.05f), radius = radius, center = center)

    // Horizon, then 30° and 60°. Three rings is the most a 56 dp thumbnail can carry without
    // turning into a grey disc, and the same three are what a paper polar plot has always used.
    ELEVATION_RINGS.forEach { elevationDeg ->
        val (_, north) = PolarProjection.project(0f, elevationDeg)
        drawCircle(
            // The rings are tinted with the accent rather than left on the theme's hairline. At
            // 10% white on a translucent panel the inner two were invisible, which turns a scale
            // into a plain disc and takes the "how high is high" reading away entirely.
            color = if (elevationDeg == 0f) accent.copy(alpha = 0.45f) else accent.copy(alpha = 0.22f),
            radius = radius * north,
            center = center,
            style = Stroke(width = hairline),
        )
    }

    if (style.labelled) {
        // Cardinal ticks, drawn inward from the rim so they read as a compass rose rather than
        // as spokes cutting through the arc.
        COMPASS_POINTS.forEach { (label, azimuth) ->
            val outer = PolarProjection.toOffset(azimuth, 0f, center, radius)
            val inner = PolarProjection.toOffset(azimuth, TICK_ELEVATION_DEG, center, radius)
            drawLine(color = ringColor, start = inner, end = outer, strokeWidth = hairline)

            if (textMeasurer != null) {
                val measured = textMeasurer.measure(label, labelStyle)
                val anchor = PolarProjection.toOffset(azimuth, -LABEL_ELEVATION_DEG, center, radius)
                drawText(
                    textLayoutResult = measured,
                    color = labelColor,
                    topLeft = Offset(
                        anchor.x - measured.size.width / 2f,
                        anchor.y - measured.size.height / 2f,
                    ),
                )
            }
        }
    }

    if (arc.size >= 2) {
        val path = Path()
        arc.forEachIndexed { index, point ->
            val offset = PolarProjection.toOffset(point.azimuthDeg, point.elevationDeg, center, radius)
            if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
        }
        drawPath(
            path = path,
            color = accent,
            style = Stroke(width = size.minDimension * ARC_STROKE_FRACTION.forStyle(style)),
        )
    }

    val markerRadius = size.minDimension * MARKER_FRACTION.forStyle(style)

    // AOS hollow, LOS filled: the pass is read as beginning at the open circle and ending at the
    // solid one, which says the direction of travel without needing an arrowhead that would be
    // invisible at thumbnail size.
    markers.aos?.let {
        val offset = PolarProjection.toOffset(it.azimuthDeg, it.elevationDeg, center, radius)
        drawCircle(
            color = accent,
            radius = markerRadius,
            center = offset,
            style = Stroke(width = hairline * 1.4f),
        )
    }
    markers.los?.let {
        val offset = PolarProjection.toOffset(it.azimuthDeg, it.elevationDeg, center, radius)
        drawCircle(color = accent, radius = markerRadius, center = offset)
    }
    markers.tca?.let {
        val offset = PolarProjection.toOffset(it.azimuthDeg, it.elevationDeg, center, radius)
        drawCircle(color = accent.copy(alpha = 0.35f), radius = markerRadius * 1.9f, center = offset)
        if (style.labelled && textMeasurer != null) {
            val measured = textMeasurer.measure("${it.elevationDeg.toInt()}°", labelStyle)
            // Clear of the culmination glow, which is markerRadius * 1.9 across — a label sitting
            // on top of it is unreadable at exactly the moment it matters most.
            translate(offset.x - measured.size.width / 2f, offset.y - measured.size.height - markerRadius * 4.5f) {
                drawText(textLayoutResult = measured, color = labelColor)
            }
        }
    }

    live?.let {
        // Only while the satellite is actually up. Drawing the live dot on the rim for something
        // that has already set would claim a pass is in progress when it is over.
        if (it.elevationDeg < 0f) return@let
        val offset = PolarProjection.toOffset(it.azimuthDeg, it.elevationDeg, center, radius)
        drawCircle(color = accent.copy(alpha = 0.25f), radius = markerRadius * 3f, center = offset)
        drawCircle(color = accent, radius = markerRadius * 1.4f, center = offset)
    }
}

private fun Pair<Float, Float>.forStyle(style: PolarPlotStyle): Float =
    if (style == PolarPlotStyle.FULL) first else second

/** Full, thumbnail. The thumbnail's strokes are proportionally heavier so the shape survives. */
private val ARC_STROKE_FRACTION = 0.010f to 0.055f
private val MARKER_FRACTION = 0.014f to 0.055f

private const val RING_STROKE_FRACTION = 0.006f
private const val LABEL_INSET_FRACTION = 0.09f
private const val TICK_ELEVATION_DEG = 8f

/** Negative elevation: the labels sit just *outside* the horizon ring, in the inset reserved. */
private const val LABEL_ELEVATION_DEG = 11f

private val ELEVATION_RINGS = floatArrayOf(0f, 30f, 60f)

private val COMPASS_POINTS = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
