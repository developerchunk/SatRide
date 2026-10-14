package com.developerstring.satride.feature.arsky.projection

import com.developerstring.satride.core.orbit.math.Quaternion
import com.developerstring.satride.core.orbit.math.Vector3
import com.developerstring.satride.core.sensors.AttitudeFrames
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Projection is the one part of the AR view with a right answer that can be written down, so
 * every case here is computed by hand rather than recorded from a run. A screenshot test would
 * happily lock in a sky that is mirrored east-for-west.
 *
 * Unless stated otherwise the fixture is a square viewport with a 90° field of view on both axes,
 * which makes the arithmetic checkable in your head: `tan(45°) = 1`, so a target 45° off-axis
 * lands exactly on the edge of the screen.
 */
class SkyProjectorTest {

    private val size = 1000f

    private fun projector(
        attitude: Quaternion = SkyProjector.UPRIGHT_FACING_NORTH,
        width: Float = size,
        height: Float = size,
        fov: CameraFov = CameraFov(90.0, 90.0),
    ) = SkyProjector().apply {
        this.widthPx = width
        this.heightPx = height
        this.fov = fov
        this.attitude = attitude
    }

    // --- The fixture attitude means what it says ---------------------------------------------

    @Test
    fun `upright facing north points the camera at the northern horizon`() {
        val pointing = AttitudeFrames.pointingAzimuthElevation(SkyProjector.UPRIGHT_FACING_NORTH)

        assertEquals(0.0, pointing.azimuthDeg, 1e-9)
        assertEquals(0.0, pointing.elevationDeg, 1e-9)
    }

    @Test
    fun `the identity attitude points at the ground — which is why the fixture is not identity`() {
        // Guards the comment in SkyProjector: the intuitive assumption that identity faces north
        // is wrong, and a test that silently used it would be checking the floor.
        val pointing = AttitudeFrames.pointingAzimuthElevation(Quaternion.IDENTITY)

        assertEquals(-90.0, pointing.elevationDeg, 1e-9)
    }

    // --- The centre, and the tangent relationship ---------------------------------------------

    @Test
    fun `what the camera points at lands in the middle of the screen`() {
        val point = projector().project(azDeg = 0.0, elDeg = 0.0)

        assertTrue(point.isVisible)
        assertEquals(size / 2f, point.x, 1e-3f)
        assertEquals(size / 2f, point.y, 1e-3f)
    }

    @Test
    fun `a target at half the field of view lands on the edge — not part way to it`() {
        // 45° with a 90° FOV is the edge exactly. This is the assertion that catches a projection
        // written as a linear map from angle to pixels, which is right at the centre, right at
        // the edge and wrong everywhere in between.
        val top = projector().project(azDeg = 0.0, elDeg = 45.0)

        assertEquals(size / 2f, top.x, 1e-3f)
        assertEquals(0f, top.y, 1e-3f)
    }

    @Test
    fun `half way up the screen is not half the field of view`() {
        // The point a quarter of the screen height above centre is at atan(0.5) = 26.565°, not
        // 22.5°. Spelling this out because it is the discrepancy a linear projection hides.
        val quarterUp = projector().project(azDeg = 0.0, elDeg = 26.565051177077994)

        assertEquals(size * 0.25f, quarterUp.y, 1e-2f)
    }

    // --- Orientation: the sky is not mirrored --------------------------------------------------

    @Test
    fun `east is on the right when facing north`() {
        val east = projector().project(azDeg = 45.0, elDeg = 0.0)

        assertEquals(size, east.x, 1e-3f)
        assertEquals(size / 2f, east.y, 1e-3f)
    }

    @Test
    fun `west is on the left when facing north`() {
        val west = projector().project(azDeg = 315.0, elDeg = 0.0)

        assertEquals(0f, west.x, 1e-3f)
        assertEquals(size / 2f, west.y, 1e-3f)
    }

    @Test
    fun `up the sky is up the screen`() {
        val higher = projector().project(azDeg = 0.0, elDeg = 20.0)
        val lower = projector().project(azDeg = 0.0, elDeg = 10.0)

        assertTrue(higher.y < lower.y, "a higher elevation must have a smaller screen y")
    }

    // --- Culling --------------------------------------------------------------------------------

    @Test
    fun `a target behind the camera is culled`() {
        val behind = projector().project(azDeg = 180.0, elDeg = 0.0)

        assertFalse(behind.isVisible)
    }

