package com.developerstring.satride.feature.play.multiplay.engine

import com.developerstring.satride.core.model.SatClass
import kotlin.math.abs
import kotlin.math.exp

/**
 * One craft in the arena: where it is, where it points, how fast it goes, and how it feels.
 *
 * Mutable on purpose. The physics step runs at frame rate and the renderer reads the arrays
 * directly; a data class copied sixty times a second for ten ships would be sixty allocations
 * a frame for nothing.
 */
class ShipState {
    /** Unit vector from the Earth's centre. */
    val pos: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0)

    /** Unit tangent: where the nose points. */
    val hdg: DoubleArray = doubleArrayOf(0.0, 1.0, 0.0)

    /** Unit tangent: where the craft is actually going. Lags [hdg] while drifting. */
    val vel: DoubleArray = doubleArrayOf(0.0, 1.0, 0.0)

    var altKm: Double = ShipPhysics.ALT_START_KM
    var speedKmS: Double = 0.0
    var climbRateKmS: Double = 0.0

    /** Visual bank, radians; eased toward the steering input. */
    var rollRad: Double = 0.0

    var drifting: Boolean = false

    /** Seconds of drift built up, spent as a boost on release. */
    var driftCharge: Double = 0.0

    /** Seconds of boost remaining. */
    var boostTimeS: Double = 0.0

    /** Stored boost charges (from drifts and orbs), spent with the boost button. */
    var boostCharges: Int = 0

    /** Seconds of spin-out remaining after a crash; controls are dead while > 0. */
    var spinOutS: Double = 0.0

    /** Seconds of collision immunity after a hit, so one bump is not three. */
    var immuneS: Double = 0.0

    var skin: SatClass = SatClass.OTHER

    /** Engine flame intensity 0..1 for the renderer, eased. */
    var flame: Double = 0.0

    /** World position scratch, km, refreshed each step: pos × (R + alt). */
    val world: DoubleArray = DoubleArray(3)

    /** Previous step's world position, for swept collisions. */
    val worldPrev: DoubleArray = DoubleArray(3)

    val boosting: Boolean get() = boostTimeS > 0.0

    fun refreshWorld() {
        val r = SphereMath.EARTH_RADIUS_KM + altKm
        world[0] = pos[0] * r
        world[1] = pos[1] * r
        world[2] = pos[2] * r
    }

    /** Places the ship at a latitude/longitude with a compass heading, stationary. */
    fun placeAt(latDeg: Double, lonDeg: Double, headingDeg: Double, altKm: Double) {
        SphereMath.fromLatLon(latDeg, lonDeg, pos)
        SphereMath.tangentFromHeading(pos, headingDeg, hdg)
        SphereMath.copy(hdg, vel)
        this.altKm = altKm
        speedKmS = 0.0
        climbRateKmS = 0.0
        rollRad = 0.0
        drifting = false
        driftCharge = 0.0
        boostTimeS = 0.0
        spinOutS = 0.0
        immuneS = 0.0
        refreshWorld()
        SphereMath.copy(world, worldPrev)
    }

    /** Places the ship at a unit position pointing along a tangent. */
    fun placeAt(unitPos: DoubleArray, tangent: DoubleArray, altKm: Double) {
        SphereMath.copy(unitPos, pos)
        SphereMath.normalize(pos)
        SphereMath.copy(tangent, hdg)
        SphereMath.tangentialize(hdg, pos)
        SphereMath.normalize(hdg)
        SphereMath.copy(hdg, vel)
        this.altKm = altKm
        speedKmS = 0.0
        climbRateKmS = 0.0
        rollRad = 0.0
        drifting = false
        driftCharge = 0.0
        boostTimeS = 0.0
        spinOutS = 0.0
        immuneS = 0.0
        refreshWorld()
        SphereMath.copy(world, worldPrev)
    }
}

/**
 * The flight model.
 *
 * Arcade, not orbital mechanics: a real satellite cannot steer and takes ninety minutes a lap,
 * neither of which is a game. Speeds here are exaggerated so a lap of the planet takes about
 * three minutes, and the craft turns because the stick says so. What is kept from the real thing
 * is the *geometry* — the ship flies a sphere, gates sit at real altitudes over real coastlines,
 * and the same Earth the Play tab draws is the one being raced over.
 *
 * ## Drift
 *
 * Holding drift decouples the nose from the velocity: the nose swings fast, the velocity follows
 * slowly, and the angle between them charges a boost that fires on release. It is the Mario Kart
 * drift, and it is what makes steering through debris a skill rather than a chore.
 */
