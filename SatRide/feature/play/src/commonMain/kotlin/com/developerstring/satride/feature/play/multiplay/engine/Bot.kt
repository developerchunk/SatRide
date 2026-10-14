package com.developerstring.satride.feature.play.multiplay.engine

import kotlin.math.abs
import kotlin.math.sin

/**
 * A computer-driven racer: flies the gates, dodges what it sees, and is beatable.
 *
 * Bots exist so the arena is never empty. A solo practice run has other craft to race, a room
 * with two friends can be filled to a grid, and someone whose connection drops still has a race
 * to finish. They read the same track and the same debris as everyone else and drive through the
 * same [ShipInput] a thumb does — there is no bot-only physics.
 */
class Bot(val name: String, private val skill: Double, seed: Long) {

    val ship = ShipState()
    val progress = Progress()
    val input = ShipInput()

    private val rng = SeededRandom(seed)
    private val target = DoubleArray(3)
    private val targetDir = DoubleArray(3)
    private val toTarget = DoubleArray(3)
    private val phase = rng.nextDouble() * 100.0

    /** Per-bot lateral aim bias, re-rolled each gate: what makes ten bots take ten lines. */
    private var aimBiasKm = 0.0
    private var aimGate = -1

    /** Whether the bot is committed to a boost this gate. */
    private var boostPlanned = false

    fun drive(track: Track, debris: DebrisField, timeS: Double) {
        if (!progress.alive) {
            input.clear()
            input.throttle = 0.0
            return
        }
        val g = progress.nextGate
        if (g != aimGate) {
            aimGate = g
            aimBiasKm = (rng.nextDouble() - 0.5) * (1.0 - skill) * 60.0
            boostPlanned = rng.nextDouble() < 0.25 + skill * 0.4
        }
        // Aim at the gate centre, offset by the bias along the corridor's lateral.
        track.gateWorld(g, target)
        track.lateralAt(2.0 * kotlin.math.PI * g / track.gateCount, targetDir)
        target[0] += targetDir[0] * aimBiasKm
        target[1] += targetDir[1] * aimBiasKm
        target[2] += targetDir[2] * aimBiasKm
        val tMag = SphereMath.length(target)
        targetDir[0] = target[0] / tMag
        targetDir[1] = target[1] / tMag
        targetDir[2] = target[2] / tMag

        // Steering: the signed angle from the nose to the bearing of the gate.
        if (!SphereMath.directionToward(ship.pos, targetDir, toTarget)) {
            input.clear()
            return
        }
        var angle = SphereMath.signedTangentAngle(ship.hdg, toTarget, ship.pos)
        // Gentle weave — bots that fly ruler-straight look like bots.
        angle += sin(timeS * 0.7 + phase) * 0.04 * (1.0 - skill)

        // Avoidance: anything within the next 260 km and close to the line gets steered around.
        var avoid = 0.0
        val lookAheadSq = 260.0 * 260.0
        for (i in 0 until debris.activeCount) {
            val dx = debris.world[i * 3] - ship.world[0]
            val dy = debris.world[i * 3 + 1] - ship.world[1]
            val dz = debris.world[i * 3 + 2] - ship.world[2]
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > lookAheadSq) continue
            val along = dx * ship.vel[0] + dy * ship.vel[1] + dz * ship.vel[2]
            if (along < 20.0) continue
            // Lateral offset, signed left/right about the local vertical.
            val lx = dx - ship.vel[0] * along
            val ly = dy - ship.vel[1] * along
            val lz = dz - ship.vel[2] * along
            val cx = ship.vel[1] * ship.pos[2] - ship.vel[2] * ship.pos[1]
            val cy = ship.vel[2] * ship.pos[0] - ship.vel[0] * ship.pos[2]
            val cz = ship.vel[0] * ship.pos[1] - ship.vel[1] * ship.pos[0]
            val side = lx * cx + ly * cy + lz * cz
            val lateral = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz)
            val danger = debris.radiusKm[i] + RaceRules.SHIP_RADIUS_KM + 22.0
            if (lateral < danger) {
                // Steer away from the side it is on, harder the closer it is.
                val strength = (1.0 - along / 260.0) * (1.0 - lateral / danger)
                avoid += (if (side >= 0) 1.0 else -1.0) * strength * 0.9
            }
        }
        // Slower reactions for weaker bots: they see the debris late.
        val reaction = 0.55 + skill * 0.45
        val steer = ((-angle * 2.2) + avoid * reaction).coerceIn(-1.0, 1.0)
        input.steer = steer
        input.drift = abs(angle) > 0.42 && skill > 0.35
        input.boost = boostPlanned && ship.boostCharges > 0 && abs(angle) < 0.2
        if (input.boost) boostPlanned = false

        // Altitude toward the gate's, with a little anticipation.
        val altError = track.gateAltKm[g] - ship.altKm
        input.climb = (altError / 120.0).coerceIn(-1.0, 1.0)
        input.throttle = if (abs(angle) > 0.9) 0.55 else 0.85 + skill * 0.15
    }
}
