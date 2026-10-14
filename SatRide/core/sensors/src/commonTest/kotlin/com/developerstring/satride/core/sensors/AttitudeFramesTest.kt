package com.developerstring.satride.core.sensors

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.Topocentric
import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.geomag.Wmm
import com.developerstring.satride.core.orbit.math.Quaternion
import com.developerstring.satride.core.orbit.math.Vector3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The frame conventions, pinned.
 *
 * Every one of these could be "verified" by eye on a device and every one of them could be wrong
 * in a way that still looks plausible — a mirrored sky, a 90-degree rotation, a doubled
 * declination. They are stated as tests so that a change to [AttitudeFrames] has to argue with
 * something concrete.
 */
class AttitudeFramesTest {

    private val tolerance = 1e-9

    /** Rotation that leaves the device upright with the camera pointing at the horizon. */
    private fun pointingAt(azimuthDeg: Double, elevationDeg: Double): Quaternion {
        // Build it from the target direction rather than from Euler angles, so the test does not
        // reimplement the thing it is testing.
        val forward = AttitudeFrames.directionOf(azimuthDeg, elevationDeg)
        val from = AttitudeFrames.CAMERA_FORWARD
        val axis = from cross forward
        val dot = (from dot forward).coerceIn(-1.0, 1.0)
        return if (axis.magnitude < 1e-12) {
            if (dot > 0) Quaternion.IDENTITY else Quaternion.fromAxisAngle(AttitudeFrames.UP, Constants.TWO_PI / 2)
        } else {
            Quaternion.fromAxisAngle(axis, kotlin.math.acos(dot))
        }
    }

    @Test
    fun azimuthIsMeasuredClockwiseFromNorth() {
        assertAzEl(AttitudeFrames.toAzimuthElevation(AttitudeFrames.NORTH), 0.0, 0.0)
        assertAzEl(AttitudeFrames.toAzimuthElevation(AttitudeFrames.EAST), 90.0, 0.0)
        assertAzEl(AttitudeFrames.toAzimuthElevation(-AttitudeFrames.NORTH), 180.0, 0.0)
        assertAzEl(AttitudeFrames.toAzimuthElevation(-AttitudeFrames.EAST), 270.0, 0.0)
    }

    /**
     * The mirroring check. Swapping the arguments to atan2 produces a sky reflected about the
     * north-south line, which is correct at north and south and wrong everywhere else — so it has
     * to be tested somewhere other than the cardinal points.
     */
    @Test
    fun northEastIsBetweenNorthAndEast() {
        val northEast = (AttitudeFrames.NORTH + AttitudeFrames.EAST).normalized()

        assertEquals(45.0, AttitudeFrames.toAzimuthElevation(northEast).azimuthDeg, 1e-9)
    }

    @Test
    fun elevationIsMeasuredAboveTheHorizon() {
        assertAzEl(AttitudeFrames.toAzimuthElevation(AttitudeFrames.UP), 0.0, 90.0, checkAzimuth = false)
        assertEquals(-90.0, AttitudeFrames.toAzimuthElevation(-AttitudeFrames.UP).elevationDeg, 1e-9)
        assertEquals(
            45.0,
            AttitudeFrames.toAzimuthElevation(AttitudeFrames.NORTH + AttitudeFrames.UP).elevationDeg,
            1e-9,
        )
    }

    @Test
    fun directionOfAndToAzimuthElevationAreInverses() {
        listOf(0.0, 37.0, 90.0, 180.0, 271.5, 359.9).forEach { az ->
            listOf(-80.0, -12.0, 0.0, 15.0, 62.0, 89.0).forEach { el ->
                val roundTripped = AttitudeFrames.toAzimuthElevation(AttitudeFrames.directionOf(az, el))
                assertEquals(az, roundTripped.azimuthDeg, 1e-9, "azimuth $az/$el")
                assertEquals(el, roundTripped.elevationDeg, 1e-9, "elevation $az/$el")
            }
        }
    }

    /** The camera looks out of the back of the phone, not out of the screen. */
    @Test
    fun anUnrotatedDeviceLooksAtTheHorizonToTheNorth() {
        // Identity attitude: device axes are world axes, so -Z (camera) is -Up... which is
        // straight down. That is the physically correct reading of a phone lying face up.
        val pointing = AttitudeFrames.pointingDirection(Quaternion.IDENTITY)

        assertEquals(-1.0, pointing.z, tolerance, "a face-up phone's camera points at the ground")
    }

    @Test
    fun pointingDirectionFollowsTheAttitude() {
        listOf(0.0, 45.0, 137.0, 300.0).forEach { az ->
            listOf(0.0, 30.0, 75.0).forEach { el ->
                val result = AttitudeFrames.pointingAzimuthElevation(pointingAt(az, el))
                assertEquals(az, result.azimuthDeg, 1e-6, "azimuth for $az/$el")
                assertEquals(el, result.elevationDeg, 1e-6, "elevation for $az/$el")
            }
        }
    }

