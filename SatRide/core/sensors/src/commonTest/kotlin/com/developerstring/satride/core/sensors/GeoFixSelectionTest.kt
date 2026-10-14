package com.developerstring.satride.core.sensors

import com.developerstring.satride.core.model.GeoPos
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which of two fixes to keep.
 *
 * This exists because the Android source now listens to GPS **and** the network provider at the
 * same time, and they interleave. It was written after a real failure: with only GPS registered,
 * an indoor phone got no fix at all and fell back to a cached network position that was *four days
 * old*, so the Passes tab computed a whole day of passes for somewhere the user no longer was —
 * and said nothing.
 */
class GeoFixSelectionTest {

    private fun fix(accuracyM: Double?, ageMinutes: Long) =
        GeoFix(GeoPos(18.8, 77.5), accuracyM, ageMillis = ageMinutes * 60_000L)

    @Test
    fun anythingBeatsNothing() {
        assertTrue(isBetterFix(fix(500.0, 60), current = null))
    }

    @Test
    fun aMuchFresherFixWinsEvenIfItIsCoarser() {
        // The case that matters: a 100 m network fix from now beats a 10 m GPS fix from four days
        // ago, because the user may simply not be there any more.
        val stalePrecise = fix(accuracyM = 10.0, ageMinutes = 4 * 24 * 60)
        val freshCoarse = fix(accuracyM = 100.0, ageMinutes = 0)

        assertTrue(isBetterFix(freshCoarse, stalePrecise))
        assertFalse(isBetterFix(stalePrecise, freshCoarse))
    }

    @Test
    fun amongEquallyFreshFixesTheMoreAccurateWins() {
        // Both current: this is GPS converging behind the network provider, which is exactly the
        // sequence the two-provider change produces.
        val network = fix(accuracyM = 120.0, ageMinutes = 0)
        val gps = fix(accuracyM = 8.0, ageMinutes = 0)

        assertTrue(isBetterFix(gps, network))
        assertFalse(isBetterFix(network, gps))
    }

    @Test
    fun aCoarseFixDoesNotOverwriteAGoodOneMomentsLater() {
        // The regression the guard exists for: without it, whatever arrived last won, so a network
        // fix landing a second after a good GPS one would replace it.
        val gps = fix(accuracyM = 8.0, ageMinutes = 0)
        val networkASecondLater = fix(accuracyM = 150.0, ageMinutes = 0)

        assertFalse(isBetterFix(networkASecondLater, gps))
    }

    @Test
    fun aFixWithNoAccuracyIsTreatedAsTheWorstCaseRatherThanTheBest() {
        val unknown = fix(accuracyM = null, ageMinutes = 0)
        val known = fix(accuracyM = 200.0, ageMinutes = 0)

        assertTrue(isBetterFix(known, unknown))
        assertFalse(isBetterFix(unknown, known))
    }

    @Test
    fun stalenessIsAnHourAndDaysAreObviouslyStale() {
        assertFalse(fix(50.0, ageMinutes = 30).isStale)
        assertFalse(fix(50.0, ageMinutes = 59).isStale)
        assertTrue(fix(50.0, ageMinutes = 61).isStale)
        assertTrue(fix(50.0, ageMinutes = 4 * 24 * 60).isStale)
    }

    @Test
    fun aFreshFixDefaultsToZeroAge() {
        // The default matters: every existing construction site omits it, and a default that read
        // as "very old" would make every fix look stale.
        assertFalse(GeoFix(GeoPos(0.0, 0.0)).isStale)
    }
}
