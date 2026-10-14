package com.developerstring.satride.core.orbit.doppler

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.Orbit
import com.developerstring.satride.core.orbit.ReferenceData
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DopplerTest {

    private val reference = ReferenceData.reference
    private val satellite = Orbit.satellite(
        reference.tle.name,
        reference.tle.line1,
        reference.tle.line2,
    )
    private val pune = reference.sites.first { it.name == "Pune" }
        .let { GeoPos(it.lat, it.lon, it.altM) }

    /** 137.1 MHz is the Meteor-M LRPT downlink, one of the app's headline targets. */
    private val meteorHz = 137_100_000L
    private val issVoiceHz = 145_800_000L

    @Test
    fun approachingRaisesAndRecedingLowersTheFrequency() {
        val closing = Doppler.receivedHz(meteorHz, rangeRateKmS = -7.0)
        val opening = Doppler.receivedHz(meteorHz, rangeRateKmS = 7.0)

        assertTrue(closing > meteorHz, "Closing satellite must appear higher in frequency")
        assertTrue(opening < meteorHz, "Receding satellite must appear lower in frequency")
        assertEquals(meteorHz.toDouble(), Doppler.receivedHz(meteorHz, 0.0), 1e-9)
    }

    @Test
    fun shiftMagnitudeMatchesTheClassicalFormula() {
        val rangeRate = -6.5
        val expected = -meteorHz * rangeRate / Constants.SPEED_OF_LIGHT_KM_S

        assertEquals(expected, Doppler.shiftHz(meteorHz, rangeRate), 1e-9)
        // Sanity check against the physical scale: a few kHz at VHF for a LEO pass.
        assertTrue(abs(expected) in 2000.0..4000.0, "Shift was $expected Hz")
    }

    /**
     * The whole point of the correction: across a pass the shift sweeps through several kHz,
     * crosses zero at closest approach and always moves downwards.
     */
    @Test
    fun shiftSweepsMonotonicallyDownAndCrossesZeroAtClosestApproach() {
        val pass = satellite.passes(
            pune,
            Instant.parse("2026-08-02T00:00:00Z"),
            Instant.parse("2026-08-03T00:00:00Z"),
        ).maxBy { it.maxElevationDeg }

        val shifts = pass.arc.map { Doppler.shiftHz(meteorHz, it.topo.rangeRateKmS) }

        assertTrue(shifts.first() > 0.0, "Shift should start positive while approaching")
        assertTrue(shifts.last() < 0.0, "Shift should end negative while receding")
        for (i in 1 until shifts.size) {
            assertTrue(
                shifts[i] <= shifts[i - 1] + 1.0,
                "Shift must fall through the pass; rose at sample $i",
            )
        }

        val swing = shifts.first() - shifts.last()
        assertTrue(swing in 2000.0..12000.0, "Total swing at 137 MHz was $swing Hz")

        // Closest approach is where the shift passes through zero.
        val atClosest = satellite.observe(pass.tca, pune)
        assertTrue(
            abs(Doppler.shiftHz(meteorHz, atClosest.rangeRateKmS)) < 300.0,
            "Shift at closest approach was ${Doppler.shiftHz(meteorHz, atClosest.rangeRateKmS)} Hz",
        )
    }

    @Test
    fun driftPredictsTheNextSecondsShift() {
        val time = Instant.parse("2026-08-02T12:00:00Z")
        val state = satellite.doppler(time, pune, issVoiceHz)
        val oneSecondLater = satellite.doppler(time + 1.seconds, pune, issVoiceHz)

        // Drift is the derivative, so stepping by it should land on the next value. The bound
        // covers the second-order term the linear ramp cannot capture.
        val predicted = state.correctedHz + state.driftHzPerS
        assertEquals(oneSecondLater.correctedHz, predicted, 5.0)
    }

    @Test
    fun invertingTransponderFlipsTheCorrection() {
        val time = Instant.parse("2026-08-02T12:00:00Z")

        val normal = satellite.doppler(time, pune, issVoiceHz, invert = false)
        val inverted = satellite.doppler(time, pune, issVoiceHz, invert = true)

        assertEquals(normal.shiftHz, -inverted.shiftHz, 1e-6)
    }

    @Test
    fun maxShiftSizesTheCaptureBandwidth() {
        // A 437 MHz cubesat seen from the ground swings about +/-10 kHz, which is what the
        // receiver's captured band has to accommodate without retuning mid-pass.
        val uhf = Doppler.maxShiftHz(437_000_000L, maxRangeRateKmS = 7.5)

        assertTrue(uhf in 8000.0..14000.0, "Maximum UHF shift was $uhf Hz")
    }
}
