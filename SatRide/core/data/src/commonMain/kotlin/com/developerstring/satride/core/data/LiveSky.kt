package com.developerstring.satride.core.data

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.SatelliteSnapshot
import com.developerstring.satride.core.model.Topocentric
import com.developerstring.satride.core.sensors.AttitudeFrames
import kotlin.time.Instant

/**
 * Live positions for the whole tracked set, as parallel primitive arrays.
 *
 * ## Why this is not a `Map<Int, SatelliteSnapshot>`
 *
 * It used to be, and that was correct while the tracked set was the bundled three dozen objects.
 * It stops being correct the moment the user asks to track the catalog. A map rebuilt every second
 * over twenty thousand satellites allocates twenty thousand `SatelliteSnapshot` values, twenty
 * thousand `Topocentric` values, twenty thousand `GeoPos` values and a hash table to hold them —
 * roughly forty megabytes a minute of garbage for numbers that are overwritten before anyone reads
 * most of them. On a phone that is not a slow frame, it is a stuttering app.
 *
 * So the tracked set is stored once, column-wise, in primitive arrays that are **written in place**
 * on every tick. A tick allocates nothing at all. Twenty thousand satellites cost about three
 * megabytes, permanently, and a tick's cost becomes purely the orbital mechanics — which is the
 * only part that was ever doing useful work.
 *
 * ## Reading it
 *
 * [ids] is sorted ascending, so [indexOf] is a binary search: about fifteen comparisons over the
 * full catalog, no allocation, safe to call from a draw call. Everything else is an array read at
 * that index. Helpers like [elevationDegOf] and [snapshotOf] exist for callers that want one
 * satellite; a caller that walks the whole set should index the arrays directly rather than build
 * objects it is about to throw away.
 *
 * ## Concurrency
 *
 * The tracking engine writes; the UI and the renderer read, without locks. That is deliberate and
 * it is safe *because of what is stored*: every field is an independently-written primitive, and a
 * reader that catches a write in progress sees one satellite's azimuth from this second beside its
 * elevation from last second. Both are angles that move under a degree a second, so the worst
 * possible outcome is a glyph a fraction of a degree out for a single frame — far inside the
 * compass error the sky view already carries. Locking a structure the renderer touches once per
 * satellite per frame would cost incomparably more than that.
 *
 * The array *identities* never change: a change to the tracked set builds a whole new [LiveSky]
 * rather than resizing this one, so a renderer walking the arrays can never be handed a shorter
 * one mid-frame.
 *
 * [version] is what makes this usable with `StateFlow`: the instance is the same object across
 * ticks, so [equals] compares versions instead of identity, and a tick therefore emits.
 */
