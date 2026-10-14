package com.developerstring.satride.feature.arsky.projection

import com.developerstring.satride.core.orbit.math.Quaternion
import com.developerstring.satride.core.orbit.math.Vector3
import kotlin.jvm.JvmInline
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * A projected screen position, or the fact that there is not one.
 *
 * ## Why this is a packed value class rather than a nullable data class
 *
 * The phase file specifies `project(): ProjectedPoint?`, and this returns a non-null value class
 * instead. That is a deliberate departure, for a reason the same phase file supplies: P5.3
 * requires **zero allocations per frame**, and a nullable value class is boxed by the compiler at
 * every return. Fifty satellites at thirty frames a second is fifteen hundred short-lived objects
 * a second — enough to keep the young generation churning and to show up as exactly the sawtooth
 * heap that acceptance criterion exists to forbid.
 *
 * Packing two floats into the `Long` a value class already carries costs nothing and allocates
 * nothing. [isVisible] replaces the null check and reads the same at the call site.
 */
@JvmInline
value class ProjectedPoint internal constructor(private val bits: Long) {

    /** False when the direction is behind the camera, where there is no meaningful screen point. */
    val isVisible: Boolean get() = !x.isNaN()

    val x: Float get() = Float.fromBits((bits ushr Int.SIZE_BITS).toInt())

    val y: Float get() = Float.fromBits(bits.toInt())

    override fun toString(): String = if (isVisible) "ProjectedPoint($x, $y)" else "ProjectedPoint(culled)"

    companion object {
        /** The direction is behind the camera. */
        val CULLED: ProjectedPoint = of(Float.NaN, Float.NaN)

        internal fun of(x: Float, y: Float): ProjectedPoint =
            ProjectedPoint((x.toRawBits().toLong() shl Int.SIZE_BITS) or (y.toRawBits().toLong() and 0xFFFF_FFFFL))
    }
}

/**
 * Turns a direction in the sky into a position on the screen.
 *
 * ## The model
 *
 * A pinhole camera, which is all a phone lens is for this purpose. A satellite is effectively at
 * infinity — the nearest is several hundred kilometres away — so only its *direction* matters and
 * there is no parallax to model. That is also why this app needs no ARCore or ARKit: those solve
 * for the camera's position in a room, and this problem has no position in it.
 *
 * ## Screen orientation is already handled, and must not be handled twice
 *
 * [attitude] arrives having already been remapped for the display's rotation — Android does it in
 * `AndroidAttitudeSource`, iOS's interface orientation is applied at the same boundary. So the
 * device axes this class projects through *are* the screen's axes in every orientation, and
 * landscape needs no special case beyond passing the swapped [widthPx]/[heightPx] and the
 * matching [fov]. Adding a rotation here would double-correct it: the sky would sit still in
 * portrait and rotate the wrong way in landscape, which is a bug that looks like a sensor
 * problem rather than a projection one.
 *
 * ## Mutable on purpose
 *
 * Configured once and updated in place each frame rather than rebuilt, so the render loop
 * allocates nothing at all — see [ProjectedPoint]. Hold one in `remember` and assign to it.
 *
 * Not thread-safe, and not meant to be: it is written and read by the frame loop alone.
 */
class SkyProjector {

    /** The lens being looked through. */
    var fov: CameraFov = CameraFov.PLANETARIUM_DEFAULT
        set(value) {
            field = value
            tanHalfHorizontal = tan(value.horizontalDeg * DEG_TO_RAD / 2.0)
            tanHalfVertical = tan(value.verticalDeg * DEG_TO_RAD / 2.0)
        }

    var widthPx: Float = 0f

    var heightPx: Float = 0f

