package com.developerstring.satride.feature.play.multiplay

import com.developerstring.satride.core.orbit.math.Quaternion
import com.developerstring.satride.core.orbit.math.Vector3
import com.developerstring.satride.feature.play.multiplay.engine.RemoteShip
import com.developerstring.satride.feature.play.multiplay.engine.SphereMath
import com.developerstring.satride.feature.play.multiplay.input.TiltController
import com.developerstring.satride.feature.play.multiplay.net.RoomCodes
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiPlayUnitsTest {

    @Test
    fun roomCodesNormaliseAndValidate() {
        assertEquals("ABC234", RoomCodes.normalize("abc-234"))
        assertTrue(RoomCodes.isValid(RoomCodes.generate()))
        assertFalse(RoomCodes.isValid("ABC10"), "0 and 1 are not in the alphabet")
        assertEquals(6, RoomCodes.normalize("ABCDEFGHJK").length)
    }

    private fun freshTilt(): TiltController = TiltController().apply {
        absoluteSmoothingS = 1e-6
        steerDeadZoneDeg = 0.0
        smoothThresholdDegS = 0.0
        tighteningDegS = 0.0
        lookReturnS = 0.0
        lookSensitivity = 1.0
    }

    /** Feeds a rotation in [steps] samples 20 ms apart, as the sensor would deliver it. */
    private fun sweep(
        tilt: TiltController,
        from: Quaternion,
        axis: Vector3,
        degrees: Double,
        steps: Int,
        startNanos: Long,
    ) {
        for (i in 1..steps) {
            val q = from * Quaternion.fromAxisAngle(axis, degrees * i / steps * PI / 180.0)
            tilt.update(q, startNanos + i * 20_000_000L)
        }
    }

    @Test
    fun rollingThePhoneLikeAWheelReadsAsAbsoluteSteering() {
        // Device held upright facing the user: device +Y is world up, so q = Rx(+90°).
        val upright = Quaternion.fromAxisAngle(Vector3(1.0, 0.0, 0.0), PI / 2)
        val tilt = freshTilt()
        tilt.calibrate(upright)
        tilt.update(upright, 1_000_000L)
        assertEquals(0.0, tilt.rollDeg, 1e-6)

        // Clockwise as the user sees it: −20° about the screen normal.
        val rolled = upright * Quaternion.fromAxisAngle(Vector3(0.0, 0.0, 1.0), -20.0 * PI / 180.0)
        tilt.update(rolled, 2_000_000L)
        assertEquals(20.0, tilt.rollDeg, 0.5, "clockwise roll should read positive")
        assertTrue(tilt.steer > 0.4 && tilt.steer <= 1.0, "steer=${tilt.steer}")
        assertEquals(0.0, tilt.pitchDeg, 0.5)
    }

    @Test
    fun turningThePhoneIntegratesIntoTheLookInThatDirection() {
        val upright = Quaternion.fromAxisAngle(Vector3(1.0, 0.0, 0.0), PI / 2)
        val tilt = freshTilt()
        tilt.calibrate(upright)
        tilt.update(upright, 1_000_000L)

        // Turn to face the user's right: −12° about the screen's vertical axis (device +Y).
        sweep(tilt, upright, Vector3(0.0, 1.0, 0.0), -12.0, steps = 6, startNanos = 1_000_000L)
        assertEquals(12.0, tilt.lookYawDeg, 1.0, "turning right should look right")
        assertEquals(0.0, tilt.lookPitchDeg, 1.0)

        // Tip the top edge toward the user: +10° about the screen's horizontal axis (device +X).
        val turned = upright * Quaternion.fromAxisAngle(Vector3(0.0, 1.0, 0.0), -12.0 * PI / 180.0)
        sweep(tilt, turned, Vector3(1.0, 0.0, 0.0), 10.0, steps = 5, startNanos = 200_000_000L)
        assertEquals(10.0, tilt.lookPitchDeg, 1.0, "tipping toward the player should look up")
        assertEquals(12.0, tilt.lookYawDeg, 1.5)
    }

    @Test
    fun theLookReturnsHomeWhenThePhoneIsStill() {
        val upright = Quaternion.fromAxisAngle(Vector3(1.0, 0.0, 0.0), PI / 2)
        val tilt = freshTilt().apply {
            lookReturnS = 0.5
            tighteningDegS = 2.0
        }
        tilt.calibrate(upright)
        tilt.update(upright, 1_000_000L)
        sweep(tilt, upright, Vector3(0.0, 1.0, 0.0), -12.0, steps = 6, startNanos = 1_000_000L)
        assertTrue(tilt.lookYawDeg > 8.0, "look should have moved: ${tilt.lookYawDeg}")
        val held = upright * Quaternion.fromAxisAngle(Vector3(0.0, 1.0, 0.0), -12.0 * PI / 180.0)
        // Two seconds of stillness at 50 Hz.
        for (i in 1..100) tilt.update(held, 200_000_000L + i * 20_000_000L)
        assertTrue(abs(tilt.lookYawDeg) < 1.0, "look should relax home: ${tilt.lookYawDeg}")
    }

    @Test
    fun tiltIsIndependentOfWhichDeviceAxisIsScreenUp() {
        // The same physical pose with the device rotated 90° about its normal (landscape): the
        // screen-up axis is now device +X, and the controller must find it from gravity alone.
        val landscape = Quaternion.fromAxisAngle(Vector3(1.0, 0.0, 0.0), PI / 2) *
            Quaternion.fromAxisAngle(Vector3(0.0, 0.0, 1.0), PI / 2)
        val tilt = freshTilt()
        tilt.calibrate(landscape)
        tilt.update(landscape, 1_000_000L)
        val rolled = landscape * Quaternion.fromAxisAngle(Vector3(0.0, 0.0, 1.0), -10.0 * PI / 180.0)
        tilt.update(rolled, 2_000_000L)
        assertEquals(10.0, tilt.rollDeg, 0.5)
        // Back to neutral, then a turn to the right: clockwise about the world's vertical, which
        // in this pose is device +X (the Rz(90°) took device +X to world up).
        tilt.update(landscape, 50_000_000L)
        sweep(tilt, landscape, Vector3(1.0, 0.0, 0.0), -8.0, steps = 4, startNanos = 100_000_000L)
        assertEquals(8.0, tilt.lookYawDeg, 1.0, "landscape right turn should look right")
    }

    @Test
    fun remoteShipsDeadReckonAlongTheirHeading() {
        val remote = RemoteShip("x")
        remote.applySample(
            latDeg = 0.0, lonDeg = 0.0, altKm = 600.0, headingDeg = 90.0, speedKmS = 200.0,
            rollRad = 0.0, boosting = false, sentAtMs = 0L, receivedAtMs = 0L,
        )
        // One second later with no new sample: the craft should have moved ~200 km east.
        repeat(60) { remote.update(nowMs = (it + 1) * 16L, dt = 0.016) }
        val lon = SphereMath.longitudeDeg(remote.ship.pos)
        assertTrue(lon > 0.5 && lon < 3.0, "expected eastward motion, longitude=$lon")
        assertEquals(0.0, SphereMath.latitudeDeg(remote.ship.pos), 0.2)
    }

    @Test
    fun headingRoundTrips() {
        val p = DoubleArray(3)
        val t = DoubleArray(3)
        SphereMath.fromLatLon(35.0, -120.0, p)
        for (h in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 359.0)) {
            SphereMath.tangentFromHeading(p, h, t)
            assertEquals(h, SphereMath.headingDeg(p, t), 1e-6)
        }
    }
}