object ShipPhysics {

    const val ALT_MIN_KM: Double = 320.0
    const val ALT_MAX_KM: Double = 1500.0
    const val ALT_START_KM: Double = 620.0

    /** Cruise speed with the throttle open. ~3.2 minutes a lap of the planet. */
    const val BASE_SPEED_KMS: Double = 215.0
    const val IDLE_FRACTION: Double = 0.42
    const val BOOST_MULTIPLIER: Double = 1.62
    const val CLIMB_RATE_KMS: Double = 95.0

    const val TURN_RATE_DEG_S: Double = 52.0
    const val DRIFT_TURN_MULTIPLIER: Double = 1.9

    /** How fast velocity follows the nose, seconds. Long while drifting: that is the drift. */
    private const val ALIGN_TAU_S: Double = 0.22
    private const val DRIFT_ALIGN_TAU_S: Double = 1.35
    private const val ACCEL_TAU_S: Double = 0.9
    private const val CLIMB_TAU_S: Double = 0.35
    private const val ROLL_TAU_S: Double = 0.18
    private const val MAX_ROLL_RAD: Double = 0.62

    /** Drift needs at least this much charge to pay out, and pays out this long per second held. */
    const val DRIFT_MIN_CHARGE_S: Double = 0.55
    const val DRIFT_BOOST_S_PER_CHARGE_S: Double = 0.85
    const val DRIFT_MAX_BOOST_S: Double = 2.4
    const val BUTTON_BOOST_S: Double = 1.6
    const val MAX_BOOST_CHARGES: Int = 3

    const val SPIN_OUT_S: Double = 1.15
    const val IMMUNE_AFTER_HIT_S: Double = 1.8
    const val CRASH_SPEED_FRACTION: Double = 0.42

