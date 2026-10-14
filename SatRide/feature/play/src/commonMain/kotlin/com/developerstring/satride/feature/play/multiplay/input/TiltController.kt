package com.developerstring.satride.feature.play.multiplay.input

import com.developerstring.satride.core.orbit.math.Quaternion
import com.developerstring.satride.core.orbit.math.Vector3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Motion controls for the arena, built the way the games that do this well do it.
 *
 * Two different things are asked of the sensor, and they want two different treatments:
 *
 * **Steering is absolute.** How far the phone is rolled from a neutral pose, like a wheel — the
 * SuperTuxKart model: the angle of gravity within the screen plane, a dead zone so a resting hand
 * does not steer, and a curve so small angles give fine control. [rollDeg] is that angle.
 *
 * **Looking around is a mouse.** The camera should follow how fast the phone *turns*, not where
 * it points: "gyro as a mouse" (GyroWiki / JoyShockMapper / GamepadMotionHelpers). Angular
 * velocity is taken from successive attitude samples, converted to *player space* — yaw about
 * gravity for direction, the phone's own yaw/roll magnitude for size, so it works whether the
 * phone is held flat or upright — then filtered: tiered smoothing (a short rolling average that
 * only applies to slow, shaky input and fades out for deliberate moves) and tightening (inputs
 * below a small speed are squeezed toward zero rather than cut off). The result is integrated
 * into [lookYawDeg]/[lookPitchDeg], which relax back to centre when the phone is still, because
 * this is a chase camera's glance rather than a gun sight. Being rate-based, it cannot drift with
 * the compass the way an absolute yaw does.
 *
 * ## Axes
 *
 * Android's attitude source remaps the device axes for the display rotation and iOS's does not,
 * so which device axis is "screen up" differs by platform and by landscape. Rather than carry a
 * table, [calibrate] reads it from gravity: its projection onto the screen plane *is* the screen's
 * vertical. Everything is measured against those axes, so the feel is identical everywhere.
 *
 * Conventions, all "window" semantics — tilt toward what you want to see:
 * - [rollDeg] positive = phone turned clockwise (steer right).
 * - [lookYawDeg] positive = the phone was turned to face right (look right).
 * - [lookPitchDeg] positive = the top edge was tipped toward the player (look up).
 */
class TiltController {

    private var reference: Quaternion? = null
    private var previous: Quaternion? = null
    private var screenUp: Vector3 = Vector3(0.0, 1.0, 0.0)
    private var screenRight: Vector3 = Vector3(1.0, 0.0, 0.0)

    /** Absolute roll about the screen normal, degrees, after smoothing and the dead zone. */
    var rollDeg: Double = 0.0
        private set

    /** Absolute pitch (top edge toward the player), degrees. Informational; the camera uses rates. */
    var pitchDeg: Double = 0.0
        private set

    /** Integrated look, degrees, relaxing to zero when the phone is still. */
    var lookYawDeg: Double = 0.0
        private set
    var lookPitchDeg: Double = 0.0
        private set

    val isCalibrated: Boolean get() = reference != null

    // --- Tunables ----------------------------------------------------------------------------

    /** Camera degrees per phone degree. 1 is "natural"; a chase cam likes a little more. */
    var lookSensitivity: Double = 1.35

    /** Below this speed (deg/s) the rolling average applies in full; it fades out by 2× this. */
    var smoothThresholdDegS: Double = 8.0

    /** Rolling-average window for the smoothed tier, seconds. */
    var smoothWindowS: Double = 0.1

    /** Inputs slower than this (deg/s) are scaled toward zero — GyroWiki's "tightening". */
    var tighteningDegS: Double = 2.5

    /** How strongly the world (gravity) yaw may exceed the local yaw+roll magnitude. */
    var yawRelaxFactor: Double = 1.41

    /** Time constant of the look's return to centre while the phone is still, seconds. */
    var lookReturnS: Double = 2.2

    /** Look limits, degrees. */
    var maxLookYawDeg: Double = 75.0
    var maxLookPitchDeg: Double = 45.0

    /** Steering: below the dead zone nothing; full lock at [steerFullDeg]. */
    var steerDeadZoneDeg: Double = 2.5
    var steerFullDeg: Double = 26.0

    /** Smoothing time constant for the absolute angles, seconds. */
    var absoluteSmoothingS: Double = 0.08

    // --- State -------------------------------------------------------------------------------

    private var lastNanos = 0L
    private var rollSmoothed = 0.0
    private var pitchSmoothed = 0.0

    // Tiered smoothing buffer for the (yaw, pitch) rate pair.
    private val bufferYaw = DoubleArray(SMOOTH_BUFFER_MAX)
    private val bufferPitch = DoubleArray(SMOOTH_BUFFER_MAX)
    private var bufferIndex = 0
    private var bufferCount = 0

