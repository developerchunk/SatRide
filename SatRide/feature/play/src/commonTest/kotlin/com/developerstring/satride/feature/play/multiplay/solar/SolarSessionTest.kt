package com.developerstring.satride.feature.play.multiplay.solar

import com.developerstring.satride.feature.play.multiplay.engine.GameEvent
import com.developerstring.satride.feature.play.multiplay.engine.GameEventKind
import com.developerstring.satride.feature.play.multiplay.engine.GamePhase
import com.developerstring.satride.feature.play.solar.Body
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The expedition's invariants: the autopilot gets there and parks, nothing ever ends up inside
 * a planet, the governor holds the speed near a surface, and the dial round-trips.
 */
class SolarSessionTest {

    private val t0 = 1_788_000_000_000L

    private fun session(bots: Int = 0): SolarSession = SolarSession(
        seed = 7L,
        localUid = "me",
        localName = "Me",
        localColorArgb = 0xFF3FE0FF,
        botCount = bots,
        botSeed = 9L,
        localSlot = 0,
    )
        .apply { schedule(startMs = t0, endMs = t0 + 3_600_000L, nowMs = t0) }

    private fun SolarSession.run(seconds: Double, from: Long): Long {
        var now = from
        val steps = (seconds * 60).toInt()
        repeat(steps) {
            now += 16L
            tick(now)
        }
        return now
    }

    @Test
    fun startsInOrbitAroundTheEarth() {
        val s = session()
        assertEquals(GamePhase.RUNNING, s.phase)
        assertEquals(PilotMode.AUTO, s.autopilot.mode)
        assertEquals(PilotPhase.ORBIT, s.autopilot.phase)
        val d = s.world.distanceTo(Body.EARTH, s.local.pos[0], s.local.pos[1], s.local.pos[2])
        assertEquals(Body.EARTH.parkRadiusKm, d, 1.0)
        s.run(5.0, t0)
        val d2 = s.world.distanceTo(Body.EARTH, s.local.pos[0], s.local.pos[1], s.local.pos[2])
        assertEquals(Body.EARTH.parkRadiusKm, d2, 1.0, "the parking orbit should hold")
    }

    @Test
    fun autopilotFliesToMarsAndParks() {
        val s = session()
        s.setTarget(Body.MARS)
        s.setPilotMode(PilotMode.AUTO)
        assertTrue(s.autoSpeed, "auto speed sets the dial for a half-minute trip")
        val events = ArrayList<GameEvent>()
        var now = t0
        var arrived = false
        repeat(90 * 60) {
            now += 16L
            s.tick(now)
            s.drainEvents(events)
            if (s.autopilot.phase == PilotPhase.ORBIT && s.autopilot.target == Body.MARS) arrived = true
            if (arrived) return@repeat
        }
        assertTrue(arrived, "should reach Mars within 90 s; phase ${s.autopilot.phase}")
        val d = s.world.distanceTo(Body.MARS, s.local.pos[0], s.local.pos[1], s.local.pos[2])
        assertEquals(Body.MARS.parkRadiusKm, d, Body.MARS.parkRadiusKm * 0.02)
        assertTrue(
            events.any {
                it.kind == GameEventKind.DISCOVERY && it.detail == "MARS"
            },
            "Mars should be discovered",
        )
        assertTrue(events.any { it.kind == GameEventKind.ARRIVED }, "arrival should be announced")
        assertTrue(s.localProgress.visited[Body.MARS.ordinal])
        assertTrue(s.localProgress.score >= 100)
    }

    @Test
    fun manualFlightNeverEntersAPlanetAndTheGovernorHoldsSpeed() {
        val s = session()
        // Point straight at the Earth from the parking orbit at full warp, governor on.
        s.takeManualControl()
        s.setWarp(1000.0)
        s.governorEnabled = true
        val ship = s.local
        val toEarth = DoubleArray(3)
        for (i in 0..2) toEarth[i] = s.world.pos[Body.EARTH.ordinal * 3 + i] - ship.pos[i]
        val m = sqrt(toEarth[0] * toEarth[0] + toEarth[1] * toEarth[1] + toEarth[2] * toEarth[2])
        for (i in 0..2) toEarth[i] /= m
        SolarPhysics.turnToward(ship, toEarth, 10.0)
        var now = t0
        var maxSpeed = 0.0
        var minAlt = Double.MAX_VALUE
        repeat(20 * 60) {
            now += 16L
            s.tick(now)
            maxSpeed = maxOf(maxSpeed, ship.speedKmS)
            minAlt = minOf(minAlt, s.altitudeAbove(Body.EARTH))
        }
        assertTrue(
            minAlt >= Body.EARTH.radiusKm * (SolarPhysics.SURFACE_FACTOR - 1.0) - 1.0,
            "went inside the Earth: $minAlt km",
        )
        // From 14,000 km up at 2.5 s of approach, the governor allows at most ~5,600 km/s.
        assertTrue(maxSpeed < 8_000.0, "governor let the ship reach $maxSpeed km/s")
        assertTrue(ship.governed || ship.speedKmS < 1000.0)
    }

