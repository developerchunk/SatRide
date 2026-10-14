package com.developerstring.satride.feature.arsky.guide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guide loop is a "feels right" feature with a subjective acceptance gate, which is exactly
 * why its logic is pinned here: tuning the tick curve on hardware should mean changing two
 * constants, not rediscovering which transitions were meant to fire.
 */
class GuideFeedbackTest {

    private val ms = 1_000_000L

    @Test
    fun `bands follow the angular error`() {
        assertEquals(GuideBand.LOCKED, GuideBand.of(2.0))
        assertEquals(GuideBand.HOT, GuideBand.of(8.0))
        assertEquals(GuideBand.WARM, GuideBand.of(25.0))
        assertEquals(GuideBand.COLD, GuideBand.of(90.0))
    }

    @Test
    fun `the lock threshold is not tighter than the compass can resolve`() {
        // Compass error runs to about 5°. A lock threshold below that would be unreachable: the
        // user would be pointing straight at the satellite and the app would disagree.
        assertTrue(GuideBand.LOCK_DEG >= 2.0, "a sub-2° lock cannot be achieved on real hardware")
    }

    @Test
    fun `nothing fires when the target is nowhere near`() {
        val guide = GuideFeedback()

        assertEquals(GuideFeedback.Pulse.NONE, guide.onFrame(errorDeg = 120.0, nowNanos = 0))
        assertEquals(GuideFeedback.Pulse.NONE, guide.onFrame(errorDeg = 120.0, nowNanos = 500 * ms))
    }

    @Test
    fun `the warm band ticks — but not every frame`() {
        val guide = GuideFeedback()
        var now = 0L

        assertEquals(GuideFeedback.Pulse.TICK, guide.onFrame(20.0, now))

        // The next few frames, at 30 Hz, must stay silent.
        repeat(5) {
            now += 33 * ms
            assertEquals(GuideFeedback.Pulse.NONE, guide.onFrame(20.0, now))
        }

        now += 300 * ms
        assertEquals(GuideFeedback.Pulse.TICK, guide.onFrame(20.0, now))
    }

    @Test
    fun `the hot band ticks faster than the warm band`() {
        assertTrue(GuideFeedback.HOT_INTERVAL_NANOS < GuideFeedback.WARM_INTERVAL_NANOS)

        val warm = GuideFeedback()
        val hot = GuideFeedback()
        var now = 0L
        warm.onFrame(20.0, now)
        hot.onFrame(6.0, now)

        var warmTicks = 0
        var hotTicks = 0
        repeat(60) {
            now += 33 * ms
            if (warm.onFrame(20.0, now) == GuideFeedback.Pulse.TICK) warmTicks++
            if (hot.onFrame(6.0, now) == GuideFeedback.Pulse.TICK) hotTicks++
        }

        assertTrue(hotTicks > warmTicks, "closer must feel faster: warm=$warmTicks hot=$hotTicks")
    }

    @Test
    fun `ticks never come faster than a person can feel them apart`() {
        // Past roughly eight hertz individual taps stop being distinguishable and the rhythm
        // stops carrying any information, which is the whole point of the metronome.
        val fastest = 1_000_000_000.0 / GuideFeedback.HOT_INTERVAL_NANOS
        assertTrue(fastest <= 8.0, "the hot band ticks at ${fastest}Hz, which reads as a buzz")
    }

    @Test
    fun `the lock fires exactly once on arrival`() {
        val guide = GuideFeedback()
        var now = 0L

        assertEquals(GuideFeedback.Pulse.LOCK, guide.onFrame(1.0, now))

        // Holding still on the target must be silent — not thirty locks a second.
        repeat(30) {
            now += 33 * ms
            assertEquals(GuideFeedback.Pulse.NONE, guide.onFrame(1.0, now))
        }
        assertEquals(GuideBand.LOCKED, guide.band)
    }

    @Test
    fun `a lock held at the threshold does not chatter`() {
        val guide = GuideFeedback()
        var now = 0L
        guide.onFrame(2.9, now)

        // A hand wobbling either side of 3° must not fire a lock per wobble.
        var locks = 0
        repeat(40) { index ->
            now += 33 * ms
            val error = if (index % 2 == 0) 2.9 else 3.4
            if (guide.onFrame(error, now) == GuideFeedback.Pulse.LOCK) locks++
        }

        assertEquals(0, locks, "hand tremor across the threshold must not re-fire the lock")
    }

    @Test
    fun `drifting well away and coming back locks again`() {
        val guide = GuideFeedback()
        var now = 0L
        assertEquals(GuideFeedback.Pulse.LOCK, guide.onFrame(1.0, now))

        now += 100 * ms
        guide.onFrame(40.0, now)

        now += 100 * ms
        assertEquals(
            GuideFeedback.Pulse.LOCK,
            guide.onFrame(1.0, now),
            "a genuine re-acquisition deserves its own confirmation",
        )
    }

    @Test
    fun `re-entering the warm band ticks immediately rather than serving out the old interval`() {
        val guide = GuideFeedback()
        var now = 0L
        guide.onFrame(20.0, now)

        now += 50 * ms
        guide.onFrame(120.0, now)

        now += 50 * ms
        assertEquals(
            GuideFeedback.Pulse.TICK,
            guide.onFrame(20.0, now),
            "swinging back onto the target should answer at once",
        )
    }

    @Test
    fun `resetting forgets the lock so a new target starts clean`() {
        val guide = GuideFeedback()
        guide.onFrame(1.0, 0)
        assertEquals(GuideBand.LOCKED, guide.band)

        guide.reset()

        assertEquals(GuideBand.COLD, guide.band)
        assertEquals(GuideFeedback.Pulse.LOCK, guide.onFrame(1.0, 100 * ms))
    }

    @Test
    fun `a late frame produces one tick — not a backlog`() {
        val guide = GuideFeedback()
        guide.onFrame(20.0, 0)

        // The app was backgrounded for ten seconds. Twenty-four intervals have elapsed; the user
        // must not be handed twenty-four taps.
        assertEquals(GuideFeedback.Pulse.TICK, guide.onFrame(20.0, 10_000 * ms))
        assertEquals(GuideFeedback.Pulse.NONE, guide.onFrame(20.0, 10_001 * ms))
    }
}
