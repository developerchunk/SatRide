package com.developerstring.satride.feature.arsky.camera

import com.developerstring.satride.feature.arsky.projection.CameraFov
import com.developerstring.satride.feature.arsky.projection.DEG_TO_RAD
import com.developerstring.satride.feature.arsky.projection.RAD_TO_DEG
import kotlin.math.atan
import kotlin.math.tan

/**
 * How a lens's field of view survives the trip to the screen.
 *
 * The number a camera reports describes its *sensor*, and almost nothing about a phone's AR view
 * uses the whole sensor. The image is cropped twice before the user sees it — once when the
 * capture pipeline picks a preview stream with its own aspect ratio, and again when that stream
 * is scaled to fill a screen of a different shape — and each crop narrows the angle actually on
 * display. Projecting with the raw sensor figure draws a sky perhaps 15% too wide, which puts a
 * satellite near the edge of the frame several degrees from where it really is.
 *
 * Kept here as pure functions on angles, rather than inside either platform's camera code,
 * because it is the same arithmetic on both and it is the part that is easy to get quietly wrong.
 */
internal object CameraFovMath {

    /**
     * Field of view of a complete sensor, from the physical dimensions the camera reports.
     *
     * The pinhole relation: an object at the edge of the sensor subtends `atan(halfWidth/focal)`
     * at the lens.
     */
    fun fromSensor(focalLengthMm: Double, sensorWidthMm: Double, sensorHeightMm: Double): CameraFov {
        require(focalLengthMm > 0.0) { "focal length must be positive, was $focalLengthMm" }
        return CameraFov(
            horizontalDeg = 2.0 * atan(sensorWidthMm / (2.0 * focalLengthMm)) * RAD_TO_DEG,
            verticalDeg = 2.0 * atan(sensorHeightMm / (2.0 * focalLengthMm)) * RAD_TO_DEG,
        )
    }

    /**
     * The field of view left after centre-cropping an image to [targetAspect] (width ÷ height).
     *
     * Cropping never widens: the axis that already matches is kept and the other is cut back. The
     * arithmetic happens in tangent space, because that is where the pinhole model is linear —
     * cropping half the width halves the *tangent* of the half-angle, not the angle.
     */
    fun cropToAspect(fov: CameraFov, targetAspect: Double): CameraFov {
        if (targetAspect <= 0.0 || !targetAspect.isFinite()) return fov

        val tanH = tan(fov.horizontalDeg * DEG_TO_RAD / 2.0)
        val tanV = tan(fov.verticalDeg * DEG_TO_RAD / 2.0)
        val currentAspect = tanH / tanV

        return if (targetAspect > currentAspect) {
            // Wanted wider than we have: width is the limit, so height gets cut.
            CameraFov(fov.horizontalDeg, 2.0 * atan(tanH / targetAspect) * RAD_TO_DEG)
        } else {
            CameraFov(2.0 * atan(tanV * targetAspect) * RAD_TO_DEG, fov.verticalDeg)
        }
    }

    /** Swaps the two axes, for a preview rotated a quarter turn relative to the sensor. */
    fun transpose(fov: CameraFov): CameraFov = CameraFov(fov.verticalDeg, fov.horizontalDeg)

    /**
     * The field of view a user is actually looking at.
     *
     * Applies the whole chain: sensor → the preview stream's aspect → the rotation between sensor
     * and display → the crop imposed by scaling that stream to fill the view.
     *
     * @param sensorFov the full sensor's field of view, in sensor orientation
     * @param streamAspect the resolved preview stream's width ÷ height, in sensor orientation
     * @param quarterTurns how far the preview is rotated to match the display; odd values swap axes
     * @param viewAspect the on-screen viewport's width ÷ height
     */
    fun visibleFov(sensorFov: CameraFov, streamAspect: Double, quarterTurns: Int, viewAspect: Double): CameraFov {
        val stream = cropToAspect(sensorFov, streamAspect)
        val oriented = if (quarterTurns % 2 == 0) stream else transpose(stream)
        // A preview scaled to fill the view keeps its centre and loses whatever overflows, so
        // the visible region is the largest rectangle of the view's shape inside the stream.
        return cropToAspect(oriented, viewAspect)
    }

    /**
     * Sanity band for a value that came from the hardware.
     *
     * Some devices report a focal length of zero, a physical sensor size of zero, or a field of
     * view for a lens that is not the one being previewed. A wrong FOV is not a visibly broken
     * camera — it is a sky that is subtly and consistently misaligned, which is far harder to
     * diagnose than a black screen. Anything outside this band is discarded in favour of the
     * planetarium default.
     */
    fun isPlausible(fov: CameraFov): Boolean = fov.horizontalDeg in MIN_PLAUSIBLE_DEG..MAX_PLAUSIBLE_DEG &&
        fov.verticalDeg in MIN_PLAUSIBLE_DEG..MAX_PLAUSIBLE_DEG

    /** Narrower than any phone's main camera; below this something has been misreported. */
    private const val MIN_PLAUSIBLE_DEG: Double = 20.0

    /** Wider than any phone's main camera, with room for an ultra-wide the user has selected. */
    private const val MAX_PLAUSIBLE_DEG: Double = 140.0
}