    /** Takes the current attitude (device → world, world up = +Z) as the neutral pose. */
    fun calibrate(attitude: Quaternion) {
        val q = attitude.normalized()
        reference = q
        previous = q
        val upInDevice = q.conjugate().rotate(WORLD_UP)
        // Screen up = gravity's projection onto the screen plane; the screen normal is device +Z.
        val planar = Vector3(upInDevice.x, upInDevice.y, 0.0)
        screenUp = if (planar.magnitude > 0.2) planar.normalized() else Vector3(0.0, 1.0, 0.0)
        // Right-handed: up × normal = right.
        screenRight = (screenUp cross SCREEN_NORMAL).normalized()
        rollDeg = 0.0
        pitchDeg = 0.0
        rollSmoothed = 0.0
        pitchSmoothed = 0.0
        lookYawDeg = 0.0
        lookPitchDeg = 0.0
        bufferYaw.fill(0.0)
        bufferPitch.fill(0.0)
        bufferIndex = 0
        bufferCount = 0
        lastNanos = 0L
    }

    fun clear() {
        reference = null
        previous = null
        rollDeg = 0.0
        pitchDeg = 0.0
        lookYawDeg = 0.0
        lookPitchDeg = 0.0
    }

    /** Recentres the look without touching the steering reference. */
    fun recentreLook() {
        lookYawDeg = 0.0
        lookPitchDeg = 0.0
    }

    /** Feeds one attitude sample; returns false until calibrated. */
    fun update(attitude: Quaternion, elapsedNanos: Long): Boolean {
        val ref = reference ?: return false
        val q = attitude.normalized()
        val prev = previous ?: q
        previous = q

        val dt = if (lastNanos == 0L || elapsedNanos <= lastNanos) {
            DEFAULT_DT
        } else {
            ((elapsedNanos - lastNanos) / 1e9).coerceIn(0.002, 0.1)
        }
        lastNanos = elapsedNanos

        updateAbsolute(ref, q, dt)
        updateLook(prev, q, dt)
        return true
    }

    // --- Absolute: steering roll, informational pitch -------------------------------------------

    private fun updateAbsolute(ref: Quaternion, q: Quaternion, dt: Double) {
        // Current device axes expressed in the reference device frame.
        val rel = (ref.conjugate() * q).normalized()
        val upNow = rel.rotate(screenUp)
        val normalNow = rel.rotate(SCREEN_NORMAL)
        // Where the screen's up axis now points within the screen plane: the wheel angle.
        val rawRoll = atan2(upNow dot screenRight, upNow dot screenUp) * RAD_TO_DEG
        // Tipping the top edge toward the player swings the normal *down* along screen-up.
        val rawPitch = -asinClamped(normalNow dot screenUp) * RAD_TO_DEG

        val k = 1.0 - exp(-dt / absoluteSmoothingS)
        rollSmoothed += (rawRoll - rollSmoothed) * k
        pitchSmoothed += (rawPitch - pitchSmoothed) * k
        rollDeg = deadZone(rollSmoothed, steerDeadZoneDeg)
        pitchDeg = pitchSmoothed
    }

    /**
     * The steering input for [rollDeg]: −1..1, zero inside the dead zone, full lock at
     * [steerFullDeg], with a gentle curve so the first few degrees are fine adjustments.
     */
    val steer: Double
        get() {
            val span = (steerFullDeg - steerDeadZoneDeg).coerceAtLeast(1.0)
            val t = (abs(rollDeg) / span).coerceIn(0.0, 1.0)
            val shaped = t * t * (3.0 - 2.0 * t) * 0.35 + t * 0.65 // mostly linear, softened near zero
            return if (rollDeg < 0) -shaped else shaped
        }

    // --- Rates: player-space gyro as a mouse ---------------------------------------------------

    private fun updateLook(prev: Quaternion, q: Quaternion, dt: Double) {
        // Angular velocity in the device frame from the rotation between samples: q = prev · Δq,
        // so Δq = prev⁻¹ · q is the turn expressed in the previous device frame.
        var delta = (prev.conjugate() * q).normalized()
        if (delta.w < 0) delta = Quaternion(-delta.w, -delta.x, -delta.y, -delta.z)
        val sinHalf = sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z)
        var wx = 0.0
        var wy = 0.0
        var wz = 0.0
        if (sinHalf > 1e-9) {
            val angle = 2.0 * atan2(sinHalf, delta.w)
            val rate = angle * RAD_TO_DEG / dt
            wx = delta.x / sinHalf * rate
            wy = delta.y / sinHalf * rate
            wz = delta.z / sinHalf * rate
        }

