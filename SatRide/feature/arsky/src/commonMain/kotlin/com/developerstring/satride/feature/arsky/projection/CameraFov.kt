package com.developerstring.satride.feature.arsky.projection

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.tan

/**
 * The angular size of what the camera can see.
 *
 * Queried from the hardware rather than assumed, because this single number decides whether a
 * glyph lands on the satellite or several degrees away from it. Phone cameras range from about
 * 60° to 80° horizontally, and guessing the middle of that range would put a target at the edge
 * of the frame wrong by roughly ten degrees — far larger than every other error in the app
 * combined.
 *
 * Both angles are full angles, not half-angles.
 */
data class CameraFov(val horizontalDeg: Double, val verticalDeg: Double) {

    init {
        require(horizontalDeg > 0.0 && horizontalDeg < 180.0) {
            "horizontal FOV must be in (0, 180), was $horizontalDeg"
        }
        require(verticalDeg > 0.0 && verticalDeg < 180.0) {
            "vertical FOV must be in (0, 180), was $verticalDeg"
        }
    }

    companion object {
        /**
         * What the sky is drawn through when there is no camera to ask.
         *
         * Planetarium mode has no lens, so it picks its own field of view; this is the middle of
         * the range real phone cameras occupy, which makes the two modes look like the same sky.
         */
        val PLANETARIUM_DEFAULT: CameraFov = CameraFov(horizontalDeg = 67.0, verticalDeg = 100.0)

        /** Narrowest and widest the planetarium's pinch gesture may go. */
        const val MIN_FOV_DEG: Double = 30.0
        const val MAX_FOV_DEG: Double = 90.0

        /**
         * Derives the vertical field of view from the horizontal one and the viewport shape.
         *
         * A camera reports one FOV figure for its active format; the other axis follows from the
         * aspect ratio through the pinhole relation — *not* by scaling the angle directly.
         * `vertical = 2·atan(tan(horizontal/2) · height/width)` is exact; multiplying the angle by
         * the aspect ratio is the obvious shortcut and is wrong by several degrees on a tall
         * phone screen, always in the direction that stretches the sky.
         */
        fun fromHorizontal(horizontalDeg: Double, widthPx: Float, heightPx: Float): CameraFov {
            if (widthPx <= 0f || heightPx <= 0f) return CameraFov(horizontalDeg, horizontalDeg)
            val halfHorizontal = horizontalDeg * DEG_TO_RAD / 2.0
            val halfVertical = atan(tan(halfHorizontal) * (heightPx / widthPx))
            return CameraFov(horizontalDeg, halfVertical * 2.0 * RAD_TO_DEG)
        }

        /** As [fromHorizontal], but starting from the vertical angle. */
        fun fromVertical(verticalDeg: Double, widthPx: Float, heightPx: Float): CameraFov {
            if (widthPx <= 0f || heightPx <= 0f) return CameraFov(verticalDeg, verticalDeg)
            val halfVertical = verticalDeg * DEG_TO_RAD / 2.0
            val halfHorizontal = atan(tan(halfVertical) * (widthPx / heightPx))
            return CameraFov(halfHorizontal * 2.0 * RAD_TO_DEG, verticalDeg)
        }
    }
}

internal const val DEG_TO_RAD: Double = PI / 180.0
internal const val RAD_TO_DEG: Double = 180.0 / PI
