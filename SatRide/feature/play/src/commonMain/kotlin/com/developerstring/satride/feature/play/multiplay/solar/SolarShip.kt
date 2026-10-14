package com.developerstring.satride.feature.play.multiplay.solar

import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.feature.play.multiplay.engine.SphereMath
import com.developerstring.satride.feature.play.solar.Body
import com.developerstring.satride.feature.play.solar.SolarWorld
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A starship in free flight: a position in heliocentric kilometres, a nose, an up, a speed.
 *
 * There is no sphere to stay on here — the orbital arena's ships fly a planet; these fly the
 * space between planets — so the state is a full frame rather than a unit vector and an
 * altitude. Mutable and read directly by the renderer, for the same reason as [ShipState].
 */
class SolarShipState {
    /** Heliocentric equatorial position, km. */
    val pos: DoubleArray = DoubleArray(3)

    /** Previous step's position, for the renderer's motion cues. */
    val prev: DoubleArray = DoubleArray(3)

    /** Unit nose direction. */
    val fwd: DoubleArray = doubleArrayOf(0.0, 1.0, 0.0)

    /** Unit "ceiling" direction, always perpendicular to [fwd]. */
    val up: DoubleArray = doubleArrayOf(0.0, 0.0, 1.0)

    var speedKmS: Double = 0.0

    /** Cruise setting as a multiple of the speed of light; the dial on the HUD. */
    var warp: Double = 1.0

    /** Visual bank, radians; eased toward the steering input. */
    var rollRad: Double = 0.0

    /** Engine flame 0..1, eased. */
    var flame: Double = 0.0

    /** Seconds of collision immunity after bouncing off a body. */
    var immuneS: Double = 0.0

    /** True while the approach governor is holding the speed below the dial. */
    var governed: Boolean = false

    /** Screen-shake request from the last step, km; consumed by the camera. */
    var bumpKm: Double = 0.0

    val boosting: Boolean get() = warp >= 1.0 && speedKmS > SolarPhysics.C * 0.5

    fun right(out: DoubleArray) {
        SphereMath.cross(fwd, up, out)
        SphereMath.normalize(out)
    }

    /** Re-orthonormalises [up] against [fwd] after either has been rotated. */
    fun orthonormalize() {
        SphereMath.normalize(fwd)
        SphereMath.tangentialize(up, fwd)
        if (!SphereMath.normalize(up)) {
            // fwd and up collapsed: pick any perpendicular.
            if (abs(fwd[2]) < 0.9) SphereMath.set(up, 0.0, 0.0, 1.0) else SphereMath.set(up, 1.0, 0.0, 0.0)
            SphereMath.tangentialize(up, fwd)
            SphereMath.normalize(up)
        }
    }

    /** Places the ship at [x], [y], [z] pointing along [fwd], with [up] as the ceiling, at rest. */
    @Suppress("LongParameterList")
    fun place(
        x: Double,
        y: Double,
        z: Double,
        fx: Double,
        fy: Double,
        fz: Double,
        ux: Double,
        uy: Double,
        uz: Double,
    ) {
        SphereMath.set(pos, x, y, z)
        SphereMath.copy(pos, prev)
        SphereMath.set(fwd, fx, fy, fz)
        SphereMath.set(up, ux, uy, uz)
        orthonormalize()
        speedKmS = 0.0
        rollRad = 0.0
        immuneS = 0.0
        bumpKm = 0.0
    }
}

/** The pilot's inputs for one physics step. */
class SolarInput {
    /** −1 (nose left) to +1 (nose right). */
    var steer: Double = 0.0

    /** −1 (nose down) to +1 (nose up). */
    var climb: Double = 0.0

    /** 0 = coast to a stop, 1 = cruise at the dial's speed. */
    var throttle: Double = 1.0

    var brake: Boolean = false

    fun clear() {
        steer = 0.0
        climb = 0.0
        throttle = 1.0
        brake = false
    }
}

