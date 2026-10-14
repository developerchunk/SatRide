package com.developerstring.satride.core.sensors

import com.developerstring.satride.core.orbit.math.Quaternion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One orientation reading.
 *
 * [rotation] maps device coordinates into ENU — see [AttitudeFrames] for what that means and why
 * every platform converts into it rather than out of it.
 *
 * [elapsedNanos] is a monotonic device timestamp, not a wall clock. The filter needs the interval
 * between samples, and a wall clock can step backwards when the network corrects it — which would
 * produce a negative interval and a filter that briefly runs in reverse.
 */
public data class AttitudeSample(val rotation: Quaternion, val elapsedNanos: Long)

/**
 * Which north an attitude is measured against.
 *
 * Kept explicit because the platforms differ and the correction is not idempotent: Android
 * reports magnetic north and needs declination applied, iOS reports true north and must be left
 * alone. A single boolean buried in an actual would make double-correction easy and invisible.
 */
public enum class NorthReference { MAGNETIC, TRUE }

/**
 * How much the compass can be trusted.
 *
 * A first-class state rather than a hidden detail: magnetometers are thrown off by anything
 * ferrous — a car, a desk, a phone case with a magnetic clasp — and when that happens the sky
 * view is simply wrong. Telling the user, and showing them the figure-of-eight gesture that
 * fixes it, is the difference between "this app is broken" and "ah, I need to move away from the
 * fridge".
 */
public enum class CompassAccuracy {
    /** Good enough for the AR view to be trusted. */
    HIGH,

    /** Usable, but worth suggesting calibration. */
    MEDIUM,

    /** Visibly wrong. The UI should say so. */
    LOW,

    /** No usable heading yet, or no magnetometer at all. */
    UNCALIBRATED,
    ;

    public val needsCalibration: Boolean get() = this == LOW || this == UNCALIBRATED
}

/**
 * Device orientation, in the ENU convention.
 *
 * The streams are cold, which is the whole power story: motion sensors are among the most
 * expensive things an app can leave running, and tying the sensor's lifetime to collection means
 * it stops the moment the sky view goes away. There is deliberately no `start`/`stop` pair to
 * forget to call.
 */
public interface AttitudeProvider {
    /** Smoothed orientation, device → ENU, true north. Cold: sampling begins on collection. */
    public val attitude: Flow<AttitudeSample>

    /** Raw, unsmoothed orientation. For the P5 debug overlay's raw-versus-filtered comparison. */
    public val rawAttitude: Flow<AttitudeSample>

    public val accuracy: StateFlow<CompassAccuracy>

    /** False on platforms or devices with no orientation sensing, so the UI can offer manual mode. */
    public val isAvailable: Boolean
}
