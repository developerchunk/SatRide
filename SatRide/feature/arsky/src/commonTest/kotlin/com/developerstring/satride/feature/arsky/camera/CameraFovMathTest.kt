package com.developerstring.satride.feature.arsky.camera

import com.developerstring.satride.feature.arsky.projection.CameraFov
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The camera's reported field of view is not the field of view on screen, and the difference is
 * large enough to matter. These cases pin the two crops between them.
 */
class CameraFovMathTest {

    @Test
    fun `sensor field of view follows the pinhole relation`() {
        // A typical phone main camera: 26 mm equivalent on a 1/1.7" sensor.
        val fov = CameraFovMath.fromSensor(focalLengthMm = 4.38, sensorWidthMm = 6.4, sensorHeightMm = 4.8)

        // 2·atan(3.2/4.38) = 72.303°, 2·atan(2.4/4.38) = 57.441°
        assertEquals(72.303, fov.horizontalDeg, 0.001)
        assertEquals(57.441, fov.verticalDeg, 0.001)
    }

    @Test
    fun `cropping to a taller shape narrows the width and leaves the height alone`() {
        val full = CameraFov(72.0, 57.0)
        val cropped = CameraFovMath.cropToAspect(full, targetAspect = 9.0 / 16.0)

        assertEquals(57.0, cropped.verticalDeg, 1e-9)
        assertTrue(cropped.horizontalDeg < full.horizontalDeg)
    }

    @Test
    fun `cropping to a wider shape narrows the height and leaves the width alone`() {
        val full = CameraFov(72.0, 57.0)
        val cropped = CameraFovMath.cropToAspect(full, targetAspect = 16.0 / 9.0)

        assertEquals(72.0, cropped.horizontalDeg, 1e-9)
        assertTrue(cropped.verticalDeg < full.verticalDeg)
    }

    @Test
    fun `cropping to the shape it already has changes nothing`() {
        val fov = CameraFovMath.fromSensor(4.38, 6.4, 4.8)
        val aspect = kotlin.math.tan(fov.horizontalDeg * kotlin.math.PI / 360.0) /
            kotlin.math.tan(fov.verticalDeg * kotlin.math.PI / 360.0)
        val cropped = CameraFovMath.cropToAspect(fov, aspect)

        assertEquals(fov.horizontalDeg, cropped.horizontalDeg, 1e-6)
        assertEquals(fov.verticalDeg, cropped.verticalDeg, 1e-6)
    }

    @Test
    fun `cropping never widens the view`() {
        val full = CameraFov(72.0, 57.0)

        listOf(0.2, 0.5625, 0.75, 1.0, 1.333, 1.777, 4.0).forEach { aspect ->
            val cropped = CameraFovMath.cropToAspect(full, aspect)
            assertTrue(
                cropped.horizontalDeg <= full.horizontalDeg + 1e-9 &&
                    cropped.verticalDeg <= full.verticalDeg + 1e-9,
                "aspect $aspect widened the view: $cropped",
            )
        }
    }

    @Test
    fun `cropping in tangent space — not angle space`() {
        // Halving the width halves the tangent of the half-angle, so a 90° view cropped to half
        // its width is 2·atan(0.5) = 53.13°, not 45°. Getting this wrong is a 8° error at the
        // edge of the frame — several times the app's entire error budget.
        val square = CameraFov(90.0, 90.0)
        val halved = CameraFovMath.cropToAspect(square, targetAspect = 0.5)

        assertEquals(53.13, halved.horizontalDeg, 0.01)
    }

    @Test
    fun `a portrait phone sees much less than the sensor's horizontal field of view`() {
        // The case that actually ships: a 4:3 sensor, a 4:3 preview stream, shown rotated on a
        // 1080x2400 screen.
        val visible = CameraFovMath.visibleFov(
            sensorFov = CameraFovMath.fromSensor(4.38, 6.4, 4.8),
            streamAspect = 4.0 / 3.0,
            quarterTurns = 1,
            viewAspect = 1080.0 / 2400.0,
        )

        // Rotated, the sensor's short axis becomes the horizontal one, and filling a screen far
        // taller than 3:4 crops it further still: 72.3° of sensor becomes 36.4° on screen. A
        // projection fed the raw sensor figure would draw the sky twice as wide as it is.
        assertEquals(36.398, visible.horizontalDeg, 0.001)
        assertEquals(72.303, visible.verticalDeg, 0.001)
        assertTrue(CameraFovMath.isPlausible(visible))
    }

    @Test
    fun `a landscape phone keeps the sensor's wide axis horizontal`() {
        val visible = CameraFovMath.visibleFov(
            sensorFov = CameraFovMath.fromSensor(4.38, 6.4, 4.8),
            streamAspect = 4.0 / 3.0,
            quarterTurns = 0,
            viewAspect = 2400.0 / 1080.0,
        )

        assertEquals(72.303, visible.horizontalDeg, 0.001)
        assertEquals(36.398, visible.verticalDeg, 0.001)
    }

    @Test
    fun `transposing swaps the axes`() {
        val transposed = CameraFovMath.transpose(CameraFov(72.0, 57.0))

        assertEquals(57.0, transposed.horizontalDeg, 1e-9)
        assertEquals(72.0, transposed.verticalDeg, 1e-9)
    }

    @Test
    fun `implausible hardware readings are rejected rather than projected through`() {
        assertFalse(CameraFovMath.isPlausible(CameraFov(2.0, 3.0)), "a telephoto-only reading")
        assertFalse(CameraFovMath.isPlausible(CameraFov(170.0, 170.0)), "a fisheye reading")
        assertTrue(CameraFovMath.isPlausible(CameraFov(67.0, 100.0)))
    }

    @Test
    fun `a degenerate aspect ratio is ignored rather than producing a NaN sky`() {
        val fov = CameraFov(72.0, 57.0)

        assertEquals(fov, CameraFovMath.cropToAspect(fov, 0.0))
        assertEquals(fov, CameraFovMath.cropToAspect(fov, Double.NaN))
        assertEquals(fov, CameraFovMath.cropToAspect(fov, -1.0))
    }
}