    /** Advances [ship] by [dt] seconds under [input]. Returns true if a drift boost fired. */
    fun step(ship: ShipState, input: ShipInput, dt: Double): Boolean {
        if (dt <= 0.0) return false
        SphereMath.copy(ship.world, ship.worldPrev)
        var driftFired = false

        val controllable = ship.spinOutS <= 0.0
        val steer = if (controllable) input.steer.coerceIn(-1.0, 1.0) else 0.0
        val climb = if (controllable) input.climb.coerceIn(-1.0, 1.0) else 0.0
        val throttle = if (controllable) input.throttle.coerceIn(0.0, 1.0) else 0.0

        // Drift state: held and actually turning. Releasing pays out what was charged.
        val wantDrift = controllable && input.drift && abs(steer) > 0.15
        if (ship.drifting && !wantDrift) {
            if (ship.driftCharge >= DRIFT_MIN_CHARGE_S) {
                val boost = (ship.driftCharge * DRIFT_BOOST_S_PER_CHARGE_S).coerceAtMost(DRIFT_MAX_BOOST_S)
                ship.boostTimeS = maxOf(ship.boostTimeS, boost)
                driftFired = true
            }
            ship.driftCharge = 0.0
        }
        ship.drifting = wantDrift
        if (ship.drifting) ship.driftCharge += dt

        if (controllable && input.boost && ship.boostCharges > 0 && ship.boostTimeS < 0.3) {
            ship.boostCharges--
            ship.boostTimeS = BUTTON_BOOST_S
        }

        // Steering rotates the nose about the local vertical.
        val turnRate = TURN_RATE_DEG_S * (if (ship.drifting) DRIFT_TURN_MULTIPLIER else 1.0)
        if (steer != 0.0) {
            // Left is positive about the outward normal (right-handed), and the stick's +1 is
            // right, hence the sign.
            SphereMath.rotateAbout(ship.hdg, ship.pos, -steer * turnRate * SphereMath.DEG_TO_RAD * dt)
            SphereMath.tangentialize(ship.hdg, ship.pos)
            SphereMath.normalize(ship.hdg)
        }
        // A spinning-out ship keeps spinning.
        if (!controllable) {
            SphereMath.rotateAbout(ship.hdg, ship.pos, 5.5 * dt)
            SphereMath.tangentialize(ship.hdg, ship.pos)
            SphereMath.normalize(ship.hdg)
        }

        // Velocity eases toward the nose: quickly in normal flight, slowly in a drift.
        val tau = if (ship.drifting) DRIFT_ALIGN_TAU_S else ALIGN_TAU_S
        val k = 1.0 - exp(-dt / tau)
        ship.vel[0] += (ship.hdg[0] - ship.vel[0]) * k
        ship.vel[1] += (ship.hdg[1] - ship.vel[1]) * k
        ship.vel[2] += (ship.hdg[2] - ship.vel[2]) * k
        SphereMath.tangentialize(ship.vel, ship.pos)
        if (!SphereMath.normalize(ship.vel)) SphereMath.copy(ship.hdg, ship.vel)

        // Speed.
        val boosting = ship.boostTimeS > 0.0
        if (boosting) ship.boostTimeS = (ship.boostTimeS - dt).coerceAtLeast(0.0)
        var target = BASE_SPEED_KMS * (IDLE_FRACTION + (1.0 - IDLE_FRACTION) * throttle)
        if (boosting) target *= BOOST_MULTIPLIER
        // Sliding sideways scrubs a little speed — the trade drift makes.
        if (ship.drifting) target *= 0.9
        val accelK = 1.0 - exp(-dt / ACCEL_TAU_S)
        ship.speedKmS += (target - ship.speedKmS) * accelK

        // Altitude.
        val climbTarget = climb * CLIMB_RATE_KMS
        val climbK = 1.0 - exp(-dt / CLIMB_TAU_S)
        ship.climbRateKmS += (climbTarget - ship.climbRateKmS) * climbK
        ship.altKm = (ship.altKm + ship.climbRateKmS * dt).coerceIn(ALT_MIN_KM, ALT_MAX_KM)
        if (ship.altKm <= ALT_MIN_KM || ship.altKm >= ALT_MAX_KM) ship.climbRateKmS = 0.0

        // Move along the velocity tangent.
        val radius = SphereMath.EARTH_RADIUS_KM + ship.altKm
        val arc = ship.speedKmS * dt / radius
        if (arc > 0.0) {
            // step() transports one tangent; the heading is re-projected after.
            SphereMath.step(ship.pos, ship.vel, arc)
            SphereMath.tangentialize(ship.hdg, ship.pos)
            if (!SphereMath.normalize(ship.hdg)) SphereMath.copy(ship.vel, ship.hdg)
        }

        // Bank into the turn, more in a drift; ease so the roll reads as weight.
        val rollTarget = -steer * MAX_ROLL_RAD * (if (ship.drifting) 1.35 else 1.0)
        val rollK = 1.0 - exp(-dt / ROLL_TAU_S)
        ship.rollRad += (rollTarget - ship.rollRad) * rollK

        // Flame.
        val flameTarget = if (boosting) 1.0 else throttle * 0.55
        ship.flame += (flameTarget - ship.flame) * (1.0 - exp(-dt / 0.15))

        if (ship.spinOutS > 0.0) ship.spinOutS = (ship.spinOutS - dt).coerceAtLeast(0.0)
        if (ship.immuneS > 0.0) ship.immuneS = (ship.immuneS - dt).coerceAtLeast(0.0)

        ship.refreshWorld()
        return driftFired
    }

    /** What a collision does: a spin-out, a speed cut, a short immunity, and the drift dropped. */
    fun crash(ship: ShipState) {
        ship.spinOutS = SPIN_OUT_S
        ship.immuneS = SPIN_OUT_S + IMMUNE_AFTER_HIT_S
        ship.speedKmS *= CRASH_SPEED_FRACTION
        ship.boostTimeS = 0.0
        ship.drifting = false
        ship.driftCharge = 0.0
    }

    /** A boost pickup: one more charge, capped. */
    fun grantCharge(ship: ShipState) {
        ship.boostCharges = (ship.boostCharges + 1).coerceAtMost(MAX_BOOST_CHARGES)
    }

    /** Whether the ship is currently a valid target for collisions. */
    fun canCollide(ship: ShipState): Boolean = ship.immuneS <= 0.0 && ship.spinOutS <= 0.0
}