class LiveSky internal constructor(
    /** Catalog numbers, ascending. The only array a caller may treat as stable content. */
    internal val ids: IntArray,
) {
    val size: Int get() = ids.size

    /** Bumped once per tick, after the arrays have been written. */
    var version: Long = 0L
        internal set

    /** When the most recent tick was computed. */
    var time: Instant = Instant.DISTANT_PAST
        internal set

    /** How many entries were refreshed on the most recent tick — the live tier plus its rotation. */
    var refreshedCount: Int = 0
        internal set

    internal val azimuthDeg = DoubleArray(ids.size)
    internal val elevationDeg = DoubleArray(ids.size)
    internal val rangeKm = DoubleArray(ids.size)
    internal val rangeRateKmS = DoubleArray(ids.size)
    internal val subLatDeg = DoubleArray(ids.size)
    internal val subLonDeg = DoubleArray(ids.size)
    internal val subAltM = DoubleArray(ids.size)
    internal val footprintRadiusKm = DoubleArray(ids.size)
    internal val speedKmS = DoubleArray(ids.size)
    internal val sunlit = BooleanArray(ids.size)

    /** Epoch microseconds of the sample in the arrays above. */
    internal val sampledAtMicros = LongArray(ids.size)

    /** The sample before it, for the renderer's between-tick interpolation. */
    internal val previousAzimuthDeg = DoubleArray(ids.size)
    internal val previousElevationDeg = DoubleArray(ids.size)
    internal val previousSampledAtMicros = LongArray(ids.size)

    /** How many samples an entry has: 0 (never propagated), 1, or 2 (interpolatable). */
    internal val samples = ByteArray(ids.size)

    /** Index of [noradId], or -1. A binary search — no allocation, cheap enough for a draw call. */
    fun indexOf(noradId: Int): Int {
        var low = 0
        var high = ids.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = ids[mid]
            when {
                value < noradId -> low = mid + 1
                value > noradId -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** True when this satellite is being tracked *and* has a position. */
    fun has(noradId: Int): Boolean {
        val index = indexOf(noradId)
        return index >= 0 && samples[index] > 0
    }

    fun hasPositionAt(index: Int): Boolean = samples[index] > 0

    // --- Index-addressed reads --------------------------------------------------------------
    //
    // For callers that walk the whole set — the sky renderer choosing what to draw, a count of
    // what is up. They exist so those callers never have to binary-search per satellite, and so
    // the arrays themselves stay internal: exposing them would make the class's one real
    // invariant, that all the columns are the same length and index-aligned, impossible to hold.

    fun idAt(index: Int): Int = ids[index]

    fun elevationDegAt(index: Int): Double = elevationDeg[index]

    fun azimuthDegAt(index: Int): Double = azimuthDeg[index]

    fun rangeKmAt(index: Int): Double = rangeKm[index]

    fun isSunlitAt(index: Int): Boolean = sunlit[index]

    /** Elevation in degrees, or [Double.NaN] when there is no position yet. */
    fun elevationDegOf(noradId: Int): Double {
        val index = indexOf(noradId)
        return if (index < 0 || samples[index].toInt() == 0) Double.NaN else elevationDeg[index]
    }

    fun azimuthDegOf(noradId: Int): Double {
        val index = indexOf(noradId)
        return if (index < 0 || samples[index].toInt() == 0) Double.NaN else azimuthDeg[index]
    }

    fun isUp(noradId: Int): Boolean {
        val elevation = elevationDegOf(noradId)
        return !elevation.isNaN() && elevation > 0.0
    }

    fun isSunlit(noradId: Int): Boolean {
        val index = indexOf(noradId)
        return index >= 0 && samples[index] > 0 && sunlit[index]
    }

    /**
     * The full record for one satellite, allocated on demand.
     *
     * For a detail screen or a radio session — somewhere a handful of satellites are examined
     * closely. Calling it in a loop over the tracked set is exactly the allocation this class
     * exists to avoid; index the arrays instead.
     */
    fun snapshotOf(noradId: Int): SatelliteSnapshot? {
        val index = indexOf(noradId)
        if (index < 0 || samples[index].toInt() == 0) return null
        return SatelliteSnapshot(
            noradId = noradId,
            time = Instant.fromEpochMilliseconds(sampledAtMicros[index] / 1_000L),
            topo = Topocentric(
                azimuthDeg = azimuthDeg[index],
                elevationDeg = elevationDeg[index],
                rangeKm = rangeKm[index],
                rangeRateKmS = rangeRateKmS[index],
            ),
            subPoint = GeoPos(subLatDeg[index], subLonDeg[index], subAltM[index]),
            footprintRadiusKm = footprintRadiusKm[index],
            speedKmS = speedKmS[index],
            sunlit = sunlit[index],
        )
    }

    fun topoOf(noradId: Int): Topocentric? {
        val index = indexOf(noradId)
        if (index < 0 || samples[index].toInt() == 0) return null
        return Topocentric(
            azimuthDeg = azimuthDeg[index],
            elevationDeg = elevationDeg[index],
            rangeKm = rangeKm[index],
            rangeRateKmS = rangeRateKmS[index],
        )
    }

    /** Catalog numbers currently above the horizon, most elevated first. */
    fun upNow(limit: Int = Int.MAX_VALUE): List<Int> {
        val found = ArrayList<Int>(minOf(limit, 64))
        for (index in ids.indices) {
            if (samples[index] > 0 && elevationDeg[index] > 0.0) found += index
        }
        found.sortByDescending { elevationDeg[it] }
        return found.take(limit).map { ids[it] }
    }

    /**
     * A cheap digest of everything a *list ordering* depends on.
     *
     * The catalog sorts by "is it up", not by the degree, and it badges what is sunlit. Both change
     * a handful of times a minute where the underlying angles change every second — so a consumer
     * that re-sorts whenever this value changes re-sorts exactly when the answer would differ, and
     * never otherwise.
     *
     * Deliberately a digest rather than a timer. Sampling on an interval would be simpler and is
     * what this replaced: it re-sorted on a schedule whether or not anything had moved, it lagged a
     * satellite rising by up to the interval, and — because it schedules a repeating delay
     * forever — it made every test of a consumer hang on a virtual clock that could never reach
     * idle.
     *
     * One pass over the columns, no allocation. Collisions are possible and harmless: the worst
     * case is one ordering pass skipped, and the next tick that changes anything picks it up.
     */
    fun orderingSignature(): Long {
        var digest = 1125899906842597L
        for (index in ids.indices) {
            if (samples[index].toInt() == 0) continue
            if (elevationDeg[index] <= 0.0) continue
            digest = digest * 31 + ids[index]
            if (sunlit[index]) digest++
        }
        return digest
    }

    fun countUp(): Int {
        var total = 0
        for (index in ids.indices) {
            if (samples[index] > 0 && elevationDeg[index] > 0.0) total++
        }
        return total
    }

    /**
     * Azimuth and elevation at an arbitrary instant, written into [out] rather than returned.
     *
     * The renderer's inner loop: called once per satellite per frame, so it allocates nothing and
     * does no propagation. See [FixPair] for why it extrapolates past the newest sample rather than
     * holding — briefly, because holding produces a visible one-second stutter and the motion is
     * near-linear over that interval.
     */
    fun interpolateInto(noradId: Int, timeMicros: Long, out: DoubleArray): Boolean =
        interpolateIndexInto(indexOf(noradId), timeMicros, out)

    fun interpolateIndexInto(index: Int, timeMicros: Long, out: DoubleArray): Boolean {
        if (index < 0) return false
        val sampleCount = samples[index].toInt()
        if (sampleCount == 0) return false

        if (sampleCount == 1) {
            out[0] = azimuthDeg[index]
            out[1] = elevationDeg[index]
            return true
        }

        val previousMicros = previousSampledAtMicros[index]
        val latestMicros = sampledAtMicros[index]
        val span = latestMicros - previousMicros
        if (span <= 0L) {
            out[0] = azimuthDeg[index]
            out[1] = elevationDeg[index]
            return true
        }

        val fraction = ((timeMicros - previousMicros).toDouble() / span)
            .coerceIn(0.0, 1.0 + FixPair.MAX_EXTRAPOLATION_SPANS)

        val previousAzimuth = previousAzimuthDeg[index]
        val azimuth = previousAzimuth +
            AttitudeFrames.azimuthDeltaDeg(previousAzimuth, azimuthDeg[index]) * fraction
        out[0] = (azimuth % 360.0 + 360.0) % 360.0

        val previousElevation = previousElevationDeg[index]
        out[1] = (previousElevation + (elevationDeg[index] - previousElevation) * fraction)
            .coerceIn(-90.0, 90.0)
        return true
    }

    /** Writes one satellite's freshly propagated state. Called only by the tracking engine. */
    internal fun write(index: Int, snapshot: SatelliteSnapshot, atMicros: Long) {
        if (samples[index] > 0) {
            previousAzimuthDeg[index] = azimuthDeg[index]
            previousElevationDeg[index] = elevationDeg[index]
            previousSampledAtMicros[index] = sampledAtMicros[index]
            if (samples[index].toInt() == 1) samples[index] = 2
        } else {
            samples[index] = 1
        }
        val topo = snapshot.topo
        azimuthDeg[index] = topo.azimuthDeg
        elevationDeg[index] = topo.elevationDeg
        rangeKm[index] = topo.rangeKm
        rangeRateKmS[index] = topo.rangeRateKmS
        subLatDeg[index] = snapshot.subPoint.latDeg
        subLonDeg[index] = snapshot.subPoint.lonDeg
        subAltM[index] = snapshot.subPoint.altM
        footprintRadiusKm[index] = snapshot.footprintRadiusKm
        speedKmS[index] = snapshot.speedKmS
        sunlit[index] = snapshot.sunlit
        sampledAtMicros[index] = atMicros
    }

    /**
     * Carries forward what is still known about the satellites in [previous].
     *
     * Called when the tracked set changes. Without it, adding one satellite to a selection of five
     * thousand would blank the other four thousand nine hundred and ninety-nine until they had
     * been propagated again — the sky would empty and refill for no reason the user could see.
     */
    internal fun inherit(previous: LiveSky) {
        var from = 0
        var into = 0
        while (from < previous.ids.size && into < ids.size) {
            val previousId = previous.ids[from]
            val id = ids[into]
            when {
                previousId < id -> from++

                previousId > id -> into++

                else -> {
                    if (previous.samples[from] > 0) {
                        azimuthDeg[into] = previous.azimuthDeg[from]
                        elevationDeg[into] = previous.elevationDeg[from]
                        rangeKm[into] = previous.rangeKm[from]
                        rangeRateKmS[into] = previous.rangeRateKmS[from]
                        subLatDeg[into] = previous.subLatDeg[from]
                        subLonDeg[into] = previous.subLonDeg[from]
                        subAltM[into] = previous.subAltM[from]
                        footprintRadiusKm[into] = previous.footprintRadiusKm[from]
                        speedKmS[into] = previous.speedKmS[from]
                        sunlit[into] = previous.sunlit[from]
                        sampledAtMicros[into] = previous.sampledAtMicros[from]
                        previousAzimuthDeg[into] = previous.previousAzimuthDeg[from]
                        previousElevationDeg[into] = previous.previousElevationDeg[from]
                        previousSampledAtMicros[into] = previous.previousSampledAtMicros[from]
                        samples[into] = previous.samples[from]
                    }
                    from++
                    into++
                }
            }
        }
        version = previous.version
        time = previous.time
    }

    /**
     * Version equality, not content equality.
     *
     * `StateFlow` conflates by `equals`, and this object is deliberately the same instance from one
     * tick to the next — the whole point is that a tick does not allocate. Identity equality would
     * therefore make every tick look like "no change" and the UI would freeze; comparing versions
     * makes a tick emit exactly once.
     */
    override fun equals(other: Any?): Boolean = other is LiveSky && other.version == version && other.ids === ids

    override fun hashCode(): Int = version.hashCode() * 31 + ids.size

    companion object {
        val EMPTY: LiveSky = LiveSky(IntArray(0))

        /**
         * Builds a table from ready-made snapshots.
         *
         * For fakes, previews and anything that has positions already and needs them in the shape
         * the app reads. Nothing on the propagation path uses it — the engine writes into a table
         * it already owns, which is the whole point — so this is free to be the straightforward
         * implementation.
         *
         * Two samples per entry, a second apart, so interpolation works: a table built with one
         * sample would return a frozen position, and a test of anything that moves would pass for
         * the wrong reason.
         */
        fun of(snapshots: Collection<SatelliteSnapshot>, version: Long = 1L): LiveSky {
            val sorted = snapshots.sortedBy { it.noradId }
            val sky = LiveSky(IntArray(sorted.size) { sorted[it].noradId })
            sorted.forEachIndexed { index, snapshot ->
                val micros = snapshot.time.toEpochMicroseconds()
                sky.write(index, snapshot, micros - 1_000_000L)
                sky.write(index, snapshot, micros)
            }
            sky.version = version
            sky.time = sorted.firstOrNull()?.time ?: Instant.DISTANT_PAST
            sky.refreshedCount = sorted.size
            return sky
        }
    }
}