    /**
     * CoreMotion's `.xTrueNorthZVertical` is north-west-up. Without the quarter turn the sky is
     * rotated 90 degrees on iOS only — which presents as "the compass is broken on iPhone".
     */
    @Test
    fun coreMotionsNorthWestUpFrameBecomesEastNorthUp() {
        // A device whose camera looks along NWU's +X, i.e. north.
        val nwuNorth = Vector3(1.0, 0.0, 0.0)
        val enu = AttitudeFrames.NWU_TO_ENU.rotate(nwuNorth)
        assertVector(AttitudeFrames.NORTH, enu, "NWU north must map to ENU north")

        // NWU's +Y is west, which is ENU's -East.
        val enuWest = AttitudeFrames.NWU_TO_ENU.rotate(Vector3(0.0, 1.0, 0.0))
        assertVector(-AttitudeFrames.EAST, enuWest, "NWU west must map to ENU west")

        // Up is shared by both frames.
        assertVector(AttitudeFrames.UP, AttitudeFrames.NWU_TO_ENU.rotate(Vector3(0.0, 0.0, 1.0)))
    }

    /**
     * Declination sign. Getting this backwards doubles the error instead of removing it, and
     * because declination is near zero across much of Europe and North America the mistake would
     * look fine precisely where it was most likely to be tried.
     */
    @Test
    fun applyingEastwardDeclinationIncreasesTheTrueBearing() {
        // Phone pointing at magnetic north, in a place where magnetic north is 10 degrees east
        // of true north. Its true bearing is therefore 010.
        val magneticNorth = pointingAt(0.0, 0.0)

        val corrected = AttitudeFrames.toTrueNorth(magneticNorth, declinationDeg = 10.0)

        assertEquals(10.0, AttitudeFrames.pointingAzimuthElevation(corrected).azimuthDeg, 1e-6)
    }

    @Test
    fun applyingWestwardDeclinationDecreasesTheTrueBearing() {
        val corrected = AttitudeFrames.toTrueNorth(pointingAt(90.0, 0.0), declinationDeg = -15.0)

        assertEquals(75.0, AttitudeFrames.pointingAzimuthElevation(corrected).azimuthDeg, 1e-6)
    }

    @Test
    fun declinationDoesNotChangeElevation() {
        val corrected = AttitudeFrames.toTrueNorth(pointingAt(45.0, 60.0), declinationDeg = 20.0)

        assertEquals(60.0, AttitudeFrames.pointingAzimuthElevation(corrected).elevationDeg, 1e-6)
    }

    /**
     * The correction has to use the real field model, not a constant — and it has to move the
     * bearing in the direction the model says. Sydney's declination is strongly east, so a
     * magnetic bearing there reads well clockwise of the true one.
     */
    @Test
    fun usesTheFieldModelAtTheObserversPosition() {
        val sydney = GeoPos(-33.8688, 151.2093)
        val time = Instant.parse("2026-08-02T12:00:00Z")
        val declination = Wmm.declinationDeg(sydney, time)
        assertTrue(declination > 8.0, "expected a strongly eastward declination at Sydney, got $declination")

        val corrected = AttitudeFrames.toTrueNorth(pointingAt(0.0, 0.0), sydney, time)

        assertEquals(declination, AttitudeFrames.pointingAzimuthElevation(corrected).azimuthDeg, 1e-6)
    }

    @Test
    fun separationIsZeroWhenPointingStraightAtASatellite() {
        val topo = Topocentric(azimuthDeg = 143.0, elevationDeg = 27.0, rangeKm = 900.0, rangeRateKmS = -3.0)

        val separation = AttitudeFrames.angularSeparationDeg(pointingAt(143.0, 27.0), topo)

        assertTrue(separation < 1e-6, "expected 0, got $separation")
    }

    @Test
    fun separationGrowsWithPointingError() {
        val topo = Topocentric(azimuthDeg = 0.0, elevationDeg = 0.0, rangeKm = 900.0, rangeRateKmS = 0.0)

        assertEquals(30.0, AttitudeFrames.angularSeparationDeg(pointingAt(30.0, 0.0), topo), 1e-6)
        assertEquals(45.0, AttitudeFrames.angularSeparationDeg(pointingAt(0.0, 45.0), topo), 1e-6)
    }

    /**
     * Azimuth differences must take the short way round. A satellite passing north goes
     * 359° → 1°, and the naive difference is -358° instead of +2°.
     */
    @Test
    fun azimuthDeltaTakesTheShortArcAcrossNorth() {
        assertEquals(2.0, AttitudeFrames.azimuthDeltaDeg(359.0, 1.0), 1e-9)
        assertEquals(-2.0, AttitudeFrames.azimuthDeltaDeg(1.0, 359.0), 1e-9)
        assertEquals(10.0, AttitudeFrames.azimuthDeltaDeg(0.0, 10.0), 1e-9)
        assertEquals(180.0, AttitudeFrames.azimuthDeltaDeg(0.0, 180.0), 1e-9)
        assertEquals(-90.0, AttitudeFrames.azimuthDeltaDeg(45.0, 315.0), 1e-9)
    }

    private fun assertAzEl(
        actual: AzimuthElevation,
        azimuthDeg: Double,
        elevationDeg: Double,
        checkAzimuth: Boolean = true,
    ) {
        if (checkAzimuth) assertEquals(azimuthDeg, actual.azimuthDeg, 1e-9)
        assertEquals(elevationDeg, actual.elevationDeg, 1e-9)
    }

    private fun assertVector(expected: Vector3, actual: Vector3, message: String = "") {
        assertTrue(
            abs(expected.x - actual.x) < 1e-9 &&
                abs(expected.y - actual.y) < 1e-9 &&
                abs(expected.z - actual.z) < 1e-9,
            "$message expected $expected but was $actual",
        )
    }
}