/**
 * The interplanetary flight model.
 *
 * The one honest thing about it is the distances: Mars really is that far, and at one times the
 * speed of light it really does take minutes. Everything else is arcade. The dial sets a cruise
 * speed in multiples of c, the stick swings the nose, and the *approach governor* keeps the ship
 * from ever being less than a couple of seconds from the nearest surface at its current speed —
 * which is what turns "I set 100c and flew through Jupiter in one frame" into a graceful arrival.
 */
object SolarPhysics {

    const val C: Double = Constants.SPEED_OF_LIGHT_KM_S

    /** The dial's range: 30 km/s (a brisk orbit) to a thousand times light (Neptune in a quarter minute). */
    const val WARP_MIN: Double = 1e-4
    const val WARP_MAX: Double = 1000.0

    const val TURN_RATE_DEG_S: Double = 62.0
    private const val ACCEL_TAU_S: Double = 0.9
    private const val BRAKE_TAU_S: Double = 0.35
    private const val ROLL_TAU_S: Double = 0.2
    private const val MAX_ROLL_RAD: Double = 0.55

    /** The governor: never closer than this many seconds from the nearest surface. */
    const val APPROACH_SECONDS: Double = 2.5

    /** …but always allowed at least this fraction of the nearest body's radius per second. */
    const val FLOOR_RADII_PER_S: Double = 0.06

    /** Absolute floor so a tiny body never traps the ship at a crawl. */
    const val FLOOR_KM_S: Double = 6.0

    /** Bodies are solid at this fraction of their radius; the ship is pushed back out. */
    const val SURFACE_FACTOR: Double = 1.02
    private const val BOUNCE_SPEED_FRACTION: Double = 0.12
    private const val IMMUNE_AFTER_BOUNCE_S: Double = 1.5

    private val axis = DoubleArray(3)

    /** Cruise speed for a dial setting, km/s. */
    fun cruiseSpeed(warp: Double): Double = warp.coerceIn(WARP_MIN, WARP_MAX) * C

    /** The dial position 0..1 for a warp value — logarithmic, so every decade gets equal travel. */
    fun warpToDial(warp: Double): Double =
        (log10(warp.coerceIn(WARP_MIN, WARP_MAX)) - log10(WARP_MIN)) / (log10(WARP_MAX) - log10(WARP_MIN))

    fun dialToWarp(dial: Double): Double =
        10.0.pow(log10(WARP_MIN) + dial.coerceIn(0.0, 1.0) * (log10(WARP_MAX) - log10(WARP_MIN)))

    /** The dial setting that makes a trip of [distanceKm] take about [seconds]. */
    fun warpForTrip(distanceKm: Double, seconds: Double): Double =
        (distanceKm / (seconds.coerceAtLeast(1.0) * C)).coerceIn(WARP_MIN, WARP_MAX)

