package com.developerstring.satride.feature.arsky.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.orbit.math.Quaternion
import com.developerstring.satride.feature.arsky.SkySatelliteUi
import com.developerstring.satride.feature.arsky.projection.CameraFov
import com.developerstring.satride.feature.arsky.projection.ProjectedPoint
import kotlin.math.sin

/**
 * The sky layer: everything drawn over the camera, on one `Canvas`.
 *
 * ## Why one canvas and not composables
 *
 * A composable per satellite would be the idiomatic thing and is the wrong thing here. Fifty
 * satellites moving every frame means fifty layout passes per frame, and Compose's layout is not
 * built to run at thirty hertz on fifty independently-positioned nodes. Drawing is: the whole sky
 * is one draw pass into one canvas, and moving a glyph costs a couple of floats.
 *
 * ## What makes it thirty hertz
 *
 * The frame clock writes to a single `Long` state that is read *inside the draw lambda*. Compose
 * invalidates only the draw phase for a state read there, so a frame skips composition and layout
 * entirely. Reading it outside would recompose this function thirty times a second and undo the
 * whole design.
 */
@Composable
internal fun SkySurface(
    satellites: List<SkySatelliteUi>,
    positions: SkyPositionSource,
    /**
     * Read *inside* the draw phase, not passed as a value.
     *
     * Attitude arrives from the sensors at about fifty hertz. As a plain parameter that would
     * recompose this function fifty times a second — worse than the thirty-hertz redraw it is
     * meant to feed. A lambda invoked in the draw block defers the state read to the draw phase,
     * so a new orientation invalidates drawing and nothing else.
     */
    attitude: () -> Quaternion,
    fov: () -> CameraFov,
    palette: SkyPalette,
    renderState: SkyRenderState,
    selectedId: Int?,
    guideTargetId: Int?,
    selectionProgress: Float,
    onTapSatellite: (Int) -> Unit,
    onTapEmpty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labels = remember { LabelCache() }

    // Declutter state is keyed to the tracked set, not the frame: a satellite that has set
    // should forget its label state, and everything else should keep it.
    LaunchedEffect(satellites) {
        val ids = IntArray(satellites.size) { satellites[it].noradId }
        renderState.declutter.retainOnly(ids, ids.size)
    }

    var frameNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var lastEmitted = 0L
        while (true) {
            withFrameNanos { now ->
                // Thirty hertz is a deliberate cap, not a limitation. LEO angular motion is about
                // a degree a second, so ninety frames a second would draw the same picture three
                // times — and an AR session is measured in tens of minutes on battery.
                if (now - lastEmitted >= FRAME_INTERVAL_NANOS) {
                    lastEmitted = now
                    frameNanos = now
                }
            }
        }
    }

    val glyphRadius = with(density) { GLYPH_RADIUS.toPx() }
    val strokeWidth = with(density) { STROKE.toPx() }
    val labelOffset = with(density) { LABEL_OFFSET.toPx() }
    val tickLength = with(density) { TICK_LENGTH.toPx() }
    val tapRadius = with(density) { TAP_RADIUS.toPx() }
    val arrowRadius = with(density) { ARROW_RADIUS.toPx() }
    val arrowArm = with(density) { ARROW_ARM.toPx() }

    Canvas(
        modifier = modifier.pointerInput(renderState, tapRadius) {
            detectTapGestures { offset ->
                val hit = renderState.hitTest(offset.x, offset.y, tapRadius)
                if (hit != null) onTapSatellite(hit) else onTapEmpty()
            }
        },
    ) {
        // The frame clock, read here so only the draw phase is invalidated.
        val now = frameNanos

        renderState.projector.widthPx = size.width
        renderState.projector.heightPx = size.height
        renderState.projector.fov = fov()
        renderState.projector.attitude = attitude()
        renderState.beginFrame()
        positions.beginFrame()

        val projector = renderState.projector
        val dim = if (selectedId != null) SELECTION_DIM else 1f

        drawHorizon(projector, palette.horizon.copy(alpha = palette.horizon.alpha * dim), strokeWidth)
        drawCardinalTicks(
            projector,
            palette.horizon.copy(alpha = palette.horizon.alpha * dim),
            strokeWidth,
            tickLength,
        )

        // Trails first, so glyphs and labels sit on top of them.
        for (index in satellites.indices) {
            val satellite = satellites[index]
            val trail = satellite.trail ?: continue
            val selected = satellite.noradId == selectedId
            if (selectedId != null && !selected) continue

            drawTrail(
                projector = projector,
                azimuths = trail.azimuthDeg,
                elevations = trail.elevationDeg,
                nowIndex = trail.nowIndex,
                color = if (selected) palette.selectedTrail else palette.trail,
                strokeWidth = strokeWidth,
                // The selected satellite's arc draws itself in rather than appearing: the
                // animation is what makes a tap feel like it did something.
                maxAlpha = if (selected) TRAIL_ALPHA * selectionProgress else TRAIL_ALPHA * dim,
            )
        }

        // Glyphs, then labels, in descending label priority so the greedy declutter is offered
        // the satellites that matter first. Sorting per frame would allocate, so the list arrives
        // already ordered from the view model.
        for (index in satellites.indices) {
            val satellite = satellites[index]
            // Recorded for every satellite, fix or no fix, so this frame's positions stay
            // index-aligned with the list the label pass walks — and so one read per satellite
            // per frame serves both passes.
            if (!positions.readInto(satellite.noradId, renderState.angles)) {
                renderState.record(satellite.noradId, ProjectedPoint.CULLED, elevationDeg = -90.0)
                continue
            }

            val azimuth = renderState.angles[0]
            val elevation = renderState.angles[1]
            val point = projector.project(azimuth, elevation)
            renderState.record(satellite.noradId, point, elevation)
            if (!point.isVisible) continue

            val below = elevation <= 0.0
            val selected = satellite.noradId == selectedId
            val guided = satellite.noradId == guideTargetId

            // A satellite below the horizon is behind the Earth, so it is drawn as a ghost rather
            // than hidden: seeing where something is about to rise is most of the value of
            // pointing a phone at the ground.
            var alpha = if (below) GHOST_ALPHA else 1f
            if (selectedId != null && !selected) alpha *= SELECTION_DIM
            // A selected satellite is drawn at full strength wherever it is. Leaving a ghost at
            // its usual fifth of an alpha would make the one thing the user just tapped the
            // faintest thing on a screen that has dimmed everything else to look at it.
            if (selected || guided) alpha = 1f

            val colour = when {
                guided -> palette.guide
                satellite.hasDownlink -> palette.radio
                else -> palette.tracking
            }.copy(alpha = alpha)

            if (satellite.hasDownlink && !below) {
                // A slow pulse, the same phase for every transmitting satellite, so the sky
                // breathes in unison instead of shimmering.
                val pulse = 0.5f + 0.5f * sin(now / PULSE_PERIOD_NANOS.toFloat() * TWO_PI)
                drawGlyphHalo(
                    point.x,
                    point.y,
                    glyphRadius * (1f + pulse * 0.35f),
                    palette.radio.copy(alpha = alpha),
                )
            } else if (!below) {
                // A sunlit satellite is one you could genuinely pick out overhead with the naked
                // eye, which is a different proposition from one that is merely up there in the
                // dark. It gets a wider glow to say so — the state was already computed by the
                // orbit engine and carried this far without being drawn.
                val glow = if (satellite.sunlit) glyphRadius * SUNLIT_HALO_SCALE else glyphRadius
                drawGlyphHalo(point.x, point.y, glow, colour)
            }

            drawSkyGlyph(
                kind = satellite.glyph,
                centerX = point.x,
                centerY = point.y,
                radius = glyphRadius,
                color = colour,
                strokeWidth = strokeWidth,
            )

            if (selected) {
                drawCircle(
                    palette.selection,
                    glyphRadius * 3f,
                    Offset(point.x, point.y),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(strokeWidth),
                )
            }
        }

        // Labels last: they must sit above every glyph, and the declutter needs the whole frame's
        // geometry before it can decide which of them fit.
        for (index in 0 until renderState.count) {
            val satellite = satellites[index]
            if (!renderState.isProjectedAt(index)) continue
            val x = renderState.xAt(index)
            val y = renderState.yAt(index)
            if (x < 0f || x > size.width || y < 0f || y > size.height) continue

            val below = !renderState.isAboveHorizonAt(index)
            val selected = satellite.noradId == selectedId
            if (selectedId != null && !selected) continue

            val text = labels.textFor(satellite)
            val layout = labels.layoutFor(satellite.noradId, text, measurer, palette.labelStyle)

            val clamped = labelLeft(
                glyphX = x,
                offset = labelOffset,
                labelWidth = layout.size.width.toFloat(),
                viewportWidth = size.width,
            )
            val top = y - layout.size.height / 2f

            val fits = renderState.declutter.offer(
                id = satellite.noradId,
                left = clamped,
                top = top,
                right = clamped + layout.size.width,
                bottom = top + layout.size.height,
                nowNanos = now,
            )
            // The selected satellite's name is always drawn: the user asked about that one, and
            // decluttering it away in favour of a neighbour would be perverse.
            if (!fits && !selected) continue

            drawText(
                textLayoutResult = layout,
                topLeft = Offset(clamped, top),
                alpha = if (below) GHOST_ALPHA else 1f,
            )
        }

        // The guide arrow, drawn only when its target cannot be seen — an arrow pointing at
        // something already on screen is noise.
        val target = guideTargetId?.let { id -> satellites.firstOrNull { it.noradId == id } }
        if (target != null && positions.readInto(target.noradId, renderState.angles)) {
            val point = projector.project(renderState.angles[0], renderState.angles[1])
            if (!projector.isOnScreen(point, marginPx = -arrowRadius * 0.2f)) {
                drawGuideArrow(
                    bearingDeg = projector.screenAngleTo(renderState.angles[0], renderState.angles[1]),
                    color = palette.guide,
                    radius = arrowRadius,
                    armLength = arrowArm,
                    strokeWidth = strokeWidth * 2f,
                )
            }
        }
    }
}