    /**
     * Device orientation, device coordinates into ENU — the convention `AttitudeFrames` defines.
     *
     * Stored decomposed because projection needs the *inverse* rotation (world into device) for
     * every satellite, every frame, and `conjugate()` would allocate a quaternion each time.
     */
    var attitude: Quaternion = Quaternion.IDENTITY
        set(value) {
            field = value
            // The conjugate of a unit quaternion is its inverse. Normalising here rather than
            // trusting the caller keeps a slowly denormalising filter chain from quietly
            // scaling the whole sky.
            val n = value.normalized()
            invW = n.w
            invX = -n.x
            invY = -n.y
            invZ = -n.z
        }

    private var tanHalfHorizontal: Double = tan(CameraFov.PLANETARIUM_DEFAULT.horizontalDeg * DEG_TO_RAD / 2.0)
    private var tanHalfVertical: Double = tan(CameraFov.PLANETARIUM_DEFAULT.verticalDeg * DEG_TO_RAD / 2.0)

    private var invW: Double = 1.0
    private var invX: Double = 0.0
    private var invY: Double = 0.0
    private var invZ: Double = 0.0

    /** The most recent [project] call's device-space direction, reused by [screenAngleTo]. */
    private var lastDeviceX: Double = 0.0
    private var lastDeviceY: Double = 0.0
    private var lastDeviceZ: Double = 0.0

    /** Configures the viewport in one call, deriving the vertical field of view from its shape. */
    fun configure(widthPx: Float, heightPx: Float, horizontalFovDeg: Double) {
        this.widthPx = widthPx
        this.heightPx = heightPx
        this.fov = CameraFov.fromHorizontal(horizontalFovDeg, widthPx, heightPx)
    }

    /**
     * Where a compass bearing and elevation land on the screen, in pixels.
     *
     * Returns [ProjectedPoint.CULLED] for anything behind the camera. Points beyond the edges of
     * the screen are *not* culled — an off-screen satellite still has a valid position, and the
     * guide arrow needs it to say which way to turn.
     */
    fun project(azDeg: Double, elDeg: Double): ProjectedPoint {
        val az = azDeg * DEG_TO_RAD
        val el = elDeg * DEG_TO_RAD
        val horizontal = cos(el)
        return projectEnu(horizontal * sin(az), horizontal * cos(az), sin(el))
    }

    /** [project] for a direction already expressed as an ENU unit vector. */
    fun project(enu: Vector3): ProjectedPoint = projectEnu(enu.x, enu.y, enu.z)

    /**
     * The projection itself, taking loose doubles so no vector is allocated on the way in.
     *
     * The rotation is written out rather than delegated to [Quaternion.rotate] for the same
     * reason: that method returns a new [Vector3], and this runs once per satellite per frame.
     */
    private fun projectEnu(east: Double, north: Double, up: Double): ProjectedPoint {
        // v' = v + 2·(u × (u × v + s·v)) for the unit quaternion (s, u) — here the inverse
        // rotation, taking a world direction into device coordinates.
        val tx = 2.0 * (invY * up - invZ * north)
        val ty = 2.0 * (invZ * east - invX * up)
        val tz = 2.0 * (invX * north - invY * east)

        val deviceX = east + invW * tx + (invY * tz - invZ * ty)
        val deviceY = north + invW * ty + (invZ * tx - invX * tz)
        val deviceZ = up + invW * tz + (invX * ty - invY * tx)

        lastDeviceX = deviceX
        lastDeviceY = deviceY
        lastDeviceZ = deviceZ

        // The rear camera looks along -Z, so depth in front of the lens is -deviceZ. At or behind
        // the lens plane there is no projection: the pinhole divide would flip the point through
        // the centre and draw a satellite that is behind you as though it were in front.
        val depth = -deviceZ
        if (depth <= DEPTH_EPSILON) return ProjectedPoint.CULLED

        val ndcX = (deviceX / depth) / tanHalfHorizontal
        val ndcY = (deviceY / depth) / tanHalfVertical

        // Screen y grows downwards while device y points at the top of the screen, hence the flip.
        return ProjectedPoint.of(
            x = ((ndcX + 1.0) * 0.5 * widthPx).toFloat(),
            y = ((1.0 - ndcY) * 0.5 * heightPx).toFloat(),
        )
    }