    /**
     * The fastest the ship may go at [pos] heading along unit [fwd]: for every body it is closing
     * on, the distance to that surface spread over [APPROACH_SECONDS] divided by how squarely it
     * is aimed at it, floored so arrivals finish and sightseeing stays brisk. A body the ship is
     * flying away from, or past, imposes nothing — leaving a planet is instant, arriving is not.
     */
    fun maxSafeSpeed(world: SolarWorld, pos: DoubleArray, fwd: DoubleArray): Double {
        var best = Double.MAX_VALUE
        for (body in world.bodies) {
            val dx = world.x(body) - pos[0]
            val dy = world.y(body) - pos[1]
            val dz = world.z(body) - pos[2]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist < 1e-6) continue
            val closing = (dx * fwd[0] + dy * fwd[1] + dz * fwd[2]) / dist
            if (closing <= CLOSING_MIN) continue
            val alt = dist - body.radiusKm * SURFACE_FACTOR
            val floor = maxOf(body.radiusKm * FLOOR_RADII_PER_S, FLOOR_KM_S)
            val limit = maxOf(alt / (APPROACH_SECONDS * closing), floor)
            if (limit < best) best = limit
        }
        return best
    }

    /** Below this cosine the ship is flying past a body, not at it, and is not governed by it. */
    private const val CLOSING_MIN: Double = 0.05

    /**
     * Advances [ship] by [dt] seconds under [input]. Returns the body bounced off, or null.
     *
     * [governed] applies the approach governor to the pilot's cruise speed; the autopilot always
     * governs itself and passes false here to avoid doing it twice.
     */
    fun step(ship: SolarShipState, input: SolarInput, dt: Double, world: SolarWorld, governed: Boolean): Body? {
        if (dt <= 0.0) return null
        SphereMath.copy(ship.pos, ship.prev)
        ship.bumpKm = 0.0

        val steer = input.steer.coerceIn(-1.0, 1.0)
        val climb = input.climb.coerceIn(-1.0, 1.0)

        // Yaw about up, pitch about right. The nose turns at the same rate at every speed —
        // this is a starship, not an aeroplane, and it steers like one.
        if (steer != 0.0) {
            SphereMath.rotateAbout(ship.fwd, ship.up, -steer * TURN_RATE_DEG_S * SphereMath.DEG_TO_RAD * dt)
        }
        if (climb != 0.0) {
            ship.right(axis)
            SphereMath.rotateAbout(ship.fwd, axis, climb * TURN_RATE_DEG_S * SphereMath.DEG_TO_RAD * dt)
            SphereMath.rotateAbout(ship.up, axis, climb * TURN_RATE_DEG_S * SphereMath.DEG_TO_RAD * dt)
        }
        ship.orthonormalize()

        // Speed: ease toward the dial (times the throttle), braking hard, governed if asked.
        var target = if (input.brake) 0.0 else cruiseSpeed(ship.warp) * input.throttle.coerceIn(0.0, 1.0)
        ship.governed = false
        if (governed && target > 0.0) {
            val safe = maxSafeSpeed(world, ship.pos, ship.fwd)
            if (safe < target) {
                target = safe
                ship.governed = true
            }
        }
        val tau = if (target < ship.speedKmS) BRAKE_TAU_S else ACCEL_TAU_S
        ship.speedKmS += (target - ship.speedKmS) * (1.0 - exp(-dt / tau))
        if (ship.speedKmS < 1e-3 && target == 0.0) ship.speedKmS = 0.0

        // Move.
        val step = ship.speedKmS * dt
        ship.pos[0] += ship.fwd[0] * step
        ship.pos[1] += ship.fwd[1] * step
        ship.pos[2] += ship.fwd[2] * step

        // Bank into the turn; ease so it reads as weight.
        val rollTarget = -steer * MAX_ROLL_RAD
        ship.rollRad += (rollTarget - ship.rollRad) * (1.0 - exp(-dt / ROLL_TAU_S))

        // Flame: proportional to how hard the engine is pushing.
        val flameTarget = if (input.brake) 0.15 else (0.25 + 0.75 * input.throttle).coerceIn(0.0, 1.0)
        ship.flame += (flameTarget - ship.flame) * (1.0 - exp(-dt / 0.2))

        if (ship.immuneS > 0.0) ship.immuneS = (ship.immuneS - dt).coerceAtLeast(0.0)

        return resolveCollisions(ship, world)
    }

    /** Planets are solid: a ship inside one is put back on the surface, slowed, and told so. */
    private fun resolveCollisions(ship: SolarShipState, world: SolarWorld): Body? {
        var hit: Body? = null
        for (body in world.bodies) {
            val surface = body.radiusKm * SURFACE_FACTOR
            val dx = ship.pos[0] - world.x(body)
            val dy = ship.pos[1] - world.y(body)
            val dz = ship.pos[2] - world.z(body)
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 >= surface * surface) continue
            val d = sqrt(d2).coerceAtLeast(1e-6)
            val k = surface / d
            ship.pos[0] = world.x(body) + dx * k
            ship.pos[1] = world.y(body) + dy * k
            ship.pos[2] = world.z(body) + dz * k
            if (ship.immuneS <= 0.0) {
                ship.speedKmS *= BOUNCE_SPEED_FRACTION
                ship.immuneS = IMMUNE_AFTER_BOUNCE_S
                ship.bumpKm = 8.0
                hit = body
            }
        }
        return hit
    }

    /**
     * Turns [ship]'s nose toward the unit direction [dir] by at most [maxRad] radians, keeping the
     * frame orthonormal. Returns the remaining angle to the target, radians.
     */
    fun turnToward(ship: SolarShipState, dir: DoubleArray, maxRad: Double): Double {
        val cosA = SphereMath.dot(ship.fwd, dir).coerceIn(-1.0, 1.0)
        val angle = acos(cosA)
        if (angle < 1e-6) return 0.0
        SphereMath.cross(ship.fwd, dir, axis)
        if (!SphereMath.normalize(axis)) {
            // Exactly behind: pivot about up.
            SphereMath.copy(ship.up, axis)
        }
        val turn = minOf(angle, maxRad)
        SphereMath.rotateAbout(ship.fwd, axis, turn)
        SphereMath.rotateAbout(ship.up, axis, turn)
        ship.orthonormalize()
        return angle - turn
    }

    /** Human-readable speed: km/s below a tenth of c, multiples of c above. */
    fun describeSpeed(kmS: Double): String {
        val c = kmS / C
        return when {
            kmS < 1000.0 -> "${kmS.toInt()} km/s"
            c < 0.1 -> "${(kmS / 1000.0).toInt()}k km/s"
            c < 10.0 -> "${formatDecimal(c, 2)} c"
            else -> "${c.toInt()} c"
        }
    }

    fun describeWarp(warp: Double): String = when {
        warp < 0.001 -> "${formatDecimal(warp * 1000.0, 1)} mc"
        warp < 0.1 -> "${formatDecimal(warp, 3)} c"
        warp < 10.0 -> "${formatDecimal(warp, 2)} c"
        else -> "${warp.toInt()} c"
    }

    /** Distances: km near a planet, then millions of km, then light-minutes and AU. */
    fun describeDistance(km: Double): String = when {
        km < 100_000.0 -> "${km.toInt()} km"
        km < 10_000_000.0 -> "${formatDecimal(km / 1_000_000.0, 2)}M km"
        km < 0.2 * Constants.AU_KM -> "${formatDecimal(km / (C * 60.0), 1)} light-min"
        else -> "${formatDecimal(km / Constants.AU_KM, 2)} AU"
    }

    fun describeEta(seconds: Double): String = when {
        seconds.isInfinite() || seconds.isNaN() || seconds > 36_000.0 -> "—"
        seconds < 90.0 -> "${seconds.toInt()} s"
        seconds < 5400.0 -> "${(seconds / 60.0).toInt()} min"
        else -> "${formatDecimal(seconds / 3600.0, 1)} h"
    }

    private fun formatDecimal(value: Double, places: Int): String {
        val scale = 10.0.pow(places)
        val rounded = kotlin.math.round(value * scale) / scale
        val whole = rounded.toLong()
        var frac = ((rounded - whole) * scale).toLong().toString()
        while (frac.length < places) frac = "0$frac"
        return if (places == 0) "$whole" else "$whole.$frac"
    }

    /** Natural log of the dial's ratio, for effects that scale with "how many decades of warp". */
    fun warpDecades(warp: Double): Double =
        (ln(warp.coerceIn(WARP_MIN, WARP_MAX)) - ln(WARP_MIN)) / ln(WARP_MAX / WARP_MIN)
}

