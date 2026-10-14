package com.developerstring.satride.core.sensors

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.Topocentric
import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.geomag.Wmm
import com.developerstring.satride.core.orbit.math.Quaternion
import com.developerstring.satride.core.orbit.math.Vector3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.time.Instant

/**
 * The one place that defines what an attitude quaternion means.
 *
 * Every frame bug in an AR app is a disagreement about axes, and the only defence is to write
 * the conventions down once and convert into them at the platform boundary. Everything above
 * this file may assume the ENU convention below and nothing else.
 *
 * ## The world frame: ENU, true north
 *
 * ```
 *            Up (Z)
 *             |
 *             |
 *             +------ East (X)
 *            /
 *           /
 *      North (Y)
 * ```
 *
 * Right-handed, origin at the observer. This is the same east/north/up ordering the orbit engine
 * already produces in `Frames.ecefToTopocentric`, so a satellite's ENU vector and the phone's
 * attitude are directly comparable with no adapter in between — which is the entire point.
 *
 * Azimuth is measured **clockwise from north**, so `azimuth = atan2(east, north)`. Note the
 * argument order: `atan2(y, x)` measures anticlockwise from the x-axis, and passing east first
 * is what converts to a compass bearing. Getting this backwards mirrors the sky about the
 * north-south line, which looks plausible until you notice east and west are swapped.
 *
 * ## The device frame
 *
 * Android and iOS agree here, which is a small mercy:
 *
 * ```
 *        +Y (top of screen)
 *         |
 *         |
 *         +------ +X (right of screen)
 *        /
 *       /
 *     +Z (out of the screen, towards the user's face)
 * ```
 *
 * The rear camera therefore looks along **−Z**. [CAMERA_FORWARD] is that vector, and it is the
 * one the AR projection cares about: where the phone is pointed is where the camera looks, not
 * where the screen faces.
 *
 * ## What the quaternion means
 *
 * An [AttitudeSample]'s rotation maps **device coordinates into ENU coordinates**:
 *
 * ```
 * enuVector = attitude.rotate(deviceVector)
 * ```
 *
 * So the direction the camera is pointing, in world terms, is
 * `attitude.rotate(CAMERA_FORWARD)` — which is exactly what [pointingDirection] returns.
 *
 * ## Platform conversions, and why each one is needed
 *
 * - **Android** hands out a rotation *matrix* mapping device → world, where its world is
 *   X=East, Y=North, Z=Up — already ENU. Two corrections remain: the matrix must be remapped
 *   for the display's rotation (a landscape device reports the same physical orientation with
 *   different screen axes), and its north is **magnetic**, so [toTrueNorth] applies declination.
 *
 * - **iOS** `CMDeviceMotion` with `.xTrueNorthZVertical` gives X=True North, Y=West, Z=Up —
 *   a north-west-up frame, not ENU. [nwuToEnu] rotates it. CoreMotion has already done the
 *   true-north correction, so no declination is applied on iOS; applying it twice would double
 *   the error rather than remove it.
 */
public object AttitudeFrames {

    /** East in the world frame. */
    public val EAST: Vector3 = Vector3(1.0, 0.0, 0.0)

    /** North in the world frame. */
    public val NORTH: Vector3 = Vector3(0.0, 1.0, 0.0)

    /** Up in the world frame. */
    public val UP: Vector3 = Vector3(0.0, 0.0, 1.0)

    /**
     * The direction the rear camera looks, in device coordinates.
     *
     * Negative Z: the screen faces the user, the camera faces away.
     */
    public val CAMERA_FORWARD: Vector3 = Vector3(0.0, 0.0, -1.0)

    /** The direction the top of the screen points, in device coordinates. */
    public val SCREEN_UP: Vector3 = Vector3(0.0, 1.0, 0.0)

    /**
     * Rotation taking a north-west-up vector to east-north-up.
     *
     * `(n, w, u) -> (-w, n, u)`, which is a quarter turn about Up. Needed for CoreMotion, whose
     * `.xTrueNorthZVertical` frame is NWU despite the name mentioning only two of its axes.
     */
    public val NWU_TO_ENU: Quaternion = Quaternion.fromAxisAngle(UP, Constants.TWO_PI / 4.0)

