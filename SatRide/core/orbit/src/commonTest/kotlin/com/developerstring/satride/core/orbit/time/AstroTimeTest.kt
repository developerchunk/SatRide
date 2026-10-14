package com.developerstring.satride.core.orbit.time

import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.ReferenceData
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class AstroTimeTest {

    @Test
    fun julianDateOfJ2000IsExact() {
        // 2000-01-01T12:00:00Z is J2000.0 by definition.
        val j2000 = Instant.parse("2000-01-01T12:00:00Z")

        assertEquals(Constants.JULIAN_DATE_J2000, AstroTime.julianDate(j2000), 1e-9)
    }

    @Test
    fun julianDateOfUnixEpochIsExact() {
        assertEquals(2440587.5, AstroTime.julianDate(Instant.fromEpochMilliseconds(0)), 1e-9)
    }

    /**
     * Guards the half-day bug that hid behind the SGP4 golden vectors: those propagate by
     * elapsed minutes, so an epoch offset cancels out and never shows up. It only surfaces once
     * a satellite is placed against a rotating Earth — by which point every position is wrong
     * by 180 degrees of longitude.
     */
    @Test
    fun yearStartMatchesTheStandardJdayFormula() {
        for (year in listOf(1957, 1969, 1999, 2000, 2001, 2024, 2026, 2050)) {
            // jday(year, 1, 1, 0, 0, 0) reduced to January 1st.
            val expected = 367.0 * year -
                floor((7.0 * (year + floor((1.0 + 9.0) / 12.0))) * 0.25) +
                floor(275.0 * 1 / 9.0) + 1.0 + 1721013.5

            assertEquals(
                expected,
                AstroTime.julianDateOfYearStart(year),
                1e-9,
                "January 1.0 of $year",
            )
        }
    }

    @Test
    fun tleEpochRoundTripsToTheCorrectInstant() {
        // 2026 day 213.47431246 -> 2026-08-01, just past 11:23 UTC.
        val epoch = AstroTime.fromTleEpoch(26, 213.47431246)

        assertEquals("2026-08-01", epoch.toString().substringBefore('T'), "date")
        val jd = AstroTime.julianDate(epoch)
        val expectedJd = AstroTime.julianDateOfYearStart(2026) + 213.47431246 - 1.0
        assertEquals(expectedJd, jd, 1e-6)
    }

    @Test
    fun twoDigitYearsResolveAroundTheSputnikBoundary() {
        assertEquals(1957, AstroTime.fromTleEpoch(57, 1.0).toString().take(4).toInt())
        assertEquals(1999, AstroTime.fromTleEpoch(99, 1.0).toString().take(4).toInt())
        assertEquals(2000, AstroTime.fromTleEpoch(0, 1.0).toString().take(4).toInt())
        assertEquals(2026, AstroTime.fromTleEpoch(26, 1.0).toString().take(4).toInt())
    }

    /** GMST is the rotation that puts a satellite over a place rather than a direction. */
    @Test
    fun gmstMatchesSkyfield() {
        var worst = 0.0
        for (sample in ReferenceData.reference.ephemeris) {
            val time = Instant.parse(sample.utc)
            val actualHours = AstroTime.gmstRadians(time) * Constants.RAD_TO_DEG / 15.0
            var diff = abs(actualHours - sample.gmstHours)
            if (diff > 12.0) diff = 24.0 - diff
            worst = maxOf(worst, diff)
        }
        // One second of time is 1/3600 hour; this bound is about a tenth of that, which is far
        // finer than the UT1-UTC difference we deliberately ignore.
        assertTrue(worst < 3e-5, "Worst GMST difference ${worst * 3600} seconds of time")
    }

    @Test
    fun decimalYearIsUsableByTheMagneticModel() {
        val mid2026 = Instant.parse("2026-07-02T00:00:00Z")

        assertEquals(2026.5, AstroTime.decimalYear(mid2026), 0.01)
    }
}