/** Whether the autopilot flies the ship, only aims it, or leaves it alone. */
enum class PilotMode { MANUAL, AIM, AUTO }

/** Where the autopilot is in a trip. */
enum class PilotPhase { IDLE, TURNING, CRUISE, BRAKING, ORBIT }

/**
 * Auto direction and autopilot.
 *
 * *Aim* swings the nose onto the target and leaves the speed to the pilot. *Auto* does that and
 * flies the trip: up to the dial's speed, down to arrive, then a slow orbit at a parking distance
 * where the world fills a third of the frame. The pilot can take the stick at any moment; the
 * target is remembered so aim can be re-engaged with one tap.
 */
class Autopilot {
    var mode: PilotMode = PilotMode.MANUAL
        private set
    var phase: PilotPhase = PilotPhase.IDLE
        private set
    var target: Body? = null
        private set

    /** Seconds spent in orbit at the current target; bots use it to decide when to move on. */
    var orbitSeconds: Double = 0.0
        private set

    private val toTarget = DoubleArray(3)
    private val park = DoubleArray(3)
    private val radial = DoubleArray(3)
    private val tangent = DoubleArray(3)
    private var orbitAngle = 0.0

    fun setTarget(body: Body?) {
        if (body != target) {
            target = body
            orbitSeconds = 0.0
            if (mode != PilotMode.MANUAL) phase = if (body == null) PilotPhase.IDLE else PilotPhase.TURNING
        }
    }

