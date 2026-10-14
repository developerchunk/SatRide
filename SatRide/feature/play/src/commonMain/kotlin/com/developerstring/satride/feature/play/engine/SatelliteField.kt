package com.developerstring.satride.feature.play.engine

import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Satellite
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.orbit.Orbit
import com.developerstring.satride.core.orbit.OrbitingSatellite
import kotlin.concurrent.Volatile
import kotlin.time.Instant

/**
 * Every satellite the globe draws, propagated in rotation.
 *
 * The tracking engine is observer-centric — it answers "where do I point the phone" — while the
 * globe needs geocentric positions for everything regardless of any observer, so the Play screen
 * runs its own propagator pool rather than widening the tracked set.
 *
 * ## The two-rate design, again
 *
 * [tick] runs SGP4 on a background dispatcher a few times a second; the renderer calls
 * [temePositionInto] per satellite per frame, which only interpolates between that satellite's last
 * two fixes. Positions stay in TEME here: the renderer rotates them into the Earth-fixed frame with
 * a single GMST rotation per frame, which also means fast-forwarded time spins the planet for free.
 *
 * ## Why a tick no longer propagates everything
 *
 * It used to, and that was right for the bundled few hundred. The field now carries whatever the
 * user has asked to track, which can be the whole catalog — and thirty thousand SGP4 evaluations
 * several times a second is roughly a core, permanently, on a screen that is also drawing a globe.
 *
 * So a tick propagates a **slice**, resuming where the last one left off, and every satellite
 * carries its own pair of fix times. That is what makes the rotation invisible: interpolation
 * spans one satellite's own last two fixes rather than a clock shared with the rest, and it
 * extrapolates a full span past the newest one — so a satellite refreshed every twenty seconds
 * still moves continuously, along a chord whose deviation from the true arc is under a kilometre
 * at orbital scale. The slice is sized from the *simulated* seconds a tick covers, so the rotation
 * keeps up when time is running fast.
 *
 * ## Why fixes are double-buffered
 *
 * The tick writes on `Dispatchers.Default` while the UI thread reads mid-frame. Each tick fills an
 * off-screen buffer — carrying forward everything the slice does not touch — and publishes it
 * through a single [Volatile] reference, so a frame sees either the old state or the new one, never
 * a half-written mixture.
 */
class SatelliteField {

    // Volatile because [load] runs on a background dispatcher while the render thread reads
    // every frame: the volatile write of the final count publishes the array swaps before it,
    // so a frame can never see the new count paired with the old (smaller) arrays.
    @Volatile
    var count: Int = 0
        private set

    var ids: IntArray = IntArray(0)
        private set

    var names: Array<String> = emptyArray()
        private set

    var classes: Array<SatClass> = emptyArray()
        private set

    private var propagators: Array<OrbitingSatellite?> = emptyArray()

    /**
     * Two fixes per satellite, with their own times.
     *
     * Per-satellite times rather than one pair for the field: with the rotation, two satellites
     * refreshed on different ticks have genuinely different fix intervals, and interpolating both
     * against a shared clock would put whichever was refreshed longer ago in the wrong place.
     */
    private class Fixes(size: Int) {
        val t0Ms: DoubleArray = DoubleArray(size)
        val t1Ms: DoubleArray = DoubleArray(size)
        val p0: DoubleArray = DoubleArray(size * 3)
        val p1: DoubleArray = DoubleArray(size * 3)
        val ok: BooleanArray = BooleanArray(size)

        /** When the most recent tick ran, whatever slice it covered. */
        var tickMs: Double = 0.0

        fun copyFrom(other: Fixes) {
            other.t0Ms.copyInto(t0Ms)
            other.t1Ms.copyInto(t1Ms)
            other.p0.copyInto(p0)
            other.p1.copyInto(p1)
            other.ok.copyInto(ok)
            tickMs = other.tickMs
        }
    }

    @Volatile
    private var front: Fixes? = null
    private var back: Fixes? = null

    /** Where the rotation resumes on the next tick. */
    private var cursor: Int = 0

    /**
     * How many ticks have run since the field was loaded.
     *
     * The first two are always full, whatever slice was asked for. The first fill gives every
     * satellite a position but no *rate* — both endpoints collapse to one instant — so a satellite
     * the rotation has not reached yet would hold still until it came round, which over a large
     * field is a visible twenty seconds of a half-frozen globe on every load. A second full pass
     * costs one extra sweep and gives everything a real pair immediately.
     */
    private var ticks: Int = 0

    /** Builds the pool from the catalog; entries without an element set are dropped. */
    fun load(satellites: List<Satellite>, tles: Map<Int, Tle>) {
        val loaded = satellites.mapNotNull { satellite ->
            val tle = tles[satellite.noradId] ?: return@mapNotNull null
            val propagator = runCatching { Orbit.satellite(tle) }.getOrNull() ?: return@mapNotNull null
            satellite to propagator
        }
        // Close the gate, swap everything, then open it: the count write is last on purpose.
        count = 0
        ids = IntArray(loaded.size) { loaded[it].first.noradId }
        names = Array(loaded.size) { loaded[it].first.name }
        classes = Array(loaded.size) { loaded[it].first.classType }
        propagators = Array(loaded.size) { loaded[it].second }
        index = buildIndex(ids)
        front = null
        back = null
        cursor = 0
        ticks = 0
        count = loaded.size
    }

    /**
     * Catalog number to slot.
     *
     * A map rather than the old linear `ids.indexOf`, which is called on every tap, on every HUD
     * update and once per orbit-ring sample. Over a few hundred satellites that was free; over
     * thirty thousand it is a scan per lookup.
     */
    private var index: Map<Int, Int> = emptyMap()