        // Gravity, pointing down, in the device frame.
        val upDev = q.conjugate().rotate(WORLD_UP)
        val gx = -upDev.x
        val gy = -upDev.y
        val gz = -upDev.z

        // Player space (GyroWiki): world yaw = rotation about gravity, sign and direction; local
        // yaw+roll magnitude caps it so an odd hold cannot invent a huge turn. Positive rotation
        // about *down* is clockwise seen from above — turning to the right.
        val worldYaw = wx * gx + wy * gy + wz * gz
        val localYawMag = sqrt(sq(wx * screenUp.x + wy * screenUp.y + wz * screenUp.z) + sq(wz))
        var yawRate = sign(worldYaw) * min(abs(worldYaw) * yawRelaxFactor, localYawMag)
        // Local pitch: rotation about the screen's horizontal axis. Positive brings the top edge
        // toward the player.
        var pitchRate = wx * screenRight.x + wy * screenRight.y + wz * screenRight.z

        // Tiered smoothing: the rolling average only for the slow, shaky part of the input.
        val magnitude = sqrt(yawRate * yawRate + pitchRate * pitchRate)
        val low = smoothThresholdDegS
        val high = smoothThresholdDegS * 2.0
        val directWeight = if (high > low) ((magnitude - low) / (high - low)).coerceIn(0.0, 1.0) else 1.0
        val smoothedYaw = pushSmoothed(yawRate * (1.0 - directWeight), pitchRate * (1.0 - directWeight), dt)
        yawRate = yawRate * directWeight + smoothedYaw
        pitchRate = pitchRate * directWeight + smoothedPitchLast

        // Tightening: squeeze tiny speeds toward zero instead of cutting them off.
        val filteredMag = sqrt(yawRate * yawRate + pitchRate * pitchRate)
        if (tighteningDegS > 0.0 && filteredMag < tighteningDegS) {
            val scale = filteredMag / tighteningDegS
            yawRate *= scale
            pitchRate *= scale
        }

        // Integrate, then let the look drift home while the phone is quiet.
        lookYawDeg = (lookYawDeg + yawRate * lookSensitivity * dt).coerceIn(-maxLookYawDeg, maxLookYawDeg)
        lookPitchDeg = (lookPitchDeg + pitchRate * lookSensitivity * dt).coerceIn(-maxLookPitchDeg, maxLookPitchDeg)
        val quietBelow = if (tighteningDegS > 0.0) tighteningDegS * 4.0 else QUIET_DEG_S
        val quiet = 1.0 - (filteredMag / quietBelow).coerceIn(0.0, 1.0)
        if (quiet > 0.0 && lookReturnS > 0.0) {
            val kReturn = (1.0 - exp(-dt / lookReturnS)) * quiet
            lookYawDeg -= lookYawDeg * kReturn
            lookPitchDeg -= lookPitchDeg * kReturn
        }
    }

    private var smoothedPitchLast = 0.0

    /** Pushes a sample into the rolling window sized for [smoothWindowS] and returns the yaw mean. */
    private fun pushSmoothed(yaw: Double, pitch: Double, dt: Double): Double {
        val wanted = (smoothWindowS / dt).toInt().coerceIn(1, SMOOTH_BUFFER_MAX)
        bufferYaw[bufferIndex] = yaw
        bufferPitch[bufferIndex] = pitch
        bufferIndex = (bufferIndex + 1) % wanted
        if (bufferCount < wanted) bufferCount++
        var sumY = 0.0
        var sumP = 0.0
        for (i in 0 until bufferCount) {
            sumY += bufferYaw[i]
            sumP += bufferPitch[i]
        }
        smoothedPitchLast = sumP / bufferCount
        return sumY / bufferCount
    }

    private fun deadZone(v: Double, zone: Double): Double {
        val m = abs(v)
        if (m <= zone) return 0.0
        return (m - zone) * (if (v < 0) -1.0 else 1.0)
    }

    private fun asinClamped(x: Double): Double = kotlin.math.asin(x.coerceIn(-1.0, 1.0))

    private fun sq(x: Double): Double = x * x

    private fun sign(x: Double): Double = if (x < 0) {
        -1.0
    } else if (x > 0) {
        1.0
    } else {
        0.0
    }

    private companion object {
        const val RAD_TO_DEG = 180.0 / kotlin.math.PI
        const val DEFAULT_DT = 0.02
        const val SMOOTH_BUFFER_MAX = 16
        const val QUIET_DEG_S = 10.0
        val WORLD_UP = Vector3(0.0, 0.0, 1.0)
        val SCREEN_NORMAL = Vector3(0.0, 0.0, 1.0)
    }
}