    fun setMode(newMode: PilotMode) {
        if (newMode == mode) return
        mode = newMode
        phase = when {
            newMode == PilotMode.MANUAL -> PilotPhase.IDLE
            target == null -> PilotPhase.IDLE
            else -> PilotPhase.TURNING
        }
        orbitSeconds = 0.0
    }

    /** Drops straight into a parking orbit around [body] — how every expedition begins. */
    fun beginInOrbit(ship: SolarShipState, body: Body, world: SolarWorld, slot: Int, slots: Int) {
        target = body
        mode = PilotMode.AUTO
        phase = PilotPhase.ORBIT
        orbitSeconds = 0.0
        resetOrbitBasis()
        orbitAngle = 2.0 * kotlin.math.PI * slot / slots.coerceAtLeast(1)
        // Orbit plane: the body's equator, so Saturn's rings and Earth's continents sit level.
        radial[0] = world.pole[body.ordinal * 3]
        radial[1] = world.pole[body.ordinal * 3 + 1]
        radial[2] = world.pole[body.ordinal * 3 + 2]
        // e1 = anything ⟂ pole, e2 = pole × e1.
        if (abs(radial[2]) < 0.9) SphereMath.set(tangent, 0.0, 0.0, 1.0) else SphereMath.set(tangent, 1.0, 0.0, 0.0)
        SphereMath.tangentialize(tangent, radial)
        SphereMath.normalize(tangent)
        SphereMath.cross(radial, tangent, park)
        // radial := e1 cos θ + e2 sin θ ; tangent := −e1 sin θ + e2 cos θ
        val c = kotlin.math.cos(orbitAngle)
        val s = kotlin.math.sin(orbitAngle)
        val e1x = tangent[0]
        val e1y = tangent[1]
        val e1z = tangent[2]
        SphereMath.set(radial, e1x * c + park[0] * s, e1y * c + park[1] * s, e1z * c + park[2] * s)
        SphereMath.set(tangent, -e1x * s + park[0] * c, -e1y * s + park[1] * c, -e1z * s + park[2] * c)
        val r = body.parkRadiusKm
        ship.place(
            world.x(body) + radial[0] * r,
            world.y(body) + radial[1] * r,
            world.z(body) + radial[2] * r,
            tangent[0], tangent[1], tangent[2],
            radial[0], radial[1], radial[2],
        )
        ship.speedKmS = orbitSpeed(body)
    }

