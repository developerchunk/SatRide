package com.developerstring.satride.feature.arsky.guide

/**
 * How close the phone is to pointing at the target, as bands the feedback is defined over.
 *
 * Bands rather than a continuous function of the angle, because the guide has to be *legible*: a
 * user should be able to tell from the rhythm alone whether they are getting warmer, and a smooth
 * curve does not give the hand anything to latch onto. Each band is roughly one turn of the
 * wrist away from the next.
 */
enum class GuideBand {
    /** Nowhere near. Silence — a metronome that never stops is just noise. */
    COLD,

    /** In the right part of the sky. A slow, deliberate tick. */
    WARM,

    /** Close. Quick ticks, the rhythm that says "keep going". */
    HOT,

    /** On target. One firm event, then quiet. */
    LOCKED,
    ;

    companion object {
        /**
         * Which band an angular error falls in.
         *
         * The thresholds come from the accuracy budget rather than from taste. Compass error runs
         * to about five degrees, so a lock threshold much below that would be unreachable — the
         * user would be pointing exactly at the satellite and the app would insist they were not.
         * Three degrees is the tightest honest value, and the glyph's halo is drawn wider than it
         * so that "inside the halo" and "locked" agree.
         */
        fun of(errorDeg: Double): GuideBand = when {
            errorDeg <= LOCK_DEG -> LOCKED
            errorDeg <= HOT_DEG -> HOT
            errorDeg <= WARM_DEG -> WARM
            else -> COLD
        }

        const val LOCK_DEG: Double = 3.0
        const val HOT_DEG: Double = 10.0
        const val WARM_DEG: Double = 30.0
    }
}

/**
 * The haptic metronome that leads a user to a satellite they cannot see.
 *
 * ## Why this is a state machine and not a formula
 *
 * The interesting behaviour is all in the transitions. A lock must fire *once* on arrival, not
 * thirty times a second while the user holds still. Ticks must not stack up if a frame is late.
 * And the band must not chatter when the error sits exactly on a threshold — which it does
 * constantly, because a handheld phone wobbles by a degree or two no matter how still the person
 * thinks they are being.
 *
 * Driven by [onFrame], which is called from the render loop and returns what to play. Keeping the
 * decision here rather than in the composable means the feel can be tuned against tests instead
 * of by rebuilding and waving a phone around.
 */
class GuideFeedback(
    private val warmIntervalNanos: Long = WARM_INTERVAL_NANOS,
    private val hotIntervalNanos: Long = HOT_INTERVAL_NANOS,
    private val releaseDeg: Double = GuideBand.LOCK_DEG + LOCK_HYSTERESIS_DEG,
) {

    /** What the caller should play this frame. */
    enum class Pulse { NONE, TICK, LOCK }

    private var lastTickNanos = 0L

    /**
     * Whether [lastTickNanos] means anything yet.
     *
     * A flag rather than a sentinel value. The obvious `Long.MIN_VALUE` sentinel makes
     * `now - lastTick` overflow to a negative number, so the very first tick never fires — and it
     * does so invisibly on a device, where frame timestamps are large and positive enough that
     * the overflow only shows up if the clock happens to start near zero.
     */
    private var hasTicked = false
    private var locked = false

    var band: GuideBand = GuideBand.COLD
        private set

    /**
     * Advances the metronome.
     *
     * @param errorDeg angle between where the phone points and where the satellite is
     * @param nowNanos monotonic frame time
     */
    fun onFrame(errorDeg: Double, nowNanos: Long): Pulse {
        // Leaving the lock takes a wider angle than entering it. Without this the user holding
        // still at almost exactly three degrees would fire a lock, lose it, and fire it again,
        // several times a second.
        if (locked && errorDeg > releaseDeg) locked = false

        band = if (locked) GuideBand.LOCKED else GuideBand.of(errorDeg)

        return when (band) {
            GuideBand.LOCKED -> {
                if (locked) {
                    Pulse.NONE
                } else {
                    locked = true
                    lastTickNanos = nowNanos
                    hasTicked = true
                    Pulse.LOCK
                }
            }

            GuideBand.COLD -> {
                // Reset, so re-entering the warm band starts a tick immediately rather than
                // waiting out whatever remained of the last interval.
                hasTicked = false
                Pulse.NONE
            }

            GuideBand.WARM, GuideBand.HOT -> {
                val interval = if (band == GuideBand.HOT) hotIntervalNanos else warmIntervalNanos
                // A long gap — the app was backgrounded, say — produces one tick, not the dozens
                // that would have been due. The timestamp is reset to now rather than advanced by
                // the interval, so no backlog can accumulate.
                if (!hasTicked || nowNanos - lastTickNanos >= interval) {
                    lastTickNanos = nowNanos
                    hasTicked = true
                    Pulse.TICK
                } else {
                    Pulse.NONE
                }
            }
        }
    }

    /** Forgets everything, for when the target changes or guiding stops. */
    fun reset() {
        lastTickNanos = 0L
        hasTicked = false
        locked = false
        band = GuideBand.COLD
    }

    companion object {
        /**
         * Tick spacing in the two active bands.
         *
         * A little over two ticks a second when warm, five when hot. Fast enough that the change
         * between them is unmistakable, slow enough that neither becomes a buzz — past about
         * eight hertz the individual taps stop being distinguishable and the information in the
         * rhythm is lost. Recorded in BUILDLOG when tuned on hardware.
         */
        const val WARM_INTERVAL_NANOS: Long = 420_000_000L
        const val HOT_INTERVAL_NANOS: Long = 190_000_000L

        /** How much further than the lock threshold the user must drift before losing the lock. */
        const val LOCK_HYSTERESIS_DEG: Double = 1.5
    }
}
