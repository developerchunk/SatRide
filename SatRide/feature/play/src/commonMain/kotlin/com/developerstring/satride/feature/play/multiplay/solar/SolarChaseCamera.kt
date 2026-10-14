package com.developerstring.satride.feature.play.multiplay.solar

import com.developerstring.satride.feature.play.multiplay.engine.SphereMath
import com.developerstring.satride.feature.play.multiplay.render.LookState
import com.developerstring.satride.feature.play.render.CameraPose
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

/**
 * The starship's chase camera.
 *
 * Hangs behind the nose along the ship's own up, since there is no planet below to define one,
 * and eases like the arena's camera so turns read as the ship swinging in the frame. Two things
 * are different: the lens and the distance grow with *how many decades* of speed the ship is
 * doing rather than with the speed itself (there is no sensible linear scale from 30 km/s to a
 * thousand times light), and in a parking orbit the gaze dips toward the planet so it fills the
 * lower half of the picture.
 */
class SolarChaseCamera {

    private val camPos = DoubleArray(3)
    private val camTarget = DoubleArray(3)
    private val camUp = DoubleArray(3)
    private var initialised = false

    private val back = DoubleArray(3)
    private val right = DoubleArray(3)
    private val desired = DoubleArray(3)
    private val desiredTarget = DoubleArray(3)

    private var fov = 63.0
    private var orbitDip = 0.0

    var shakeKm: Double = 0.0
    private var shakePhase = 0.0

    fun snapTo(ship: SolarShipState) {
        initialised = false
        update(ship, LookState(), 1.0, CameraPose(), orbiting = true)
    }

    fun update(ship: SolarShipState, look: LookState, dt: Double, pose: CameraPose, orbiting: Boolean) {
        val up = ship.up
        back[0] = -ship.fwd[0]
        back[1] = -ship.fwd[1]
        back[2] = -ship.fwd[2]
        val yaw = look.totalYawDeg * SphereMath.DEG_TO_RAD
        if (yaw != 0.0) SphereMath.rotateAbout(back, up, -yaw)
        SphereMath.cross(up, back, right)
        SphereMath.normalize(right)
        // In orbit the camera looks steeply down so the world fills the lower half of the frame
        // (the planet's centre is a right angle below the nose); the pilot's own pitch adds to it.
        orbitDip += ((if (orbiting) ORBIT_DIP_DEG else 0.0) - orbitDip) * (1.0 - exp(-dt / 0.9))
        val pitch = (look.totalPitchDeg - orbitDip) * SphereMath.DEG_TO_RAD
        if (pitch != 0.0) SphereMath.rotateAbout(back, right, pitch)

        val decades = speedDecades(ship.speedKmS)
        val distance = CHASE_KM + decades * 30.0
        val lift = LIFT_KM + decades * 8.0

        desired[0] = ship.pos[0] + back[0] * distance + up[0] * lift
        desired[1] = ship.pos[1] + back[1] * distance + up[1] * lift
        desired[2] = ship.pos[2] + back[2] * distance + up[2] * lift

        desiredTarget[0] = ship.pos[0] - back[0] * AHEAD_KM - up[0] * AHEAD_DIP_KM
        desiredTarget[1] = ship.pos[1] - back[1] * AHEAD_KM - up[1] * AHEAD_DIP_KM
        desiredTarget[2] = ship.pos[2] - back[2] * AHEAD_KM - up[2] * AHEAD_DIP_KM

        if (!initialised) {
            SphereMath.copy(desired, camPos)
            SphereMath.copy(desiredTarget, camTarget)
            initialised = true
        } else {
            // The ship may have moved millions of kilometres this frame: ease the *offset* from
            // the ship, not the absolute position, or the camera would be left behind at warp.
            val kp = 1.0 - exp(-dt / POS_TAU_S)
            val kt = 1.0 - exp(-dt / TARGET_TAU_S)
            for (i in 0..2) {
                val travel = ship.pos[i] - ship.prev[i]
                camPos[i] += travel
                camTarget[i] += travel
                camPos[i] += (desired[i] - camPos[i]) * kp
                camTarget[i] += (desiredTarget[i] - camTarget[i]) * kt
            }
        }

        SphereMath.copy(up, camUp)
        val lean = ship.rollRad * 0.35 + look.leanDeg * SphereMath.DEG_TO_RAD
        if (lean != 0.0) {
            desired[0] = camTarget[0] - camPos[0]
            desired[1] = camTarget[1] - camPos[1]
            desired[2] = camTarget[2] - camPos[2]
            SphereMath.normalize(desired)
            SphereMath.rotateAbout(camUp, desired, lean)
        }

        if (shakeKm > 0.01) {
            shakePhase += dt * 55.0
            val sx = sin(shakePhase) * shakeKm
            val sy = cos(shakePhase * 1.31) * shakeKm
            camPos[0] += right[0] * sx + up[0] * sy
            camPos[1] += right[1] * sx + up[1] * sy
            camPos[2] += right[2] * sx + up[2] * sy
            shakeKm *= exp(-dt / 0.35)
        }

        val fovTarget = 63.0 + decades * 14.0
        fov += (fovTarget - fov) * (1.0 - exp(-dt / 0.5))

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
        const val ORBIT_DIP_DEG = 55.0

        /** 0 below a thousand km/s, 1 at a hundred million: five decades of "fast". */
        fun speedDecades(kmS: Double): Double = ((log10(kmS.coerceAtLeast(1.0)) - 3.0) / 5.0).coerceIn(0.0, 1.0)
    }
}