    /**
     * Which way to turn the phone to bring a direction into view, as a screen-space bearing.
     *
     * Degrees clockwise from straight up the screen, so it can be handed to a rotation modifier
     * unchanged. Defined for targets behind the camera as well as in front, which is the case
     * that matters: an arrow is only needed when the thing it points at cannot be seen.
     *
     * Uses only the two screen-plane components, so a target directly behind the viewer — where
     * every direction is equally right — resolves to straight up rather than to a value that
     * spins with sensor noise.
     */
    fun screenAngleTo(azDeg: Double, elDeg: Double): Float {
        project(azDeg, elDeg)
        val x = lastDeviceX
        val y = lastDeviceY
        // Near the camera axis — straight ahead or straight behind — the screen-plane components
        // are pure rounding error, and atan2 of two values near 1e-16 returns whatever their
        // noise happens to say. Testing for exact zero does not catch this: the rotation almost
        // never produces one, so the arrow would spin freely while pointing at a target directly
        // behind the user. Compare against a threshold instead.
        if (x * x + y * y < AXIS_EPSILON * AXIS_EPSILON) return 0f
        var degrees = atan2(x, y) * RAD_TO_DEG
        if (degrees < 0.0) degrees += 360.0
        return degrees.toFloat()
    }

    /**
     * How far off-centre a direction is, in degrees — the guide loop's error term.
     *
     * Read from the same device-space vector the projection produced, so the arrow, the haptics
     * and the glyph can never disagree about how close the user is.
     */
    fun angularErrorDeg(azDeg: Double, elDeg: Double): Double {
        project(azDeg, elDeg)
        val length = kotlin.math.sqrt(
            lastDeviceX * lastDeviceX + lastDeviceY * lastDeviceY + lastDeviceZ * lastDeviceZ,
        )
        if (length == 0.0) return 180.0
        // The angle between the target and -Z, the direction the camera looks.
        val cosine = (-lastDeviceZ / length).coerceIn(-1.0, 1.0)
        return kotlin.math.acos(cosine) * RAD_TO_DEG
    }

    /** True when a projected point falls inside the viewport, with [marginPx] of slack. */
    fun isOnScreen(point: ProjectedPoint, marginPx: Float = 0f): Boolean = point.isVisible &&
        point.x >= -marginPx && point.x <= widthPx + marginPx &&
        point.y >= -marginPx && point.y <= heightPx + marginPx

    companion object {
        /**
         * How close to the lens plane counts as behind it.
         *
         * Not zero: a direction exactly on the plane divides by zero and projects to infinity,
         * which becomes a glyph streaking off the edge of the screen for the one frame it takes
         * to cross.
         */
        private const val DEPTH_EPSILON: Double = 1e-6

        /**
         * How close to the camera axis counts as *on* it, for direction purposes.
         *
         * Directions are unit length, so this is roughly a millionth of a radian — far below
         * anything a person could see, and far above the 1e-16 noise floor the rotation leaves.
         */
        private const val AXIS_EPSILON: Double = 1e-6

        /**
         * The attitude of a phone held upright, screen towards the viewer, camera facing north.
         *
         * A quarter turn about East: it takes the device's up-the-screen axis onto world Up and
         * the camera's −Z onto North. Worth naming because the intuitive guess — that the
         * identity quaternion faces north — is wrong. Identity leaves the device axes on the
         * world axes, which points the camera along −Up: straight at the ground.
         *
         * Used as planetarium mode's starting view and as the fixture most projection tests are
         * written against.
         */
        val UPRIGHT_FACING_NORTH: Quaternion =
            Quaternion.fromAxisAngle(Vector3(1.0, 0.0, 0.0), kotlin.math.PI / 2.0)
    }
}