    @Test
    fun `a target exactly at the lens plane is culled rather than projected to infinity`() {
        // Due east while facing north is 90° off-axis: the pinhole divide is by zero here, and
        // without the epsilon guard this becomes a glyph streaking off the screen for one frame.
        val edgeOn = projector().project(azDeg = 90.0, elDeg = 0.0)

        assertFalse(edgeOn.isVisible)
    }

    @Test
    fun `a target off the edge of the screen is still projected — because the arrow needs it`() {
        val offTop = projector().project(azDeg = 0.0, elDeg = 60.0)

        assertTrue(offTop.isVisible, "in front of the camera but off-screen is not culled")
        assertTrue(offTop.y < 0f, "60° with a 90° FOV is past the top edge")
        assertFalse(projector().isOnScreen(offTop))
    }

    // --- Roll --------------------------------------------------------------------------------

    @Test
    fun `rolling the phone rotates the sky the opposite way`() {
        // Roll the device a quarter turn about the axis it is looking along (north). A target
        // that sat at the top of the screen must move to one side, not stay put and not follow.
        val rolled = Quaternion.fromAxisAngle(AttitudeFrames.NORTH, PI / 2.0) *
            SkyProjector.UPRIGHT_FACING_NORTH
        val point = projector(attitude = rolled).project(azDeg = 0.0, elDeg = 45.0)

        assertEquals(0f, point.x, 1e-3f)
        assertEquals(size / 2f, point.y, 1e-3f)
    }

    // --- Landscape ---------------------------------------------------------------------------

    @Test
    fun `landscape works by passing the swapped viewport — with no rotation of its own`() {
        // The attitude arrives already remapped for display rotation, so landscape is nothing
        // more than a wider viewport and the field of view that follows from it. If this test
        // ever needs a rotation adding, the correction is being applied twice.
        val landscape = projector(
            width = 2000f,
            height = 1000f,
            fov = CameraFov.fromHorizontal(90.0, widthPx = 2000f, heightPx = 1000f),
        )

        val centre = landscape.project(azDeg = 0.0, elDeg = 0.0)
        assertEquals(1000f, centre.x, 1e-3f)
        assertEquals(500f, centre.y, 1e-3f)

        // atan(0.5) = 26.565° is half the derived vertical FOV, so it is the top edge.
        val top = landscape.project(azDeg = 0.0, elDeg = 26.565051177077994)
        assertEquals(0f, top.y, 1e-2f)

        // The horizontal half-angle is unchanged at 45°, so east is still the right edge.
        val right = landscape.project(azDeg = 45.0, elDeg = 0.0)
        assertEquals(2000f, right.x, 1e-2f)
    }

    @Test
    fun `vertical field of view follows the pinhole relation — not the aspect ratio`() {
        val fov = CameraFov.fromHorizontal(60.0, widthPx = 1000f, heightPx = 2000f)

        // 2·atan(tan(30°)·2) = 98.21°. Scaling the angle instead would give 120°, a 22° error
        // that stretches the sky vertically on every tall phone.
        assertEquals(98.2132, fov.verticalDeg, 1e-3)
        assertEquals(60.0, fov.horizontalDeg, 1e-9)
    }

    @Test
    fun `deriving vertical then horizontal returns the original`() {
        val original = CameraFov.fromHorizontal(72.0, widthPx = 1080f, heightPx = 2400f)
        val roundTripped = CameraFov.fromVertical(original.verticalDeg, widthPx = 1080f, heightPx = 2400f)

        // Loose by floating-point standards because the round trip passes through tan and atan
        // twice at a steep part of the curve, which amplifies the last few bits. A millionth of a
        // degree is some ten million times finer than the compass error this all sits on top of.
        assertEquals(72.0, roundTripped.horizontalDeg, 1e-5)
    }

    // --- Vector and angle helpers --------------------------------------------------------------

    @Test
    fun `projecting a vector agrees with projecting the angles it represents`() {
        val projector = projector()
        val fromAngles = projector.project(azDeg = 30.0, elDeg = 20.0)
        val fromVector = projector.project(AttitudeFrames.directionOf(30.0, 20.0))

        assertEquals(fromAngles.x, fromVector.x, 1e-4f)
        assertEquals(fromAngles.y, fromVector.y, 1e-4f)
    }

    @Test
    fun `the screen angle points straight up for a target above the centre`() {
        assertEquals(0f, projector().screenAngleTo(azDeg = 0.0, elDeg = 45.0), 1e-3f)
    }