    /**
     * One step. In AUTO the ship is moved here and [SolarPhysics.step] must not run for it; in
     * AIM only the nose is turned and the caller steps the physics as usual. Returns true when
     * the ship arrived in orbit this step.
     */
    fun update(ship: SolarShipState, dt: Double, world: SolarWorld): Boolean {
        val body = target ?: return false
        if (mode == PilotMode.MANUAL) return false
        if (dt <= 0.0) return false

        // Parking point: on the parking sphere, on our side of the body.
        radial[0] = ship.pos[0] - world.x(body)
        radial[1] = ship.pos[1] - world.y(body)
        radial[2] = ship.pos[2] - world.z(body)
        val distCentre = SphereMath.length(radial)
        if (distCentre < 1e-6) return false
        radial[0] /= distCentre
        radial[1] /= distCentre
        radial[2] /= distCentre
        val parkR = body.parkRadiusKm
        park[0] = world.x(body) + radial[0] * parkR
        park[1] = world.y(body) + radial[1] * parkR
        park[2] = world.z(body) + radial[2] * parkR
        toTarget[0] = park[0] - ship.pos[0]
        toTarget[1] = park[1] - ship.pos[1]
        toTarget[2] = park[2] - ship.pos[2]
        val remaining = SphereMath.length(toTarget)

        if (mode == PilotMode.AIM) {
            if (remaining > 1.0) {
                toTarget[0] /= remaining
                toTarget[1] /= remaining
                toTarget[2] /= remaining
                val left = SolarPhysics.turnToward(ship, toTarget, AIM_TURN_RAD_S * dt)
                phase = if (left > 0.05) PilotPhase.TURNING else PilotPhase.CRUISE
                ship.rollRad += (bankFor(ship, toTarget) - ship.rollRad) * (1.0 - exp(-dt / 0.25))
            }
            return false
        }

        // AUTO.
        SphereMath.copy(ship.pos, ship.prev)
        ship.bumpKm = 0.0
        if (phase == PilotPhase.ORBIT) {
            orbitSeconds += dt
            holdOrbit(ship, body, world, dt)
            return false
        }

        val arriveRadius = parkR * ARRIVE_FRACTION
        if (remaining < arriveRadius && distCentre > body.radiusKm * SolarPhysics.SURFACE_FACTOR) {
            enterOrbit(ship, body, world)
            return true
        }

        toTarget[0] /= remaining
        toTarget[1] /= remaining
        toTarget[2] /= remaining
        val left = SolarPhysics.turnToward(ship, toTarget, AUTO_TURN_RAD_S * dt)
        ship.rollRad += (bankFor(ship, toTarget) - ship.rollRad) * (1.0 - exp(-dt / 0.25))

        // Speed profile: the dial's cruise, cut by the governor and by an exponential approach
        // that lands the ship on the parking sphere without overshooting.
        val cruise = SolarPhysics.cruiseSpeed(ship.warp)
        val approach = maxOf(remaining / ARRIVE_SECONDS, maxOf(body.radiusKm * SolarPhysics.FLOOR_RADII_PER_S, 6.0))
        var target = minOf(cruise, approach, SolarPhysics.maxSafeSpeed(world, ship.pos, ship.fwd))
        // Do not accelerate hard while still pointing the wrong way.
        if (left > 0.6) target = minOf(target, maxOf(ship.speedKmS * 0.9, 1.0))
        ship.governed = target < cruise * 0.999
        phase = when {
            left > 0.3 -> PilotPhase.TURNING
            approach < cruise -> PilotPhase.BRAKING
            else -> PilotPhase.CRUISE
        }
        val tau = if (target < ship.speedKmS) 0.4 else 0.9
        ship.speedKmS += (target - ship.speedKmS) * (1.0 - exp(-dt / tau))
        val step = minOf(ship.speedKmS * dt, remaining)
        ship.pos[0] += ship.fwd[0] * step
        ship.pos[1] += ship.fwd[1] * step
        ship.pos[2] += ship.fwd[2] * step
        ship.flame += (1.0 - ship.flame) * (1.0 - exp(-dt / 0.2))
        return false
    }

    private fun enterOrbit(ship: SolarShipState, body: Body, world: SolarWorld) {
        phase = PilotPhase.ORBIT
        orbitSeconds = 0.0
        // Orbit plane through the current radial and the nose.
        radial[0] = ship.pos[0] - world.x(body)
        radial[1] = ship.pos[1] - world.y(body)
        radial[2] = ship.pos[2] - world.z(body)
        SphereMath.normalize(radial)
        SphereMath.copy(ship.fwd, tangent)
        SphereMath.tangentialize(tangent, radial)
        if (!SphereMath.normalize(tangent)) {
            SphereMath.copy(ship.up, tangent)
            SphereMath.tangentialize(tangent, radial)
            SphereMath.normalize(tangent)
        }
        orbitAngle = 0.0
        SphereMath.copy(radial, orbitE1)
        SphereMath.copy(tangent, orbitE2)
        ship.speedKmS = orbitSpeed(body)
    }

