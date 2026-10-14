package com.developerstring.satride.feature.arsky.planetarium

import com.developerstring.satride.core.orbit.math.Quaternion
import com.developerstring.satride.core.orbit.math.Vector3
import com.developerstring.satride.core.sensors.AttitudeFrames
import com.developerstring.satride.feature.arsky.projection.CameraFov
import kotlin.math.abs
import kotlin.math.exp

/**
 * A camera the user drags, for when there is no phone to point.
 *
 * ## Why this is not a lesser mode
 *
 * It is the only mode that works indoors, on a simulator, on a device with no compass, and for
 * anyone who declines the camera permission — and it is what the demo video is recorded in,
 * because a screen recording of an AR view is a recording of a ceiling. So it drives the same
 * renderer through the same projector as the real thing: the only difference is where the
 * attitude comes from.
 *
 * ## What it produces
 *
 * An attitude in exactly the convention `AttitudeFrames` defines — device coordinates into ENU —
 * so nothing downstream can tell which mode it is in.
 *
 * Mutable and driven from gesture callbacks, so it allocates only the quaternion it hands back.
 */
class VirtualCamera(
    initialAzimuthDeg: Double = 0.0,
    initialElevationDeg: Double = 20.0,
    initialFovDeg: Double = CameraFov.PLANETARIUM_DEFAULT.horizontalDeg,
) {

    /** Where the virtual camera looks, as a compass bearing. */
    var azimuthDeg: Double = initialAzimuthDeg
        private set

    /**
     * How far above the horizon it looks.
     *
     * Clamped just short of the poles. At exactly ±90° the up-vector is undefined and the view
     * spins on its own axis for no input — the classic gimbal problem, and one that a user
     * dragging towards the zenith would hit within a second.
     */
    var elevationDeg: Double = initialElevationDeg
        private set

    var fovDeg: Double = initialFovDeg
        private set

    private var azimuthVelocity = 0.0
    private var elevationVelocity = 0.0

    /** The attitude to hand the projector. */
    fun attitude(): Quaternion {
        // Yaw about Up, then pitch about the camera's own right-hand axis. Composed in this order
        // so that dragging up always tilts towards the zenith regardless of which way the camera
        // is facing — the other order tilts about a fixed world axis, which feels like the sky is
        // rolling rather than the head is turning.
        val yaw = Quaternion.fromAxisAngle(AttitudeFrames.UP, -azimuthDeg * DEG_TO_RAD)
        val pitch = Quaternion.fromAxisAngle(RIGHT, elevationDeg * DEG_TO_RAD)
        return yaw * BASE * pitch
    }

    /**
     * Drags the view.
     *
     * Sign is inverted relative to the finger on both axes: the user is dragging the *sky*, not
     * the camera, so pulling left brings what was on the left into the middle. Getting this
     * backwards is the single most common complaint about pan controls.
     *
     * @param dxPixels finger movement, right positive
     * @param dyPixels finger movement, down positive
     * @param widthPx viewport width, so a drag across the screen turns by about a screen's width
     *   of sky whatever the field of view happens to be
     */
    fun drag(dxPixels: Float, dyPixels: Float, widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        val degreesPerPixelX = fovDeg / widthPx
        val degreesPerPixelY = verticalFovDeg(widthPx, heightPx) / heightPx

        azimuthDeg = normaliseAzimuth(azimuthDeg - dxPixels * degreesPerPixelX)
        elevationDeg = (elevationDeg + dyPixels * degreesPerPixelY).coerceIn(MIN_ELEVATION, MAX_ELEVATION)
    }

    /** Records a fling, in pixels per second, to be spent by [settle]. */
    fun fling(velocityX: Float, velocityY: Float, widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        azimuthVelocity = -velocityX * (fovDeg / widthPx)
        elevationVelocity = velocityY * (verticalFovDeg(widthPx, heightPx) / heightPx)
    }

    /**
     * Advances inertia by [deltaSeconds].
     *
     * Exponential decay rather than a linear ramp, because that is what a flick of a real object
     * does and what every other pannable surface on both platforms does. A linear stop is
     * immediately noticeable as wrong even by people who could not say why.
     *
     * @return true while still moving, so the caller can stop asking
     */
    fun settle(deltaSeconds: Float): Boolean {
        if (!isMoving) return false

        val decay = exp(-DECAY_PER_SECOND * deltaSeconds).toDouble()

        // The exact integral of an exponentially decaying velocity over this step,
        // `∫v·e^(-kt)dt = v(1 - e^(-k·dt))/k`, rather than `velocity × dt`.
        //
        // This is what makes the fling framerate-independent, and the naive form is not: stepping
        // by `velocity × dt` and then decaying overshoots by about 5% at 30 Hz against 120 Hz,
        // because it spends the whole step at the velocity it started with. Five percent of a
        // flick is a degree or two of sky — small, but it means the same gesture lands somewhere
        // different on a 60 Hz phone than on a 120 Hz one.
        val travel = (1.0 - decay) / DECAY_PER_SECOND

        azimuthDeg = normaliseAzimuth(azimuthDeg + azimuthVelocity * travel)
        elevationDeg = (elevationDeg + elevationVelocity * travel)
            .coerceIn(MIN_ELEVATION, MAX_ELEVATION)

        azimuthVelocity *= decay
        elevationVelocity *= decay

        // Hitting the elevation limit should stop the fling rather than let it grind silently
        // against the clamp for a second.
        if (elevationDeg <= MIN_ELEVATION || elevationDeg >= MAX_ELEVATION) elevationVelocity = 0.0

        if (!isMoving) {
            azimuthVelocity = 0.0
            elevationVelocity = 0.0
            return false
        }
        return true
    }

    val isMoving: Boolean
        get() = abs(azimuthVelocity) > STOP_THRESHOLD_DEG_S || abs(elevationVelocity) > STOP_THRESHOLD_DEG_S

    /** Pinch to zoom, as a scale factor. Above 1 zooms in, which *narrows* the field of view. */
    fun zoom(scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        fovDeg = (fovDeg / scaleFactor).coerceIn(CameraFov.MIN_FOV_DEG, CameraFov.MAX_FOV_DEG)
    }

    /** Stops any fling, for when the user puts a finger down again. */
    fun stop() {
        azimuthVelocity = 0.0
        elevationVelocity = 0.0
    }

    /** Points the view at a given part of the sky, for "show me this satellite". */
    fun lookAt(azimuthDeg: Double, elevationDeg: Double) {
        stop()
        this.azimuthDeg = normaliseAzimuth(azimuthDeg)
        this.elevationDeg = elevationDeg.coerceIn(MIN_ELEVATION, MAX_ELEVATION)
    }

    /** The field of view for a viewport of this shape. */
    fun fov(widthPx: Float, heightPx: Float): CameraFov = CameraFov.fromHorizontal(fovDeg, widthPx, heightPx)

    private fun verticalFovDeg(widthPx: Float, heightPx: Float): Double = fov(widthPx, heightPx).verticalDeg

    private fun normaliseAzimuth(value: Double): Double = (value % 360.0 + 360.0) % 360.0

    companion object {
        /**
         * The attitude of a phone held upright facing north — the frame the yaw and pitch are
         * applied to. See `SkyProjector.UPRIGHT_FACING_NORTH` for why identity is not this.
         */
        private val RIGHT = Vector3(1.0, 0.0, 0.0)
        private val BASE = Quaternion.fromAxisAngle(RIGHT, kotlin.math.PI / 2.0)

        /** Just short of the zenith and nadir, to stay out of the gimbal singularity. */
        const val MAX_ELEVATION: Double = 89.0
        const val MIN_ELEVATION: Double = -89.0

        /** Velocity falls to about 2% of its initial value in a second. */
        private const val DECAY_PER_SECOND = 4.0f

        /** Below a degree a second the motion is not perceptible; stopping avoids endless frames. */
        private const val STOP_THRESHOLD_DEG_S = 1.0
    }
}

private const val DEG_TO_RAD: Double = kotlin.math.PI / 180.0