    @Test
    fun `the screen angle is measured clockwise from up`() {
        assertEquals(90f, projector().screenAngleTo(azDeg = 90.0, elDeg = 0.0), 1e-3f)
        assertEquals(180f, projector().screenAngleTo(azDeg = 0.0, elDeg = -45.0), 1e-3f)
        assertEquals(270f, projector().screenAngleTo(azDeg = 270.0, elDeg = 0.0), 1e-3f)
    }

    @Test
    fun `a target directly behind resolves to a steady direction rather than spinning on noise`() {
        // Every direction is equally wrong here, so the arrow must not be free to chase sensor
        // jitter around the compass.
        assertEquals(0f, projector().screenAngleTo(azDeg = 180.0, elDeg = 0.0), 1e-3f)
    }

    @Test
    fun `angular error is zero at the centre and grows with offset`() {
        val projector = projector()

        assertEquals(0.0, projector.angularErrorDeg(azDeg = 0.0, elDeg = 0.0), 1e-9)
        assertEquals(45.0, projector.angularErrorDeg(azDeg = 0.0, elDeg = 45.0), 1e-9)
        assertEquals(45.0, projector.angularErrorDeg(azDeg = 45.0, elDeg = 0.0), 1e-9)
        assertEquals(180.0, projector.angularErrorDeg(azDeg = 180.0, elDeg = 0.0), 1e-9)
    }

    @Test
    fun `angular error matches the frame convention's own separation calculation`() {
        // Two independent routes to the same number: if these ever disagree, the projector and
        // the guide arrow are working in different frames.
        val attitude = Quaternion.fromAxisAngle(Vector3(1.0, 0.0, 0.0), PI / 3.0)
        val projector = projector(attitude = attitude)
        val target = com.developerstring.satride.core.model.Topocentric(
            azimuthDeg = 40.0,
            elevationDeg = 25.0,
            rangeKm = 800.0,
            rangeRateKmS = 0.0,
        )

        assertEquals(
            AttitudeFrames.angularSeparationDeg(attitude, target),
            projector.angularErrorDeg(target.azimuthDeg, target.elevationDeg),
            1e-6,
        )
    }

    // --- Packing -------------------------------------------------------------------------------

    @Test
    fun `a projected point survives being packed and unpacked`() {
        val point = ProjectedPoint.of(123.5f, -67.25f)

        assertTrue(point.isVisible)
        assertEquals(123.5f, point.x)
        assertEquals(-67.25f, point.y)
    }

    @Test
    fun `the culled sentinel is not mistaken for a position`() {
        assertFalse(ProjectedPoint.CULLED.isVisible)
    }

    @Test
    fun `a normalised attitude is not required of the caller`() {
        // The smoothing filter slerps thousands of times a second; nothing guarantees the result
        // stays exactly unit length, and an un-normalised quaternion scales the whole sky.
        val drifting = SkyProjector.UPRIGHT_FACING_NORTH.let {
            Quaternion(it.w * 1.05, it.x * 1.05, it.y * 1.05, it.z * 1.05)
        }
        val point = projector(attitude = drifting).project(azDeg = 45.0, elDeg = 0.0)

        assertEquals(size, point.x, 1e-3f)
        assertEquals(size / 2f, point.y, 1e-3f)
    }

    @Test
    fun `configure derives the vertical field of view from the viewport`() {
        val projector = SkyProjector().apply { configure(1080f, 2400f, horizontalFovDeg = 67.0) }

        assertEquals(67.0, projector.fov.horizontalDeg, 1e-9)
        assertEquals(1080f, projector.widthPx)
        // Taller than wide, so the vertical angle must be the larger of the two.
        assertTrue(projector.fov.verticalDeg > projector.fov.horizontalDeg)
    }

    @Test
    fun `a satellite an arcminute across the zenith stays put between frames`() {
        // Sanity check on the whole chain at the zenith, where azimuth is degenerate: a small
        // change in elevation must produce a small change on screen, not a jump.
        val projector = projector(
            attitude = Quaternion.fromAxisAngle(AttitudeFrames.EAST, PI / 2.0) *
                Quaternion.fromAxisAngle(AttitudeFrames.EAST, PI / 2.0),
        )
        val a = projector.project(azDeg = 0.0, elDeg = 89.9)
        val b = projector.project(azDeg = 180.0, elDeg = 89.9)

        assertTrue(a.isVisible && b.isVisible)
        val separation = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
        assertTrue(separation < size * 0.02f, "0.2° apart should be under 2% of the screen, was $separation px")
    }
}
