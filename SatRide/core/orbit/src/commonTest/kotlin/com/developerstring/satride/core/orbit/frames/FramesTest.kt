package com.developerstring.satride.core.orbit.frames

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.Orbit
import com.developerstring.satride.core.orbit.ReferenceData
import com.developerstring.satride.core.orbit.math.Vector3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Validates the coordinate chain against Skyfield.
 *
 * This is the layer the golden SGP4 vectors cannot reach: they stop at TEME, while everything
 * the user sees depends on what happens after it.
 */
class FramesTest {

    private val reference = ReferenceData.reference
    private val satellite = Orbit.satellite(
        reference.tle.name,
        reference.tle.line1,
        reference.tle.line2,
    )

    private fun site(name: String): GeoPos = reference.sites.first { it.name == name }
        .let { GeoPos(it.lat, it.lon, it.altM) }

    @Test
    fun azimuthAndElevationMatchSkyfield() {
        var worstAz = 0.0
        var worstEl = 0.0
        var samples = 0

        for ((siteName, rows) in reference.topocentric) {
            val observer = site(siteName)
            for (row in rows) {
                val topo = satellite.observe(Instant.parse(row.utc), observer)

                // Azimuth is meaningless when the satellite is near the zenith and is not
                // something the user can perceive below the horizon, so compare it only where
                // it is both defined and visible.
                if (row.elDeg > 0.0 && row.elDeg < 88.0) {
                    var dAz = abs(topo.azimuthDeg - row.azDeg)
                    if (dAz > 180.0) dAz = 360.0 - dAz
                    worstAz = maxOf(worstAz, dAz)
                }
                worstEl = maxOf(worstEl, abs(topo.elevationDeg - row.elDeg))
                samples++
            }
        }

        assertTrue(samples > 300, "Only $samples samples compared")
        // 0.01 degrees is well inside the 0.1 degree the phase requires, and two orders of
        // magnitude finer than the compass error the AR view lives with.
        assertTrue(worstAz < 0.01, "Worst azimuth difference $worstAz degrees over $samples samples")
        assertTrue(worstEl < 0.01, "Worst elevation difference $worstEl degrees over $samples samples")
    }

    @Test
    fun rangeAndRangeRateMatchSkyfield() {
        var worstRange = 0.0
        var worstRate = 0.0

        for ((siteName, rows) in reference.topocentric) {
            val observer = site(siteName)
            for (row in rows) {
                val topo = satellite.observe(Instant.parse(row.utc), observer)
                worstRange = maxOf(worstRange, abs(topo.rangeKm - row.rangeKm))
                worstRate = maxOf(worstRate, abs(topo.rangeRateKmS - row.rangeRateKmS))
            }
        }

        assertTrue(worstRange < 0.5, "Worst range difference $worstRange km")
        // Range rate drives Doppler: 1e-4 km/s is about 50 Hz at 145 MHz, comfortably inside
        // the receiver's own frequency error.
        assertTrue(worstRate < 1e-3, "Worst range-rate difference $worstRate km/s")
    }

    @Test
    fun subSatellitePointMatchesSkyfield() {
        var worstLat = 0.0
        var worstLon = 0.0
        var worstAlt = 0.0

        for (sample in reference.ephemeris) {
            val state = satellite.stateAt(Instant.parse(sample.utc))
            val sub = Frames.subPoint(state)
            worstLat = maxOf(worstLat, abs(sub.latDeg - sample.subLatDeg))
            var dLon = abs(sub.lonDeg - sample.subLonDeg)
            if (dLon > 180.0) dLon = 360.0 - dLon
            worstLon = maxOf(worstLon, dLon)
            worstAlt = maxOf(worstAlt, abs(sub.altM / 1000.0 - sample.subAltKm))
        }

        assertTrue(worstLat < 0.01, "Worst sub-point latitude difference $worstLat degrees")
        assertTrue(worstLon < 0.01, "Worst sub-point longitude difference $worstLon degrees")
        assertTrue(worstAlt < 0.5, "Worst sub-point altitude difference $worstAlt km")
    }

    @Test
    fun sunlitStateMatchesSkyfield() {
        // The cylindrical shadow model has no penumbra, so a sample taken within a minute or so
        // of the terminator can legitimately disagree. Require near-total agreement instead of
        // perfection, and check the disagreements are rare.
        val total = reference.ephemeris.size
        val agreements = reference.ephemeris.count { sample ->
            val state = satellite.stateAt(Instant.parse(sample.utc))
            Frames.isSunlit(state.position, state.time) == sample.sunlit
        }

        assertTrue(
            agreements >= total - 1,
            "Eclipse state agreed on $agreements of $total samples",
        )
    }

    @Test
    fun geodeticConversionRoundTrips() {
        val places = listOf(
            GeoPos(18.5204, 73.8567, 560.0),
            GeoPos(-41.2865, 174.7762, 0.0),
            GeoPos(0.0, 0.0, 0.0),
            GeoPos(89.0, -179.0, 100.0),
            GeoPos(-89.0, 179.0, 3000.0),
        )

        for (place in places) {
            val roundTripped = Frames.ecefToGeodetic(Frames.geodeticToEcef(place))

            assertEquals(place.latDeg, roundTripped.latDeg, 1e-7, "latitude of $place")
            assertEquals(place.lonDeg, roundTripped.lonDeg, 1e-7, "longitude of $place")
            assertEquals(place.altM, roundTripped.altM, 1e-3, "altitude of $place")
        }
    }

    @Test
    fun observerAtEquatorSitsOnTheEquatorialRadius() {
        val ecef = Frames.geodeticToEcef(GeoPos(0.0, 0.0, 0.0))

        assertEquals(Constants.WGS84_SEMI_MAJOR_KM, ecef.x, 1e-9)
        assertEquals(0.0, ecef.y, 1e-9)
        assertEquals(0.0, ecef.z, 1e-9)
    }

    @Test
    fun rangeRateIsNegativeApproachingAndPositiveReceding() {
        val observer = site("Pune")
        val pass = satellite.nextPass(observer, Instant.parse("2026-08-02T00:00:00Z"))
            ?: error("Expected an ISS pass over Pune")

        val approaching = satellite.observe(pass.aos, observer)
        val receding = satellite.observe(pass.los, observer)
        val atClosest = satellite.observe(pass.tca, observer)

        assertTrue(approaching.rangeRateKmS < 0.0, "Should be closing at AOS")
        assertTrue(receding.rangeRateKmS > 0.0, "Should be opening at LOS")
        // The peak of the pass is not exactly the closest approach, but it is close enough that
        // the range rate has all but vanished there.
        assertTrue(
            abs(atClosest.rangeRateKmS) < abs(approaching.rangeRateKmS),
            "Range rate should be smallest near closest approach",
        )
    }

    @Test
    fun footprintGrowsWithAltitude() {
        val low = Frames.footprintRadiusKm(400.0)
        val high = Frames.footprintRadiusKm(800.0)

        assertTrue(low in 1000.0..3000.0, "ISS-altitude footprint radius was $low km")
        assertTrue(high > low, "A higher satellite must see more of the Earth")
    }

    @Test
    fun temeToEcefIsAPureRotation() {
        val teme = Vector3(4000.0, -5000.0, 3000.0)

        val ecef = Frames.temeToEcef(teme, 1.234)

        assertEquals(teme.magnitude, ecef.magnitude, 1e-9, "Rotation must preserve length")
    }
}
