package com.developerstring.satride.feature.arsky.render

import com.developerstring.satride.feature.arsky.projection.ProjectedPoint
import com.developerstring.satride.feature.arsky.projection.SkyProjector

/**
 * Live positions, read at frame rate rather than pushed through the UI state.
 *
 * The two-rate design in one interface: the tracking engine propagates once a second, and this is
 * how the renderer asks where something is *right now*, thirty times a second, without a state
 * emission or an allocation per read.
 */
internal interface SkyPositionSource {

    /** Called once per frame, before any [readInto] calls, so the clock is read once. */
    fun beginFrame()

    /** Writes `[azimuthDeg, elevationDeg]` into [out]. False when the satellite has no fix yet. */
    fun readInto(noradId: Int, out: DoubleArray): Boolean
}

/**
 * Everything the frame loop needs to keep between frames.
 *
 * Exists because the sky view has a hard allocation budget: the acceptance criterion for P5.3 is
 * a flat heap during a steady-state AR session, and the only way to get one is for the per-frame
 * work to write into buffers that already exist. So the projector, the declutter state and the
 * screen positions of every glyph all live here, in `remember`, for the life of the screen.
 *
 * It also happens to be what tap handling needs: hit-testing asks where the glyphs were drawn,
 * which is exactly what the last frame left behind.
 *
 * Not thread-safe. Written by the frame loop, read by the tap handler, both on the main thread.
 */
internal class SkyRenderState {

    val projector = SkyProjector()
    val declutter = LabelDeclutter()

    /** Scratch for a single position read, reused rather than reallocated. */
    val angles = DoubleArray(2)

    private var ids = IntArray(INITIAL_CAPACITY)
    private var xs = FloatArray(INITIAL_CAPACITY)
    private var ys = FloatArray(INITIAL_CAPACITY)
    private var onScreen = BooleanArray(INITIAL_CAPACITY)
    private var aboveHorizon = BooleanArray(INITIAL_CAPACITY)

    /** How many glyphs the last completed frame drew. */
    var count: Int = 0
        private set

    fun beginFrame() {
        count = 0
        declutter.beginFrame()
    }

    /** Records where a satellite was drawn, so a later tap can find it. */
    fun record(noradId: Int, point: ProjectedPoint, elevationDeg: Double) {
        if (count == ids.size) grow()
        ids[count] = noradId
        xs[count] = point.x
        ys[count] = point.y
        onScreen[count] = point.isVisible
        aboveHorizon[count] = elevationDeg > 0.0
        count++
    }

    fun idAt(index: Int): Int = ids[index]

    fun xAt(index: Int): Float = xs[index]

    fun yAt(index: Int): Float = ys[index]

    fun isProjectedAt(index: Int): Boolean = onScreen[index]

    fun isAboveHorizonAt(index: Int): Boolean = aboveHorizon[index]

    /**
     * The satellite nearest a tap, or null if none is close enough.
     *
     * ## Why nearest rather than first hit
     *
     * A fingertip covers a good deal more sky than a glyph does, and satellites cluster. Taking
     * the first glyph within range would make the choice depend on iteration order, so tapping
     * the same pair of satellites twice could select different ones. Nearest is stable and is
     * what the user meant.
     *
     * ## Ghosts are tappable, but they never win a contest
     *
     * Satellites below the horizon are drawn as ghosts, and they answer a tap like anything else —
     * with the whole catalog tracked, most of the sky below the horizon is ghosts, and a screenful
     * of glyphs that ignore being touched is its own kind of broken.
     *
     * They are searched in a second pass rather than alongside the rest, so a ghost can never take
     * a tap from a satellite that is genuinely up even when the ghost is nearer the finger. That
     * was the original reason for excluding them, and it still holds; what changed is that being
     * *unreachable* is a worse answer than being second in line.
     */
    fun hitTest(x: Float, y: Float, radiusPx: Float): Int? = nearest(x, y, radiusPx, aboveHorizonOnly = true)
        ?: nearest(x, y, radiusPx, aboveHorizonOnly = false)

    private fun nearest(x: Float, y: Float, radiusPx: Float, aboveHorizonOnly: Boolean): Int? {
        var bestId: Int? = null
        var bestDistanceSq = radiusPx * radiusPx

        for (i in 0 until count) {
            if (!onScreen[i]) continue
            if (aboveHorizonOnly && !aboveHorizon[i]) continue
            val dx = xs[i] - x
            val dy = ys[i] - y
            val distanceSq = dx * dx + dy * dy
            // Strictly less, so an exact tie keeps the earlier — and therefore higher-priority —
            // satellite rather than depending on how the loop happens to run.
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq
                bestId = ids[i]
            }
        }
        return bestId
    }

    private fun grow() {
        ids = ids.copyOf(ids.size * 2)
        xs = xs.copyOf(xs.size * 2)
        ys = ys.copyOf(ys.size * 2)
        onScreen = onScreen.copyOf(onScreen.size * 2)
        aboveHorizon = aboveHorizon.copyOf(aboveHorizon.size * 2)
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
    }
}
