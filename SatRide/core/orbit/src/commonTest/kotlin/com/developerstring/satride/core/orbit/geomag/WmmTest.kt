package com.developerstring.satride.core.orbit.geomag

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.orbit.ReferenceData
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Magnetic declination checked against the NOAA model as implemented by pygeomag. */
class WmmTest {

    private val reference = ReferenceData.reference.wmm

    @Test
    fun declinationMatchesTheReferenceImplementation() {
        var worst = 0.0
        var worstSite = ""

        for (site in reference.sites) {
            val position = GeoPos(site.latDeg, site.lonDeg, site.altKm * 1000.0)

            val actual = Wmm.declinationDeg(position, reference.epochDecimalYear)

            val diff = abs(actual - site.declinationDeg)
            if (diff > worst) {
                worst = diff
                worstSite = site.name
            }
            assertEquals(
                site.declinationDeg,
                actual,
                0.5,
                "${site.name}: declination",
            )
        }
        println("WMM: worst declination difference $worst degrees (at $worstSite)")
        // The phase requires half a degree; a faithful implementation of the same model should
        // do far better than that, and this bound catches a subtly wrong recursion that still
        // lands in the right region.
        assertTrue(worst < 0.05, "Worst declination difference $worst degrees at $worstSite")
    }

    @Test
    fun declinationSignsMatchWellKnownGeography() {
        val time = Instant.parse("2026-07-02T00:00:00Z")

        // Off the US east coast the compass points west of true north; in western Europe it is
        // close to zero and slightly east.
        val newYork = Wmm.declinationDeg(GeoPos(40.7128, -74.0060), time)
        val moscow = Wmm.declinationDeg(GeoPos(55.7558, 37.6173), time)

        assertTrue(newYork < 0.0, "New York declination should be westward, was $newYork")
        assertTrue(moscow > 0.0, "Moscow declination should be eastward, was $moscow")
    }

    @Test
    fun fieldStrengthIsPhysicallyPlausibleEverywhere() {
        // Earth's surface field runs roughly 22,000-67,000 nT. Anything outside that means the
        // expansion has gone wrong somewhere.
        for (lat in -80..80 step 20) {
            for (lon in -180..150 step 30) {
                val field = Wmm.field(GeoPos(lat.toDouble(), lon.toDouble()), 2026.5)

                assertTrue(
                    field.intensityNt in 18_000.0..70_000.0,
                    "Field at $lat,$lon was ${field.intensityNt} nT",
                )
                assertTrue(
                    abs(field.declinationDeg) <= 180.0,
                    "Declination at $lat,$lon was ${field.declinationDeg}",
                )
            }
        }
    }

    @Test
    fun trueHeadingAppliesDeclinationAndWrapsCorrectly() {
        val time = Instant.parse("2026-07-02T00:00:00Z")
        val position = GeoPos(40.7128, -74.0060)
        val declination = Wmm.declinationDeg(position, time)

        val trueHeading = Wmm.toTrueHeadingDeg(10.0, position, time)

        assertEquals((10.0 + declination + 360.0) % 360.0, trueHeading, 1e-9)
        assertTrue(trueHeading in 0.0..360.0)

        // Wrapping past north must not produce a negative bearing.
        val nearNorth = Wmm.toTrueHeadingDeg(1.0, position, time)
        assertTrue(nearNorth in 0.0..360.0, "Heading wrapped to $nearNorth")
    }

    @Test
    fun modelValidityWindowIsReported() {
        assertTrue(Wmm.isValidFor(2026.5), "2026.5 should be inside the WMM-2025 window")
        assertTrue(!Wmm.isValidFor(2031.0), "2031 is past the model's five-year life")
        assertEquals(2030.0, Wmm.VALID_UNTIL_YEAR, 1e-9)
    }

    @Test
    fun polesAreHandledWithoutProducingNonFiniteValues() {
        // The eastward component divides by cos(latitude), which is singular at the poles and
        // needs the limiting form instead.
        for (lat in listOf(89.999, -89.999, 90.0, -90.0)) {
            val field = Wmm.field(GeoPos(lat, 0.0), 2026.5)

            assertTrue(field.northNt.isFinite(), "North component at $lat")
            assertTrue(field.eastNt.isFinite(), "East component at $lat")
            assertTrue(field.downNt.isFinite(), "Down component at $lat")
        }
    }
}