    @Test
    fun withoutTheGovernorAPlanetIsSolid() {
        val s = session()
        s.takeManualControl()
        s.governorEnabled = false
        s.setWarp(0.01)
        val ship = s.local
        val toEarth = DoubleArray(3)
        for (i in 0..2) toEarth[i] = s.world.pos[Body.EARTH.ordinal * 3 + i] - ship.pos[i]
        val m = sqrt(toEarth[0] * toEarth[0] + toEarth[1] * toEarth[1] + toEarth[2] * toEarth[2])
        for (i in 0..2) toEarth[i] /= m
        SolarPhysics.turnToward(ship, toEarth, 10.0)
        val events = ArrayList<GameEvent>()
        var now = t0
        repeat(30 * 60) {
            now += 16L
            s.tick(now)
            s.drainEvents(events)
        }
        assertTrue(events.any { it.kind == GameEventKind.BOUNCE }, "should have bounced off the Earth")
        val alt = s.altitudeAbove(Body.EARTH)
        assertTrue(alt >= Body.EARTH.radiusKm * (SolarPhysics.SURFACE_FACTOR - 1.0) - 1.0, "inside the Earth: $alt")
    }

    @Test
    fun theDialRoundTripsAndTripTimingIsSane() {
        for (w in listOf(1e-4, 0.01, 1.0, 37.0, 1000.0)) {
            val back = SolarPhysics.dialToWarp(SolarPhysics.warpToDial(w))
            assertTrue(abs(back - w) / w < 1e-9, "dial round trip $w -> $back")
        }
        // One AU in 30 s is about 16.6 c.
        val w = SolarPhysics.warpForTrip(SolarSession.AU_KM, 30.0)
        assertTrue(w in 16.0..17.5, "warp for 1 AU in 30 s: $w")
        assertEquals("1.00 c", SolarPhysics.describeWarp(1.0))
        assertEquals("1.00 AU", SolarPhysics.describeDistance(SolarSession.AU_KM))
    }

    @Test
    fun botsTourTheWorldsAndStandingsRankByScore() {
        val s = session(bots = 3)
        var now = t0
        repeat(120 * 60) {
            now += 16L
            s.tick(now)
        }
        val standings = s.standings()
        assertEquals(4, standings.size)
        for (i in 1 until standings.size) assertTrue(standings[i - 1].score >= standings[i].score)
        assertTrue(s.bots.any { it.progress.visitedCount >= 2 }, "bots should have visited somewhere in two minutes")
        // Nobody inside anything.
        for (bot in s.bots) {
            for (body in Body.entries) {
                val d = s.world.distanceTo(body, bot.ship.pos[0], bot.ship.pos[1], bot.ship.pos[2])
                assertTrue(d >= body.radiusKm * 0.99, "${bot.name} inside ${body.label}")
            }
        }
    }

    @Test
    fun remoteShipDeadReckonsAlongItsNose() {
        val r = RemoteSolarShip("them")
        r.applySample(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, speedKmS = 1000.0, rollRad = 0.0, sentAtMs = 0L, receivedAtMs = 0L)
        repeat(60) { r.update(1000L, 1.0 / 60.0) }
        // One second of extrapolation eased in: well past the sample, along +x only.
        assertTrue(r.ship.pos[0] > 900.0 && r.ship.pos[0] <= 1000.0, "x = ${r.ship.pos[0]}")
        assertEquals(0.0, r.ship.pos[1], 1e-9)
        assertTrue(!r.isSilent(1000L))
        assertTrue(r.isSilent(20_000L))
    }
}