/**
 * Measured text, kept between frames.
 *
 * Measuring text is one of the more expensive things a draw pass can do and it allocates, so a
 * name is measured once and a countdown only when its text actually changes — once a second at
 * most, rather than thirty times.
 */
private class LabelCache {
    private val layouts = mutableMapOf<Int, Entry>()

    fun textFor(satellite: SkySatelliteUi): String {
        val rises = satellite.risesIn ?: return satellite.name
        if (rises.isNegative()) return satellite.name
        val minutes = rises.inWholeMinutes
        return if (minutes >= 1) {
            "${satellite.name}  ↑${minutes}m"
        } else {
            "${satellite.name}  ↑${rises.inWholeSeconds}s"
        }
    }

    fun layoutFor(id: Int, text: String, measurer: TextMeasurer, style: TextStyle): TextLayoutResult {
        val existing = layouts[id]
        if (existing != null && existing.text == text) return existing.layout
        val layout = measurer.measure(text, style)
        // One slot per satellite, replaced rather than added to. Keying the cache on the text
        // itself would grow it by an entry a second for every satellite counting down.
        layouts[id] = Entry(text, layout)
        return layout
    }

    private class Entry(val text: String, val layout: TextLayoutResult)
}

/**
 * Where a satellite's name starts, horizontally.
 *
 * Labels sit to the right of their glyph and flip to the left when that would run them off the
 * edge. Without the flip, every satellite in the right-hand margin shows a truncated name — and
 * the right-hand margin is exactly where a satellite sits at the moment the user is turning
 * towards it.
 *
 * If neither side fits, the label is clamped into view rather than dropped: half a name still
 * reads, a missing one does not.
 *
 * Extracted from the draw loop so it can be tested. Inside the lambda it was geometry no test
 * could reach, which is how it shipped wrong in the first place.
 */
