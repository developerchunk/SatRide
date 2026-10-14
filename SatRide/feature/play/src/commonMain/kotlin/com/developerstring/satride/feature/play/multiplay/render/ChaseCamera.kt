package com.developerstring.satride.feature.play.multiplay.render

import com.developerstring.satride.feature.play.multiplay.engine.ShipState
import com.developerstring.satride.feature.play.multiplay.engine.SphereMath
import com.developerstring.satride.feature.play.render.CameraPose
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The player's free look, as an offset from straight ahead. Written by touch drags and by the
 * tilt sensor, read by the camera every frame; both inputs add, so tilting while dragging works.
 */
class LookState {
    var yawDeg: Double = 0.0
    var pitchDeg: Double = 0.0

    /** The tilt sensor's contribution, kept separate so recentring the drag leaves it alone. */
    var tiltYawDeg: Double = 0.0
    var tiltPitchDeg: Double = 0.0

    /** Extra lean from the phone's roll, degrees; purely a camera flourish. */
    var leanDeg: Double = 0.0

    fun drag(dxPx: Float, dyPx: Float, widthPx: Float) {
        if (widthPx <= 0f) return
        // First-person convention, matching the tilt camera: drag right looks right, drag up
        // looks up.
        val k = 140.0 / widthPx
        yawDeg = (yawDeg + dxPx * k).coerceIn(-150.0, 150.0)
        pitchDeg = (pitchDeg - dyPx * k).coerceIn(-70.0, 70.0)
    }

    fun recentre() {
        yawDeg = 0.0
        pitchDeg = 0.0
    }

    /** Drag look drifts back to centre on its own once released. */
    fun relax(dt: Double) {
        val k = 1.0 - exp(-dt / 1.6)
        yawDeg -= yawDeg * k
        pitchDeg -= pitchDeg * k
    }

    val totalYawDeg: Double get() = (yawDeg + tiltYawDeg).coerceIn(-170.0, 170.0)
    val totalPitchDeg: Double get() = (pitchDeg + tiltPitchDeg).coerceIn(-80.0, 80.0)
}

/**
 * A chase camera that hangs behind the craft's *velocity*, not its nose.
 *
 * That one choice is what makes a drift read: the nose swings toward the inside of the turn
 * while the camera keeps following where the ship is actually going, so the player sees their
 * craft slide sideways across the screen. A camera bolted to the nose would show a drift as
 * nothing at all.
 *
 * Position and target are both eased, with the target quicker than the position, which gives
 * the slight lag-then-catch-up of a camera on a spring rather than a camera on a stick.
 */
class ChaseCamera {

    private val camPos = DoubleArray(3)
    private val camTarget = DoubleArray(3)
    private val camUp = DoubleArray(3)
    private var initialised = false

    private val back = DoubleArray(3)
    private val right = DoubleArray(3)
    private val desired = DoubleArray(3)
    private val desiredTarget = DoubleArray(3)

    private var fov = 62.0

    /** Screen shake amplitude, km; set on a crash and decays. */
    var shakeKm: Double = 0.0
    private var shakePhase = 0.0

    fun snapTo(ship: ShipState) {
        initialised = false
        update(ship, LookState(), 1.0, CameraPose(), boosting = false, spinning = false)
    }

    @Suppress("LongParameterList")
    fun update(ship: ShipState, look: LookState, dt: Double, pose: CameraPose, boosting: Boolean, spinning: Boolean) {
        val up = ship.pos
        // Behind = -velocity, then swung by the free look about up (yaw) and right (pitch).
        back[0] = -ship.vel[0]
        back[1] = -ship.vel[1]
        back[2] = -ship.vel[2]
        val yaw = look.totalYawDeg * SphereMath.DEG_TO_RAD
        if (yaw != 0.0) SphereMath.rotateAbout(back, up, -yaw)
        SphereMath.cross(up, back, right)
        SphereMath.normalize(right)
        val pitch = look.totalPitchDeg * SphereMath.DEG_TO_RAD
        if (pitch != 0.0) SphereMath.rotateAbout(back, right, pitch)

        // Further back the faster it goes: speed reads as the world receding.
        val speedFrac = (ship.speedKmS / 350.0).coerceIn(0.0, 1.0)
        val distance = CHASE_KM + speedFrac * 28.0
        val lift = LIFT_KM + speedFrac * 6.0

        desired[0] = ship.world[0] + back[0] * distance + up[0] * lift
        desired[1] = ship.world[1] + back[1] * distance + up[1] * lift
        desired[2] = ship.world[2] + back[2] * distance + up[2] * lift

        // Look past the craft, slightly downward so the horizon sits high in the frame.
        desiredTarget[0] = ship.world[0] - back[0] * AHEAD_KM - up[0] * AHEAD_DIP_KM
        desiredTarget[1] = ship.world[1] - back[1] * AHEAD_KM - up[1] * AHEAD_DIP_KM
        desiredTarget[2] = ship.world[2] - back[2] * AHEAD_KM - up[2] * AHEAD_DIP_KM

        if (!initialised) {
            SphereMath.copy(desired, camPos)
            SphereMath.copy(desiredTarget, camTarget)
            initialised = true
        } else {
            val kp = 1.0 - exp(-dt / POS_TAU_S)
            val kt = 1.0 - exp(-dt / TARGET_TAU_S)
            for (i in 0..2) {
                camPos[i] += (desired[i] - camPos[i]) * kp
                camTarget[i] += (desiredTarget[i] - camTarget[i]) * kt
            }
        }

        // Up: the radial, leaned by the roll so banking tilts the horizon a little.
        SphereMath.copy(up, camUp)
        val lean = (ship.rollRad * 0.35) + look.leanDeg * SphereMath.DEG_TO_RAD
        if (lean != 0.0) {
            // Forward axis for the lean: from the camera to the target.
            desired[0] = camTarget[0] - camPos[0]
            desired[1] = camTarget[1] - camPos[1]
            desired[2] = camTarget[2] - camPos[2]
            SphereMath.normalize(desired)
            SphereMath.rotateAbout(camUp, desired, lean)
        }

        // Shake.
        if (shakeKm > 0.01) {
            shakePhase += dt * 55.0
            val sx = sin(shakePhase) * shakeKm
            val sy = cos(shakePhase * 1.31) * shakeKm
            camPos[0] += right[0] * sx + up[0] * sy
            camPos[1] += right[1] * sx + up[1] * sy
            camPos[2] += right[2] * sx + up[2] * sy
            shakeKm *= exp(-dt / 0.35)
        }

        // Lens: widens with boost, tightens slightly while spinning out.
        val fovTarget = when {
            spinning -> 58.0
            boosting -> 74.0
            else -> 63.0
        }
        fov += (fovTarget - fov) * (1.0 - exp(-dt / 0.3))

        pose.set(
            camPos[0], camPos[1], camPos[2],
            camTarget[0], camTarget[1], camTarget[2],
            camUp[0], camUp[1], camUp[2],
        )
        pose.fovDeg = fov
    }

    fun shake(amountKm: Double) {
        shakeKm = maxOf(shakeKm, amountKm)
    }

    private companion object {
        const val CHASE_KM = 52.0
        const val LIFT_KM = 15.0
        const val AHEAD_KM = 420.0
        const val AHEAD_DIP_KM = 30.0
        const val POS_TAU_S = 0.14
        const val TARGET_TAU_S = 0.09
    }
}
