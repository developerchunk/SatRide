package com.developerstring.satride.core.sensors

import com.developerstring.satride.core.orbit.math.Quaternion
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import platform.CoreMotion.CMAttitudeReferenceFrameXTrueNorthZVertical
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo

/**
 * Device orientation from CoreMotion.
 *
 * `.xTrueNorthZVertical` asks CoreMotion for both the sensor fusion *and* the true-north
 * correction, so iOS needs no declination step — the WMM model in `core:orbit` is used on Android
 * only. Requesting it does require the magnetometer to have a heading, which is why an
 * uncalibrated compass shows up here as no samples at all rather than as wrong ones.
 *
 * CoreMotion's reference frame is north-west-up, not east-north-up, despite the constant naming
 * only two axes. [AttitudeFrames.nwuToEnu] is the quarter-turn that fixes it; without it the sky
 * is rotated 90 degrees, which reads as "the compass is broken" rather than as a frame error.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAttitudeSource : PlatformAttitudeSource {

    private val motionManager = CMMotionManager()

    override val northReference: NorthReference = NorthReference.TRUE

    override val isAvailable: Boolean
        get() = motionManager.isDeviceMotionAvailable()

    override fun samples(): Flow<RawAttitudeSample> = callbackFlow {
        if (!motionManager.isDeviceMotionAvailable()) {
            close()
            return@callbackFlow
        }

        // 50 Hz, matching the Android side so the filter constants mean the same thing on both.
        motionManager.deviceMotionUpdateInterval = 1.0 / 50.0

        motionManager.startDeviceMotionUpdatesUsingReferenceFrame(
            referenceFrame = CMAttitudeReferenceFrameXTrueNorthZVertical,
            toQueue = NSOperationQueue(),
        ) { motion, _ ->
            val attitude = motion?.attitude ?: return@startDeviceMotionUpdatesUsingReferenceFrame

            val nwu = attitude.quaternion.useContents {
                Quaternion(w = w, x = x, y = y, z = z)
            }

            trySend(
                RawAttitudeSample(
                    rotation = AttitudeFrames.nwuToEnu(nwu),
                    // systemUptime is monotonic, matching the contract on AttitudeSample —
                    // a wall clock can step and would give the filter a negative interval.
                    elapsedNanos = (NSProcessInfo.processInfo.systemUptime * 1_000_000_000.0).toLong(),
                    // A struct by value, so it has to be read through useContents.
                    accuracy = motion.magneticField.useContents { accuracyFrom(accuracy) },
                ),
            )
        }

        awaitClose { motionManager.stopDeviceMotionUpdates() }
    }
        // Newest wins; see the Android source for why orientation is a value and not a stream.
        .buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * CoreMotion reports calibration as `CMMagneticFieldCalibrationAccuracy`, which is
     * −1 uncalibrated, 0 low, 1 medium, 2 high — the same ladder Android uses, so it maps
     * straight onto the shared enum.
     */
    private fun accuracyFrom(raw: Int): CompassAccuracy = when (raw) {
        2 -> CompassAccuracy.HIGH
        1 -> CompassAccuracy.MEDIUM
        0 -> CompassAccuracy.LOW
        else -> CompassAccuracy.UNCALIBRATED
    }
}