    /** Converts a CoreMotion (north-west-up) attitude into the ENU convention. */
    public fun nwuToEnu(nwuAttitude: Quaternion): Quaternion = NWU_TO_ENU * nwuAttitude

    /**
     * Rotates a magnetic-north-referenced attitude onto true north.
     *
     * Declination is the angle from true north to magnetic north, positive east, so a bearing
     * measured against magnetic north becomes a true bearing by *adding* it. In this frame
     * azimuth increases clockwise while a rotation about Up is anticlockwise, which is why the
     * rotation applied is **negative** declination. Getting the sign wrong doubles the error
     * instead of removing it — and since declination is near zero over much of Europe and North
     * America, that mistake would look fine exactly where it was most likely to be tested.
     */
    public fun toTrueNorth(magneticAttitude: Quaternion, declinationDeg: Double): Quaternion =
        Quaternion.fromAxisAngle(UP, -declinationDeg * Constants.DEG_TO_RAD) * magneticAttitude

    /** [toTrueNorth] with the declination looked up for [position] at [time]. */
    public fun toTrueNorth(magneticAttitude: Quaternion, position: GeoPos, time: Instant): Quaternion =
        toTrueNorth(magneticAttitude, Wmm.declinationDeg(position, time))

    /** Where the rear camera is pointing, as a unit vector in ENU. */
    public fun pointingDirection(attitude: Quaternion): Vector3 = attitude.rotate(CAMERA_FORWARD).normalized()

    /**
     * Azimuth and elevation of an ENU direction, in degrees.
     *
     * Azimuth is normalised to `[0, 360)` so it can be compared with the orbit engine's output
     * and shown as a compass bearing without further wrangling.
     */
    public fun toAzimuthElevation(enuDirection: Vector3): AzimuthElevation {
        val v = enuDirection.normalized()
        var azimuth = atan2(v.x, v.y) * Constants.RAD_TO_DEG
        if (azimuth < 0.0) azimuth += 360.0
        val elevation = asin(v.z.coerceIn(-1.0, 1.0)) * Constants.RAD_TO_DEG
        return AzimuthElevation(azimuth, elevation)
    }

    /** Where the phone is pointing, as a compass bearing and an angle above the horizon. */
    public fun pointingAzimuthElevation(attitude: Quaternion): AzimuthElevation =
        toAzimuthElevation(pointingDirection(attitude))

    /** The unit ENU vector for a compass bearing and elevation, in degrees. */
    public fun directionOf(azimuthDeg: Double, elevationDeg: Double): Vector3 {
        val az = azimuthDeg * Constants.DEG_TO_RAD
        val el = elevationDeg * Constants.DEG_TO_RAD
        val horizontal = kotlin.math.cos(el)
        return Vector3(
            horizontal * kotlin.math.sin(az),
            horizontal * kotlin.math.cos(az),
            kotlin.math.sin(el),
        )
    }

    /** The unit ENU vector pointing at a satellite. */
    public fun directionOf(topo: Topocentric): Vector3 = directionOf(topo.azimuthDeg, topo.elevationDeg)

    /**
     * The angle between where the phone points and where a satellite is, in degrees.
     *
     * This is what the AR guide arrow and its haptic feedback are driven by.
     */
    public fun angularSeparationDeg(attitude: Quaternion, topo: Topocentric): Double =
        pointingDirection(attitude).angleTo(directionOf(topo)) * Constants.RAD_TO_DEG

    /**
     * Shortest signed difference between two compass bearings, in degrees, within (-180, 180].
     *
     * Interpolating or differencing azimuths without this wraps the long way round at the
     * 359°/0° boundary — which in the sky view is a satellite that jumps most of the way across
     * the horizon once per orbit.
     */
    public fun azimuthDeltaDeg(fromDeg: Double, toDeg: Double): Double {
        var delta = (toDeg - fromDeg) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta <= -180.0) delta += 360.0
        return delta
    }
}

/** A direction expressed the way a person would describe it. */
public data class AzimuthElevation(val azimuthDeg: Double, val elevationDeg: Double)