internal fun labelLeft(glyphX: Float, offset: Float, labelWidth: Float, viewportWidth: Float): Float {
    val toTheRight = glyphX + offset
    val left = if (toTheRight + labelWidth > viewportWidth) glyphX - offset - labelWidth else toTheRight
    return left.coerceIn(0f, (viewportWidth - labelWidth).coerceAtLeast(0f))
}

/** Colours and text style for the sky layer, resolved from the theme once per composition. */
internal data class SkyPalette(
    val tracking: Color,
    val radio: Color,
    val guide: Color,
    val horizon: Color,
    val trail: Color,
    val selectedTrail: Color,
    val selection: Color,
    val labelStyle: TextStyle,
)

/** 30 Hz. */
private const val FRAME_INTERVAL_NANOS = 33_333_333L

private const val PULSE_PERIOD_NANOS = 1_800_000_000L
private const val TWO_PI = 6.2831855f

private const val GHOST_ALPHA = 0.2f
private const val SELECTION_DIM = 0.4f
private const val TRAIL_ALPHA = 0.55f

/** How much wider the halo is on a satellite that is in sunlight and so actually visible. */
private const val SUNLIT_HALO_SCALE = 1.45f

private val GLYPH_RADIUS = 5.dp
private val STROKE = 1.5.dp
private val LABEL_OFFSET = 10.dp
private val TICK_LENGTH = 8.dp
private val ARROW_RADIUS = 96.dp
private val ARROW_ARM = 14.dp

/**
 * How close a tap must be to a glyph to select it.
 *
 * The phase file says 24 dp. That is smaller than a fingertip, which is why the nearest glyph
 * wins rather than the first one found.
 */
private val TAP_RADIUS = 24.dp
