package com.developerstring.satride.core.sensors

import com.developerstring.satride.core.common.MutableClock
import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.ObserverPosition
import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.geomag.Wmm
import com.developerstring.satride.core.orbit.math.Quaternion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The north correction, which has to happen exactly once.
 *
 * Applying declination on a platform that already reports true north doubles the error rather
 * than removing it — and because declination is near zero across much of Europe and North America,
 * that bug would look fine precisely where it was most likely to be tried.
 */
class DefaultAttitudeProviderTest {

    private val sydney = GeoPos(-33.8688, 151.2093)
    private val time = Instant.parse("2026-08-02T12:00:00Z")
    private val clock = MutableClock(time)

    private class FakeSource(
        override val northReference: NorthReference,
        private val samples: List<RawAttitudeSample>,
        override val isAvailable: Boolean = true,
    ) : PlatformAttitudeSource {
        override fun samples(): Flow<RawAttitudeSample> = flowOf(*samples.toTypedArray())
    }

    private class FakeLocation(position: ObserverPosition) : LocationProvider {
        override val position: StateFlow<ObserverPosition> = MutableStateFlow(position)
        override suspend fun requestFix() = Unit
        override fun setManual(position: GeoPos?) = Unit
    }

    /** Attitude with the camera pointing at the horizon on the given bearing. */
    private fun pointingAt(azimuthDeg: Double): Quaternion {
        val forward = AttitudeFrames.directionOf(azimuthDeg, 0.0)
        val axis = AttitudeFrames.CAMERA_FORWARD cross forward
        val dot = (AttitudeFrames.CAMERA_FORWARD dot forward).coerceIn(-1.0, 1.0)
        return Quaternion.fromAxisAngle(axis, kotlin.math.acos(dot))
    }

    private fun TestScope.provider(reference: NorthReference, position: ObserverPosition, rotation: Quaternion) =
        DefaultAttitudeProvider(
            source = FakeSource(reference, listOf(RawAttitudeSample(rotation, 0L, CompassAccuracy.HIGH))),
            locationProvider = FakeLocation(position),
            clock = clock,
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )

    @Test
    fun appliesDeclinationToAMagneticReferencedPlatform() = runTest {
        val declination = Wmm.declinationDeg(sydney, time)
        assertTrue(declination > 8.0, "expected a strongly eastward declination at Sydney: $declination")

        val provider = provider(NorthReference.MAGNETIC, ObserverPosition.Manual(sydney), pointingAt(0.0))

        val sample = provider.attitude.first()
        assertEquals(
            declination,
            AttitudeFrames.pointingAzimuthElevation(sample.rotation).azimuthDeg,
            1e-6,
        )
    }

    /** CoreMotion has already done this. Doing it again is the bug. */
    @Test
    fun leavesATrueNorthReferencedPlatformAlone() = runTest {
        val provider = provider(NorthReference.TRUE, ObserverPosition.Manual(sydney), pointingAt(0.0))

        val sample = provider.attitude.first()

        assertEquals(0.0, AttitudeFrames.pointingAzimuthElevation(sample.rotation).azimuthDeg, 1e-6)
    }

    /**
     * Declination is unknowable without a position, and magnetic north is a better answer than
     * refusing to draw: it is within a few degrees of true north across most of the populated
     * world, and the alternative is an empty sky.
     */
    @Test
    fun fallsBackToMagneticNorthWhileThePositionIsUnknown() = runTest {
        val provider = provider(NorthReference.MAGNETIC, ObserverPosition.Unknown, pointingAt(0.0))

        val sample = provider.attitude.first()

        assertEquals(0.0, AttitudeFrames.pointingAzimuthElevation(sample.rotation).azimuthDeg, 1e-6)
    }

    @Test
    fun reportsCompassAccuracyFromThePlatform() = runTest {
        val provider = DefaultAttitudeProvider(
            source = FakeSource(
                NorthReference.TRUE,
                listOf(RawAttitudeSample(Quaternion.IDENTITY, 0L, CompassAccuracy.LOW)),
            ),
            locationProvider = FakeLocation(ObserverPosition.Unknown),
            clock = clock,
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )

        assertEquals(CompassAccuracy.UNCALIBRATED, provider.accuracy.value)
        provider.attitude.first()
        assertEquals(CompassAccuracy.LOW, provider.accuracy.value)
        assertTrue(provider.accuracy.value.needsCalibration)
    }

    @Test
    fun reportsWhenThePlatformHasNoOrientationSensing() = runTest {
        val provider = DefaultAttitudeProvider(
            source = FakeSource(NorthReference.TRUE, emptyList(), isAvailable = false),
            locationProvider = FakeLocation(ObserverPosition.Unknown),
            clock = clock,
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )

        assertTrue(!provider.isAvailable)
    }

    /** A quarter turn is the one error a hurried reading of the frame docs would produce. */
    @Test
    fun theCorrectionIsDeclinationSizedRatherThanAQuarterTurn() = runTest {
        val provider = provider(NorthReference.MAGNETIC, ObserverPosition.Manual(sydney), pointingAt(90.0))

        val azimuth = AttitudeFrames.pointingAzimuthElevation(provider.attitude.first().rotation).azimuthDeg

        assertTrue(azimuth in 95.0..115.0, "expected roughly 90 + declination, got $azimuth")
    }

    @Test
    fun theCorrectionMatchesTheDocumentedFormula() = runTest {
        val raw = pointingAt(217.0)
        val provider = provider(NorthReference.MAGNETIC, ObserverPosition.Manual(sydney), raw)

        val corrected = provider.attitude.first().rotation
        val expected = Quaternion.fromAxisAngle(
            AttitudeFrames.UP,
            -Wmm.declinationDeg(sydney, time) * Constants.DEG_TO_RAD,
        ) * raw

        // 1e-6 rad, not something tighter: angleTo goes through acos, whose derivative is
        // unbounded at zero, so comparing two near-identical rotations has a noise floor around
        // 1e-8 rad in double precision. That is six thousandths of an arcsecond — irrelevant next
        // to several degrees of compass error, but it does mean the assertion cannot be tighter.
        assertTrue(
            corrected.angleTo(expected) < 1e-6,
            "differed by ${corrected.angleTo(expected)} rad",
        )
    }
}
