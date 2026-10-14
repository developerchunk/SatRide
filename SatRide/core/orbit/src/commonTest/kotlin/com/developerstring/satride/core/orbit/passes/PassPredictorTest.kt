package com.developerstring.satride.core.orbit.passes

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.orbit.Orbit
import com.developerstring.satride.core.orbit.ReferenceData
import com.developerstring.satride.core.orbit.sgp4.PropagationException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Pass search checked against Skyfield's independent event finder. */
class PassPredictorTest {

    private val reference = ReferenceData.reference
    private val satellite = Orbit.satellite(
        reference.tle.name,
        reference.tle.line1,
        reference.tle.line2,
    )
    private val windowStart = Instant.parse("2026-08-02T00:00:00Z")
    private val windowEnd = Instant.parse("2026-08-03T00:00:00Z")

    private fun site(name: String): GeoPos =
        reference.sites.first { it.name == name }.let { GeoPos(it.lat, it.lon, it.altM) }

    @Test
    fun findsTheSamePassesAsSkyfield() {
        for ((siteName, expectedPasses) in reference.passes) {
            val observer = site(siteName)
            val actual = satellite.passes(observer, windowStart, windowEnd)

            // Skyfield reports only complete rise-culminate-set triples inside the window,
            // while we also report a pass already under way when the window opens. Match on
            // the complete ones.
            val comparable = actual.filter { it.aos > windowStart }

            assertEquals(
                expectedPasses.size,
                comparable.size,
                "$siteName: pass count over 24 hours",
            )

            for ((expected, got) in expectedPasses.zip(comparable)) {
                val expectedAos = Instant.parse(expected.aos.utc)
                val expectedTca = Instant.parse(expected.tca.utc)
                val expectedLos = Instant.parse(expected.los.utc)

                assertTrue(
                    abs((got.aos - expectedAos).inWholeSeconds) <= 10,
                    "$siteName AOS: expected $expectedAos, got ${got.aos}",
                )
                assertTrue(
                    abs((got.los - expectedLos).inWholeSeconds) <= 10,
                    "$siteName LOS: expected $expectedLos, got ${got.los}",
                )
                assertTrue(
                    abs((got.tca - expectedTca).inWholeSeconds) <= 30,
                    "$siteName TCA: expected $expectedTca, got ${got.tca}",
                )
                assertEquals(
                    expected.tca.elDeg,
                    got.maxElevationDeg,
                    1.0,
                    "$siteName peak elevation",
                )
                assertEquals(
                    expected.aos.azDeg,
                    got.aosAzimuthDeg,
                    2.0,
                    "$siteName rise azimuth",
                )
            }
        }
    }

    @Test
    fun passesAreOrderedAndInternallyConsistent() {
        val passes = satellite.passes(site("Pune"), windowStart, windowEnd)

        assertTrue(passes.isNotEmpty(), "Expected ISS passes over Pune in 24 hours")
        for (pass in passes) {
            assertTrue(pass.aos < pass.tca, "AOS must precede closest approach")
            assertTrue(pass.tca < pass.los, "Closest approach must precede LOS")
            assertTrue(pass.maxElevationDeg > 0.0, "A pass must rise above the horizon")
            assertTrue(pass.arc.size >= 20, "Arc has ${pass.arc.size} samples; too few to plot")
            // The peak of the sampled arc should not exceed the reported maximum.
            val arcPeak = pass.arc.maxOf { it.topo.elevationDeg }
            assertTrue(
                arcPeak <= pass.maxElevationDeg + 0.01,
                "Arc peak $arcPeak exceeds reported maximum ${pass.maxElevationDeg}",
            )
        }
        for (i in 1 until passes.size) {
            assertTrue(passes[i - 1].los < passes[i].aos, "Passes must not overlap")
        }
    }

    @Test
    fun minimumPeakElevationFiltersMarginalPasses() {
        val observer = site("Pune")
        val all = satellite.passes(observer, windowStart, windowEnd)
        val good = satellite.passes(
            observer,
            windowStart,
            windowEnd,
            PassSearchOptions(minPeakElevationDeg = 30.0),
        )

        assertTrue(good.size <= all.size)
        assertTrue(good.all { it.maxElevationDeg >= 30.0 })
    }

    /**
     * A satellite that never rises must produce an empty list rather than an error: from the
     * user's point of view "nothing to see" is a normal answer, not a failure.
     */
    @Test
    fun returnsNoPassesForASatelliteThatNeverRises() {
        // The ISS is inclined 51.6 degrees and its footprint reaches about 20 degrees beyond
        // its ground track, so it can never clear the horizon from close to the North Pole.
        // Using the real element set keeps this a statement about geometry rather than about
        // a TLE invented for the test.
        val northPole = GeoPos(89.5, 0.0, 0.0)

        val passes = satellite.passes(northPole, windowStart, windowEnd)

        assertTrue(
            passes.isEmpty(),
            "The ISS cannot rise at 89.5N; found ${passes.size} passes " +
                "(peak ${passes.maxOfOrNull { it.maxElevationDeg }})",
        )
    }

    @Test
    fun decayedElementSetFailsLoudlyRatherThanReportingAQuietSky() {
        // Mean motion this high puts the object below the Earth's surface: the propagator
        // reports decay, and the predictor must surface that instead of returning no passes,
        // which the UI would render as "nothing overhead".
        val decayed = Orbit.satellite(
            "DECAYED",
            "1 25544U 98067A   26213.47431246  .00008515  00000-0  16079-3 0  9990",
            "2 25544  51.6315  75.9770 0007211   0.6648 359.4349 20.00000000578771",
        )

        assertFailsWith<PropagationException> {
            decayed.passes(site("Pune"), windowStart, windowEnd)
        }
    }

    @Test
    fun nextPassFindsTheFirstUpcomingTransit() {
        val observer = site("Pune")

        val next = satellite.nextPass(observer, windowStart, 2.days)
        val all = satellite.passes(observer, windowStart, windowEnd)

        assertTrue(next != null, "Expected an upcoming pass")
        assertEquals(all.first().aos, next.aos)
    }
}
