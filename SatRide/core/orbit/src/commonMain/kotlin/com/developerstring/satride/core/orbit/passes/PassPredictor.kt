package com.developerstring.satride.core.orbit.passes

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.model.TimedTopo
import com.developerstring.satride.core.model.Topocentric
import com.developerstring.satride.core.orbit.frames.Frames
import com.developerstring.satride.core.orbit.sgp4.PropagationException
import com.developerstring.satride.core.orbit.sgp4.Sgp4
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How the pass search behaves.
 *
 * [coarseStep] must stay well under the time a satellite spends above the horizon or short
 * passes are stepped straight over. A fast LEO pass is around eight minutes end to end, so 30
 * seconds gives plenty of margin; raising it to save work is a false economy that silently
 * loses passes.
 */
public data class PassSearchOptions(
    public val coarseStep: Duration = 30.seconds,
    /** Elevation that counts as the start of a pass. */
    public val minElevationDeg: Double = 0.0,
    /** Passes peaking below this are discarded entirely. */
    public val minPeakElevationDeg: Double = 0.0,
    /** Number of samples in the returned arc; drives polar-plot smoothness. */
    public val arcSamples: Int = 40,
    /** Refinement stops once AOS/LOS are known to better than this. */
    public val refinementTolerance: Duration = 1.seconds,
)

/**
 * Finds the transits of one satellite over one observer.
 *
 * Two-stage by design: a coarse scan finds sign changes in elevation, then bisection pins the
 * horizon crossings to under a second. Propagating at one-second resolution across a week
 * instead would cost hundreds of times more work for the same answer.
 */
public class PassPredictor(
    private val sgp4: Sgp4,
    private val observer: GeoPos,
    private val options: PassSearchOptions = PassSearchOptions(),
) {

    /** Elevation in degrees at [time]; `null` when the orbit model cannot reach that far. */
    private fun elevationAt(time: Instant): Double? = topoAt(time)?.elevationDeg

    private fun topoAt(time: Instant): Topocentric? = try {
        Frames.topocentric(sgp4.propagate(time), observer)
    } catch (e: PropagationException) {
        null
    }

    /**
     * All passes between [start] and [end].
     *
     * A pass already in progress at [start] is included, clipped to the window.
     *
     * @throws PropagationException if the satellite has decayed and cannot be propagated at all.
     */
    public fun findPasses(start: Instant, end: Instant): List<Pass> {
        // Fail loudly for a decayed or otherwise unusable element set rather than quietly
        // returning "no passes", which reads to the user as a quiet sky.
        val initial = topoAt(start)
            ?: throw PropagationException(
                "Cannot propagate satellite ${sgp4.elements.noradId} at $start; " +
                    "the element set may describe a decayed orbit",
                6,
                sgp4.elements.noradId,
            )

        val passes = mutableListOf<Pass>()
        val step = options.coarseStep
        var previousTime = start
        var previousElevation = initial.elevationDeg
        var aosTime: Instant? = if (previousElevation > options.minElevationDeg) start else null

        var time = start + step
        while (time <= end) {
            val elevation = elevationAt(time) ?: break

            val wasUp = previousElevation > options.minElevationDeg
            val isUp = elevation > options.minElevationDeg

            if (!wasUp && isUp) {
                aosTime = refineCrossing(previousTime, time, rising = true)
            } else if (wasUp && !isUp) {
                val los = refineCrossing(previousTime, time, rising = false)
                val aos = aosTime ?: start
                buildPass(aos, los)?.let { passes += it }
                aosTime = null
            }

            previousTime = time
            previousElevation = elevation
            time += step
        }

        // A pass still in progress when the window closes is reported clipped, not dropped:
        // "the ISS is overhead right now" is the most useful thing the app can say.
        val openAos = aosTime
        if (openAos != null && previousElevation > options.minElevationDeg) {
            buildPass(openAos, minOf(previousTime, end))?.let { passes += it }
        }

        return passes
    }

    /** The next pass at or after [from], or `null` if none within [searchLimit]. */
    public fun nextPass(from: Instant, searchLimit: Duration = Duration.parse("P7D")): Pass? =
        findPasses(from, from + searchLimit).firstOrNull()

    /**
     * Bisects between a time below the horizon and one above it.
     *
     * Elevation is monotonic across a horizon crossing, so bisection is both correct and
     * predictable — it always converges in a fixed number of steps.
     */
    private fun refineCrossing(before: Instant, after: Instant, rising: Boolean): Instant {
        var low = before
        var high = after
        while (high - low > options.refinementTolerance) {
            val mid = low + (high - low) / 2
            val elevation = elevationAt(mid) ?: return low
            val above = elevation > options.minElevationDeg
            if (above == rising) high = mid else low = mid
        }
        return if (rising) high else low
    }

    /**
     * Locates the peak by ternary search.
     *
     * Elevation across a pass is unimodal, which is exactly the condition ternary search needs;
     * a plain scan would have to sample far more finely to place the peak as precisely.
     */
    private fun findPeak(aos: Instant, los: Instant): Instant {
        var low = aos
        var high = los
        repeat(40) {
            if (high - low <= options.refinementTolerance) return@repeat
            val third = (high - low) / 3
            val m1 = low + third
            val m2 = high - third
            val e1 = elevationAt(m1) ?: return low
            val e2 = elevationAt(m2) ?: return low
            if (e1 < e2) low = m1 else high = m2
        }
        return low + (high - low) / 2
    }

    private fun buildPass(aos: Instant, los: Instant): Pass? {
        if (los <= aos) return null

        val tca = findPeak(aos, los)
        val peak = topoAt(tca) ?: return null
        if (peak.elevationDeg < options.minPeakElevationDeg) return null

        val aosTopo = topoAt(aos) ?: return null
        val losTopo = topoAt(los) ?: return null

        val samples = options.arcSamples.coerceAtLeast(2)
        val duration = los - aos
        val arc = ArrayList<TimedTopo>(samples)
        for (i in 0 until samples) {
            val t = aos + duration * (i.toDouble() / (samples - 1))
            topoAt(t)?.let { arc += TimedTopo(t, it) }
        }

        return Pass(
            noradId = sgp4.elements.noradId,
            aos = aos,
            tca = tca,
            los = los,
            maxElevationDeg = peak.elevationDeg,
            aosAzimuthDeg = aosTopo.azimuthDeg,
            losAzimuthDeg = losTopo.azimuthDeg,
            arc = arc,
        )
    }
}