    private fun buildIndex(ids: IntArray): Map<Int, Int> =
        buildMap(ids.size) { ids.forEachIndexed { slot, id -> put(id, slot) } }

    /**
     * Propagates a slice of the field to [time] and publishes the result.
     *
     * Call from a background dispatcher. [sliceSize] is how many satellites this tick may
     * evaluate; pass [FULL_FIELD] to refresh everything, which is what a small field and the first
     * fill both want. Whatever is not in the slice keeps the fixes it had, so it goes on being
     * interpolated from its own pair rather than freezing.
     */
    fun tick(time: Instant, sliceSize: Int = FULL_FIELD) {
        val size = count
        if (size == 0) return
        val current = front
        val next = back?.takeIf { it.ok.size == size } ?: Fixes(size)
        val timeMs = time.toEpochMilliseconds().toDouble()

        if (current == null) {
            // First fill: every satellite gets a fix, and both endpoints collapse to one instant
            // so interpolation degenerates to a hold until the next tick gives it a rate.
            propagateSlice(time, timeMs, next, 0, size, firstFill = true)
            back = current
            front = next
            cursor = 0
            ticks = 1
            return
        }

        next.copyFrom(current)

        // Time jumped backwards — a speed change, a resync, a scrub. Interpolating from fixes
        // ahead of the requested instant would run every satellite backwards along its chord, so
        // the field is refilled from scratch instead.
        if (timeMs <= current.tickMs) {
            propagateSlice(time, timeMs, next, 0, size, firstFill = true)
            back = current
            front = next
            cursor = 0
            ticks = 1
            return
        }

        // The second tick is full whatever was asked for; see [ticks].
        val warmingUp = ticks < WARMUP_TICKS
        val slice = if (warmingUp || sliceSize <= 0 || sliceSize >= size) size else sliceSize
        propagateSlice(time, timeMs, next, cursor, slice, firstFill = false)
        cursor = if (slice >= size) 0 else (cursor + slice) % size
        ticks++

        back = current
        front = next
    }

    /**
     * Evaluates [length] satellites starting at [start], wrapping.
     *
     * The wrap is what makes the rotation uniform: without it the tail of the field would be
     * refreshed less often than the head, and the satellites drawn near one edge of the globe
     * would visibly lag the rest.
     */
    private fun propagateSlice(
        time: Instant,
        timeMs: Double,
        fixes: Fixes,
        start: Int,
        length: Int,
        firstFill: Boolean,
    ) {
        val size = count
        var offset = 0
        while (offset < length) {
            val i = (start + offset) % size
            offset++
            val base = i * 3

            val state = runCatching { propagators[i]?.stateAt(time) }.getOrNull()
            if (state == null) {
                // Decayed or numerically failed: the satellite simply is not drawn.
                fixes.ok[i] = false
                continue
            }

            val fresh = firstFill || !fixes.ok[i]
            if (fresh) {
                // Nothing to interpolate from yet: both endpoints collapse to this instant, so the
                // satellite holds still until its next fix gives it a rate.
                fixes.p0[base] = state.position.x
                fixes.p0[base + 1] = state.position.y
                fixes.p0[base + 2] = state.position.z
                fixes.t0Ms[i] = timeMs
            } else {
                fixes.p0[base] = fixes.p1[base]
                fixes.p0[base + 1] = fixes.p1[base + 1]
                fixes.p0[base + 2] = fixes.p1[base + 2]
                fixes.t0Ms[i] = fixes.t1Ms[i]
            }

            fixes.p1[base] = state.position.x
            fixes.p1[base + 1] = state.position.y
            fixes.p1[base + 2] = state.position.z
            fixes.t1Ms[i] = timeMs
            fixes.ok[i] = true
        }
        fixes.tickMs = timeMs
    }

    /**
     * TEME position (km) at [timeMs] into [out], interpolated between the last two fixes.
     *
     * The renderer's per-frame read path: no allocation, no propagation. Extrapolates up to one
     * extra fix interval past the newest fix — the frame always asks for a time slightly ahead
     * of the last tick — and holds beyond that rather than flinging the glyph onward.
     *
     * @return false while the satellite has no usable fix yet.
     */
    fun temePositionInto(index: Int, timeMs: Double, out: DoubleArray): Boolean {
        val fixes = front ?: return false
        if (index < 0 || index >= count || index >= fixes.ok.size || !fixes.ok[index]) return false
        val t0 = fixes.t0Ms[index]
        val t1 = fixes.t1Ms[index]
        val span = t1 - t0
        val t = if (span <= 0.0) 1.0 else ((timeMs - t0) / span).coerceIn(0.0, 2.0)
        val base = index * 3
        out[0] = fixes.p0[base] + (fixes.p1[base] - fixes.p0[base]) * t
        out[1] = fixes.p0[base + 1] + (fixes.p1[base + 1] - fixes.p0[base + 1]) * t
        out[2] = fixes.p0[base + 2] + (fixes.p1[base + 2] - fixes.p0[base + 2]) * t
        return true
    }

    fun indexOf(noradId: Int): Int = index[noradId] ?: -1

    /** The propagator itself, for the HUD's exact numbers and the orbit ring's sampling. */
    fun propagatorFor(noradId: Int): OrbitingSatellite? {
        val slot = indexOf(noradId)
        return if (slot >= 0) propagators[slot] else null
    }

    companion object {
        /** Pass as `sliceSize` to refresh every satellite this tick. */
        const val FULL_FIELD: Int = Int.MAX_VALUE

        /** How many ticks are forced to cover the whole field after a load. See [ticks]. */
        private const val WARMUP_TICKS: Int = 2
    }
}
