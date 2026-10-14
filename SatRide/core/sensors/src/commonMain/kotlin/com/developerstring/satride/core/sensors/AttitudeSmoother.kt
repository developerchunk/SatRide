package com.developerstring.satride.core.sensors

import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.math.Quaternion
import kotlin.math.exp

/**
 * Tuning for [AttitudeSmoother]. Collected in one place so it can be adjusted from a debug
 * overlay on a real device, which is the only way these numbers can honestly be chosen.
 */
public data class AttitudeSmoothingConfig(
    /**
     * Time constant when the phone is still. Long, because this is the regime where the only
     * signal is magnetometer noise and the user is trying to hold steady on a satellite.
     */
    public val stillTimeConstantSeconds: Double = 1.2,

    /** Time constant when the phone is being swung. Short enough that the sky does not lag. */
    public val movingTimeConstantSeconds: Double = 0.04,

    /** Lead angle below which motion is indistinguishable from noise. Degrees. */
    public val stillLeadDeg: Double = 1.5,

    /** Lead angle at which the filter should be tracking as fast as it can. Degrees. */
    public val movingLeadDeg: Double = 8.0,

    /**
     * Time constant of the probe that estimates how fast the phone is turning.
     *
     * Short enough to react within a couple of frames, long enough that the probe lags real
     * motion measurably — that lag is the measurement.
     */
    public val probeTimeConstantSeconds: Double = 0.15,

    /**
     * Sample intervals are clamped to this. A dropped frame, a debugger pause or a wake from
     * background can otherwise deliver an interval of seconds, which would snap the filter
     * straight to the raw sample and undo exactly the jitter rejection it exists for.
     */
    public val maxIntervalSeconds: Double = 0.1,
)

/**
 * Turns noisy orientation samples into something that can be drawn without shimmering.
 *
 * This filter is where the polish of the whole AR view lives. Too little smoothing and satellite
 * glyphs vibrate; too much and the sky lags behind the phone, which feels worse than the jitter
 * ever did. The resolution is to change behaviour based on what the user is doing.
 *
 * ## Distinguishing motion from noise
 *
 * The obvious approach — adapt on the error between the filtered and raw values — cannot work,
 * because one degree of magnetometer noise and one degree of genuine motion are the same number.
 * Instead a second, fixed, fast filter runs alongside as a *probe*, and the angle by which the
 * raw signal leads that probe is the measurement:
 *
 * - **Noise** is zero-mean, so the probe sits in the middle of it and the lead stays around the
 *   noise amplitude — a degree or so.
 * - **Real motion** is sustained, so the raw signal pulls steadily ahead of the lagging probe.
 *   Turning at 60°/s with a 0.15 s probe gives roughly 9° of lead.
 *
 * An order of magnitude between the two cases, from a comparison that costs one extra slerp.
 *
 * Approach informed by Stardroid's sensor smoothing (Apache-2.0, see docs/plan/07-references.md);
 * the velocity-probe formulation and the constants are ours.
 *
 * Not thread-safe: one instance per collector, driven from a single sensor callback.
 */
public class AttitudeSmoother(private val config: AttitudeSmoothingConfig = AttitudeSmoothingConfig()) {

    private var smoothed: Quaternion? = null
    private var probe: Quaternion? = null
    private var lastElapsedNanos: Long = 0L

    /** The filtered orientation, or null before the first sample. */
    public val current: Quaternion? get() = smoothed

    /** How far the raw signal is leading the probe, in degrees. Zero until the second sample. */
    public var leadDeg: Double = 0.0
        private set

    /** The interval the filter is currently converging over. Surfaced for the debug overlay. */
    public var timeConstantSeconds: Double = 0.0
        private set

    public fun reset() {
        smoothed = null
        probe = null
        lastElapsedNanos = 0L
        leadDeg = 0.0
        timeConstantSeconds = 0.0
    }

    /** Filters [sample], returning the orientation to draw. */
    public fun update(sample: AttitudeSample): Quaternion {
        val previous = smoothed
        val previousProbe = probe

        if (previous == null || previousProbe == null) {
            smoothed = sample.rotation
            probe = sample.rotation
            lastElapsedNanos = sample.elapsedNanos
            timeConstantSeconds = config.stillTimeConstantSeconds
            return sample.rotation
        }

        val dt = ((sample.elapsedNanos - lastElapsedNanos) / 1_000_000_000.0)
            .coerceIn(0.0, config.maxIntervalSeconds)
        lastElapsedNanos = sample.elapsedNanos
        // A repeated timestamp carries no new information about elapsed time; blending on a zero
        // interval would leave the output frozen while the input moved.
        if (dt <= 0.0) return previous

        val advancedProbe = previousProbe.slerp(sample.rotation, blend(dt, config.probeTimeConstantSeconds))
        probe = advancedProbe

        leadDeg = sample.rotation.angleTo(advancedProbe) * Constants.RAD_TO_DEG
        timeConstantSeconds = timeConstantFor(leadDeg)

        val result = previous.slerp(sample.rotation, blend(dt, timeConstantSeconds))
        smoothed = result
        return result
    }

    /**
     * Interpolates between the still and moving time constants.
     *
     * Linear in the lead angle rather than in a smoothed velocity: the extra sophistication is
     * unmeasurable next to the several degrees of compass error the AR view already carries.
     */
    private fun timeConstantFor(lead: Double): Double {
        val span = config.movingLeadDeg - config.stillLeadDeg
        val t = if (span <= 0.0) 1.0 else ((lead - config.stillLeadDeg) / span).coerceIn(0.0, 1.0)
        return config.stillTimeConstantSeconds +
            t * (config.movingTimeConstantSeconds - config.stillTimeConstantSeconds)
    }

    /**
     * Exponential blend factor for an interval and a time constant.
     *
     * Derived from `dt` rather than fixed per sample, so the filter behaves identically whether
     * the sensor is delivering 30 or 200 samples a second — device sample rates vary widely and
     * a fixed factor would make the same code feel different on different phones.
     */
    private fun blend(dt: Double, timeConstant: Double): Double =
        if (timeConstant <= 0.0) 1.0 else 1.0 - exp(-dt / timeConstant)
}
