package com.developerstring.satride.feature.arsky.planetarium

import com.developerstring.satride.core.sensors.AttitudeFrames
import com.developerstring.satride.feature.arsky.projection.CameraFov
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualCameraTest {

    private val width = 1080f
    private val height = 2400f

    @Test
    fun `the attitude points where the camera says it does`() {
        // The whole contract: whatever azimuth and elevation the controller holds, the quaternion
        // it produces must make the projector agree. If these ever diverge, planetarium mode and
        // AR mode are drawing different skies.
        listOf(0.0 to 0.0, 90.0 to 30.0, 200.0 to -20.0, 355.0 to 75.0).forEach { (az, el) ->
            val camera = VirtualCamera(initialAzimuthDeg = az, initialElevationDeg = el)
            val pointing = AttitudeFrames.pointingAzimuthElevation(camera.attitude())

            assertEquals(az, pointing.azimuthDeg, 1e-6, "azimuth for ($az, $el)")
            assertEquals(el, pointing.elevationDeg, 1e-6, "elevation for ($az, $el)")
        }
    }

    @Test
    fun `dragging left brings the sky from the left into view`() {
        val camera = VirtualCamera(initialAzimuthDeg = 90.0)

        // Finger moves right; the sky follows the finger, so the camera turns to a lower bearing.
        camera.drag(dxPixels = 100f, dyPixels = 0f, widthPx = width, heightPx = height)

        assertTrue(camera.azimuthDeg < 90.0, "dragging right must turn the view left, was ${camera.azimuthDeg}")
    }

    @Test
    fun `dragging down looks higher up`() {
        val camera = VirtualCamera(initialElevationDeg = 20.0)

        camera.drag(dxPixels = 0f, dyPixels = 100f, widthPx = width, heightPx = height)

        assertTrue(camera.elevationDeg > 20.0, "pulling the sky down should raise the view")
    }

    @Test
    fun `a drag across the screen turns by about the field of view`() {
        val camera = VirtualCamera(initialAzimuthDeg = 180.0, initialFovDeg = 60.0)

        camera.drag(dxPixels = -width, dyPixels = 0f, widthPx = width, heightPx = height)

        assertEquals(240.0, camera.azimuthDeg, 0.5, "a full-width drag should turn by one FOV")
    }

    @Test
    fun `the view cannot be dragged past the zenith`() {
        val camera = VirtualCamera(initialElevationDeg = 80.0)

        repeat(20) { camera.drag(0f, height, width, height) }

        // Beyond the pole the up-vector is undefined and the view would spin on its own axis for
        // no input at all.
        assertTrue(camera.elevationDeg <= VirtualCamera.MAX_ELEVATION)
        assertTrue(camera.elevationDeg >= VirtualCamera.MAX_ELEVATION - 0.001)
    }

    @Test
    fun `azimuth wraps rather than growing without bound`() {
        val camera = VirtualCamera(initialAzimuthDeg = 350.0)

        camera.drag(dxPixels = -width, dyPixels = 0f, widthPx = width, heightPx = height)

        assertTrue(camera.azimuthDeg in 0.0..360.0, "azimuth escaped its range: ${camera.azimuthDeg}")
    }

    @Test
    fun `a fling decays to a stop`() {
        val camera = VirtualCamera()
        camera.fling(velocityX = 2000f, velocityY = 0f, widthPx = width, heightPx = height)
        assertTrue(camera.isMoving)

        var frames = 0
        while (camera.settle(deltaSeconds = 1f / 60f) && frames < 600) frames++

        assertFalse(camera.isMoving)
        assertTrue(frames < 300, "a fling should settle within a few seconds, took $frames frames")
    }

    @Test
    fun `inertia does not depend on the frame rate`() {
        // The same flick must travel the same distance whether the display runs at 30 Hz or 120,
        // which a per-frame decay factor would get wrong by a factor of four.
        val slow = VirtualCamera(initialAzimuthDeg = 0.0)
        val fast = VirtualCamera(initialAzimuthDeg = 0.0)
        slow.fling(-2000f, 0f, width, height)
        fast.fling(-2000f, 0f, width, height)

        repeat(30) { slow.settle(1f / 30f) }
        repeat(120) { fast.settle(1f / 120f) }

        assertEquals(slow.azimuthDeg, fast.azimuthDeg, 1.0)
    }

    @Test
    fun `putting a finger down stops the fling`() {
        val camera = VirtualCamera()
        camera.fling(2000f, 0f, width, height)

        camera.stop()

        assertFalse(camera.isMoving)
        assertFalse(camera.settle(1f / 60f))
    }

    @Test
    fun `a fling into the zenith stops rather than grinding against the clamp`() {
        val camera = VirtualCamera(initialElevationDeg = 88.0)
        camera.fling(0f, 4000f, width, height)

        repeat(10) { camera.settle(1f / 60f) }

        assertEquals(VirtualCamera.MAX_ELEVATION, camera.elevationDeg, 1e-9)
        assertFalse(camera.isMoving, "the fling should end at the limit, not keep pushing")
    }

    @Test
    fun `pinching in narrows the field of view`() {
        val camera = VirtualCamera(initialFovDeg = 60.0)

        camera.zoom(scaleFactor = 2f)

        assertEquals(30.0, camera.fovDeg, 1e-9)
    }

    @Test
    fun `zoom is bounded at both ends`() {
        val camera = VirtualCamera(initialFovDeg = 60.0)

        repeat(20) { camera.zoom(2f) }
        assertEquals(CameraFov.MIN_FOV_DEG, camera.fovDeg, 1e-9)

        repeat(40) { camera.zoom(0.5f) }
        assertEquals(CameraFov.MAX_FOV_DEG, camera.fovDeg, 1e-9)
    }

    @Test
    fun `a degenerate zoom factor is ignored`() {
        val camera = VirtualCamera(initialFovDeg = 60.0)

        camera.zoom(0f)
        camera.zoom(-1f)

        assertEquals(60.0, camera.fovDeg, 1e-9)
    }

    @Test
    fun `zooming in makes a drag turn less`() {
        val wide = VirtualCamera(initialAzimuthDeg = 180.0, initialFovDeg = 90.0)
        val narrow = VirtualCamera(initialAzimuthDeg = 180.0, initialFovDeg = 30.0)

        wide.drag(-100f, 0f, width, height)
        narrow.drag(-100f, 0f, width, height)

        assertTrue(
            narrow.azimuthDeg - 180.0 < wide.azimuthDeg - 180.0,
            "zoomed in, the same finger movement should cover less sky",
        )
    }

    @Test
    fun `looking at a satellite points straight at it and cancels any fling`() {
        val camera = VirtualCamera()
        camera.fling(3000f, 0f, width, height)

        camera.lookAt(azimuthDeg = 137.0, elevationDeg = 42.0)

        assertEquals(137.0, camera.azimuthDeg, 1e-9)
        assertEquals(42.0, camera.elevationDeg, 1e-9)
        assertFalse(camera.isMoving)
    }

    @Test
    fun `a zero-sized viewport is ignored rather than producing NaN`() {
        val camera = VirtualCamera(initialAzimuthDeg = 10.0, initialElevationDeg = 10.0)

        camera.drag(50f, 50f, widthPx = 0f, heightPx = 0f)
        camera.fling(50f, 50f, widthPx = 0f, heightPx = 0f)

        assertEquals(10.0, camera.azimuthDeg, 1e-9)
        assertEquals(10.0, camera.elevationDeg, 1e-9)
    }
}