    private val orbitE1 = DoubleArray(3)
    private val orbitE2 = DoubleArray(3)

    /** Circles the body at the parking distance; the frame follows the body as it moves. */
    private fun holdOrbit(ship: SolarShipState, body: Body, world: SolarWorld, dt: Double) {
        if (SphereMath.length(orbitE1) < 0.5) {
            // Placed by [beginInOrbit]: seed the basis from the ship's own frame.
            radial[0] = ship.pos[0] - world.x(body)
            radial[1] = ship.pos[1] - world.y(body)
            radial[2] = ship.pos[2] - world.z(body)
            SphereMath.normalize(radial)
            SphereMath.copy(radial, orbitE1)
            SphereMath.copy(ship.fwd, orbitE2)
            SphereMath.tangentialize(orbitE2, orbitE1)
            SphereMath.normalize(orbitE2)
            orbitAngle = 0.0
        }
        val r = body.parkRadiusKm
        val v = orbitSpeed(body)
        orbitAngle += v * dt / r
        val c = kotlin.math.cos(orbitAngle)
        val s = kotlin.math.sin(orbitAngle)
        for (i in 0..2) {
            radial[i] = orbitE1[i] * c + orbitE2[i] * s
            tangent[i] = -orbitE1[i] * s + orbitE2[i] * c
        }
        ship.pos[0] = world.x(body) + radial[0] * r
        ship.pos[1] = world.y(body) + radial[1] * r
        ship.pos[2] = world.z(body) + radial[2] * r
        SphereMath.copy(tangent, ship.fwd)
        SphereMath.copy(radial, ship.up)
        ship.orthonormalize()
        ship.speedKmS = v
        ship.rollRad += (0.0 - ship.rollRad) * (1.0 - exp(-dt / 0.4))
        ship.flame += (0.35 - ship.flame) * (1.0 - exp(-dt / 0.3))
    }

    /** Parking-orbit speed: one lap in [ORBIT_PERIOD_S] regardless of the body's size. */
    private fun orbitSpeed(body: Body): Double = 2.0 * kotlin.math.PI * body.parkRadiusKm / ORBIT_PERIOD_S

    /** Bank toward the side the nose is swinging to, from the target's offset in the ship's frame. */
    private fun bankFor(ship: SolarShipState, dir: DoubleArray): Double {
        val rx = ship.fwd[1] * ship.up[2] - ship.fwd[2] * ship.up[1]
        val ry = ship.fwd[2] * ship.up[0] - ship.fwd[0] * ship.up[2]
        val rz = ship.fwd[0] * ship.up[1] - ship.fwd[1] * ship.up[0]
        val side = (dir[0] * rx + dir[1] * ry + dir[2] * rz).coerceIn(-1.0, 1.0)
        return -side * 0.5
    }

    /** Distance still to fly to the parking point of the current target, km, or NaN. */
    fun remainingKm(ship: SolarShipState, world: SolarWorld): Double {
        val body = target ?: return Double.NaN
        val d = world.distanceTo(body, ship.pos[0], ship.pos[1], ship.pos[2]) - body.parkRadiusKm
        return d.coerceAtLeast(0.0)
    }

    /** Clears the orbit basis so the next orbit is seeded fresh. */
    fun resetOrbitBasis() {
        orbitE1.fill(0.0)
        orbitE2.fill(0.0)
    }

    companion object {
        const val ORBIT_PERIOD_S: Double = 48.0
        private const val ARRIVE_SECONDS: Double = 1.5
        private const val ARRIVE_FRACTION: Double = 0.1
        private const val AUTO_TURN_RAD_S: Double = 1.6
        private const val AIM_TURN_RAD_S: Double = 1.2
    }
}
