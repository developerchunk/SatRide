package com.developerstring.satride.feature.play.multiplay.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The flight model's invariants: a craft stays on its sphere, inside its altitude band, turns
 * the way the stick says, and a drift pays out a boost.
 */
class ShipPhysicsTest {

    private fun ship(): ShipState = ShipState().apply {
        placeAt(latDeg = 0.0, lonDeg = 0.0, headingDeg = 0.0, altKm = 600.0)
    }

    @Test
    fun positionStaysUnitAndTangentsStayTangentOverALongFlight() {
        val ship = ship()
        val input = ShipInput().apply {
            throttle = 1.0
            steer = 0.6
            climb = 0.3
        }
        repeat(6000) { ShipPhysics.step(ship, input, 1.0 / 60.0) }
        assertEquals(1.0, SphereMath.length(ship.pos), 1e-9, "position drifted off the unit sphere")
        assertEquals(1.0, SphereMath.length(ship.hdg), 1e-9)
        assertEquals(1.0, SphereMath.length(ship.vel), 1e-9)
        assertTrue(abs(SphereMath.dot(ship.hdg, ship.pos)) < 1e-9, "heading is not tangent")
        assertTrue(abs(SphereMath.dot(ship.vel, ship.pos)) < 1e-9, "velocity is not tangent")
    }

    @Test
    fun altitudeIsClampedToTheBand() {
        val ship = ship()
        val up = ShipInput().apply { climb = 1.0 }
        repeat(3000) { ShipPhysics.step(ship, up, 1.0 / 60.0) }
        assertEquals(ShipPhysics.ALT_MAX_KM, ship.altKm, 1e-9)
        val down = ShipInput().apply { climb = -1.0 }
        repeat(3000) { ShipPhysics.step(ship, down, 1.0 / 60.0) }
        assertEquals(ShipPhysics.ALT_MIN_KM, ship.altKm, 1e-9)
    }

    @Test
    fun steeringRightTurnsTheNoseClockwise() {
        // At the equator heading north, a right turn brings the nose toward east.
        val ship = ship()
        val before = SphereMath.headingDeg(ship.pos, ship.hdg)
        assertEquals(0.0, before, 1e-6)
        val input = ShipInput().apply {
            steer = 1.0
            throttle = 0.0
        }
        repeat(30) { ShipPhysics.step(ship, input, 1.0 / 60.0) }
        val after = SphereMath.headingDeg(ship.pos, ship.hdg)
        assertTrue(after in 5.0..60.0, "expected a clockwise turn, heading is $after°")
    }

    @Test
    fun speedApproachesCruiseAndBoostExceedsIt() {
        val ship = ship()
        val input = ShipInput().apply { throttle = 1.0 }
        repeat(600) { ShipPhysics.step(ship, input, 1.0 / 60.0) }
        assertEquals(ShipPhysics.BASE_SPEED_KMS, ship.speedKmS, 2.0)
        ship.boostCharges = 1
        input.boost = true
        ShipPhysics.step(ship, input, 1.0 / 60.0)
        input.boost = false
        repeat(60) { ShipPhysics.step(ship, input, 1.0 / 60.0) }
        assertTrue(ship.speedKmS > ShipPhysics.BASE_SPEED_KMS * 1.2, "boost did not raise the speed: ${ship.speedKmS}")
        assertEquals(0, ship.boostCharges)
    }

    @Test
    fun releasingAHeldDriftFiresABoost() {
        val ship = ship()
        val input = ShipInput().apply {
            throttle = 1.0
            steer = 1.0
            drift = true
        }
        repeat(90) { ShipPhysics.step(ship, input, 1.0 / 60.0) } // 1.5 s of drift
        assertTrue(ship.drifting)
        assertTrue(ship.driftCharge >= ShipPhysics.DRIFT_MIN_CHARGE_S)
        input.drift = false
        val fired = ShipPhysics.step(ship, input, 1.0 / 60.0)
        assertTrue(fired, "drift release should pay out")
        assertTrue(ship.boostTimeS > 0.0)
    }

    @Test
    fun aCrashKillsControlAndGrantsImmunity() {
        val ship = ship()
        val input = ShipInput().apply { throttle = 1.0 }
        repeat(300) { ShipPhysics.step(ship, input, 1.0 / 60.0) }
        val speed = ship.speedKmS
        ShipPhysics.crash(ship)
        assertTrue(ship.speedKmS < speed * 0.5)
        assertTrue(!ShipPhysics.canCollide(ship))
        repeat(240) { ShipPhysics.step(ship, input, 1.0 / 60.0) } // 4 s: spin-out and immunity elapse
        assertTrue(ShipPhysics.canCollide(ship))
    }
}
