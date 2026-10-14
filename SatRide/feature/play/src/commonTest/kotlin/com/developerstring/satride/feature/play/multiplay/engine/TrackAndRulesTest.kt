package com.developerstring.satride.feature.play.multiplay.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackAndRulesTest {

    @Test
    fun aTrackIsDeterministicFromItsSeed() {
        val a = Track(seed = 42L)
        val b = Track(seed = 42L)
        for (i in 0 until a.gateCount) assertContentEquals(a.gateDir[i], b.gateDir[i])
        assertContentEquals(a.gateAltKm, b.gateAltKm)
    }

    @Test
    fun gatesSitOnTheUnitSphereInsideTheAltitudeBandInOrder() {
        val track = Track(seed = 7L)
        var previous = -1.0
        for (i in 0 until track.gateCount) {
            assertEquals(1.0, SphereMath.length(track.gateDir[i]), 1e-9)
            assertTrue(track.gateAltKm[i] in ShipPhysics.ALT_MIN_KM..ShipPhysics.ALT_MAX_KM)
            assertEquals(1.0, SphereMath.length(track.gateAxis[i]), 1e-6)
            val t = track.parameterOf(track.gateDir[i])
            assertTrue(t > previous, "gate $i is not further along the lap than gate ${i - 1}")
            previous = t
        }
    }

    @Test
    fun debrisStaysInTheCorridor() {
        val track = Track(seed = 3L)
        val debris = DebrisField(seed = 3L, track = track, capacity = 80)
        debris.update(1.0, 1.0)
        for (i in 0 until debris.activeCount) {
            val x = debris.world[i * 3]
            val y = debris.world[i * 3 + 1]
            val z = debris.world[i * 3 + 2]
            val r = kotlin.math.sqrt(x * x + y * y + z * z) - SphereMath.EARTH_RADIUS_KM
            assertTrue(r > 200.0 && r < 1600.0, "debris $i at $r km altitude")
        }
    }

    @Test
    fun flyingThroughTheFirstGateScoresAndAdvances() {
        val track = Track(seed = 11L)
        val rules = RaceRules(track, GameMode.RACE)
        val progress = Progress()
        rules.reset(progress)
        val debris = DebrisField(seed = 11L, track = track, capacity = 0)

        // Start just before gate 0 on the track centre line, pointing along the gate's axis.
        val ship = ShipState()
        val dir = DoubleArray(3)
        val tangent = DoubleArray(3)
        track.centreAt(-0.02, dir)
        SphereMath.directionToward(dir, track.gateDir[0], tangent)
        ship.placeAt(dir, tangent, track.gateAltKm[0])
        ship.speedKmS = 200.0

        val input = ShipInput().apply { throttle = 1.0 }
        val events = ArrayList<GameEvent>()
        var passed = false
        repeat(400) {
            // Keep the altitude on the gate and the nose on the gate centre so the pass is clean.
            ship.altKm = track.gateAltKm[0]
            SphereMath.directionToward(ship.pos, track.gateDir[0], tangent)
            if (SphereMath.dot(tangent, ship.hdg) > 0.0) {
                SphereMath.copy(tangent, ship.hdg)
                SphereMath.copy(tangent, ship.vel)
            }
            ShipPhysics.step(ship, input, 1.0 / 60.0)
            rules.step(ship, progress, debris, DoubleArray(0), 0, 9.0, null, it / 60.0, it * 16L, false)
            rules.drainEvents(events)
            if (events.any { e -> e.kind == GameEventKind.GATE || e.kind == GameEventKind.PERFECT_GATE }) {
                passed = true
                return@repeat
            }
        }
        assertTrue(passed, "never registered the gate; events=${events.map { it.kind }}")
        assertEquals(1, progress.nextGate)
        assertEquals(1, progress.gatesPassed)
        assertTrue(progress.score >= GameEventKind.GATE.points)
    }

    @Test
    fun aSweptCollisionWithDebrisCrashesTheShipOnce() {
        val track = Track(seed = 5L)
        val rules = RaceRules(track, GameMode.DODGE)
        val progress = Progress()
        rules.reset(progress)
        val debris = DebrisField(seed = 5L, track = track, capacity = 1)
        debris.update(0.0, 0.0)

        // Put the ship's previous and current positions either side of the one piece of debris.
        val ship = ShipState()
        val dx = debris.world[0]
        val dy = debris.world[1]
        val dz = debris.world[2]
        val r = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        ship.pos[0] = dx / r
        ship.pos[1] = dy / r
        ship.pos[2] = dz / r
        ship.altKm = r - SphereMath.EARTH_RADIUS_KM
        ship.refreshWorld()
        ship.worldPrev[0] = ship.world[0] + 30.0
        ship.worldPrev[1] = ship.world[1]
        ship.worldPrev[2] = ship.world[2]
        ship.world[0] -= 30.0

        rules.step(ship, progress, debris, DoubleArray(0), 0, 9.0, null, 1.0, 1000L, false)
        val events = ArrayList<GameEvent>()
        rules.drainEvents(events)
        assertTrue(events.any { it.kind == GameEventKind.CRASH }, "expected a crash, got ${events.map { it.kind }}")
        assertEquals(1, progress.crashes)
        assertEquals(GameMode.DODGE.shields - 1, progress.shields)

        // Immune now: the same overlap next frame is not a second crash.
        rules.step(ship, progress, debris, DoubleArray(0), 0, 9.0, null, 1.02, 1020L, false)
        rules.drainEvents(events)
        assertEquals(1, progress.crashes)
    }

    @Test
    fun lapParameterCoversTheWholeCircle() {
        val track = Track(seed = 99L)
        val p = DoubleArray(3)
        var last = -1.0
        for (k in 0 until 100) {
            track.centreAt(2.0 * PI * k / 100, p)
            val t = track.parameterOf(p)
            assertTrue(t >= 0.0 && t < 2.0 * PI)
            if (k > 0) assertTrue(t > last, "parameter went backwards at $k")
            last = t
        }
    }

    @Test
    fun theSessionRunsAWholeRaceWithoutNaNs() {
        val session = GameSession(
            mode = GameMode.RACE, seed = 1234L, localUid = "me", localName = "Me", localColorArgb = 0xFFFFFFFF,
            localSkin = com.developerstring.satride.core.model.SatClass.OTHER,
            botCount = 3, botSeed = 9L, localSlot = 0,
        )
        session.schedule(startMs = 1000L, endMs = 600_000L, nowMs = 0L)
        session.localInput.throttle = 1.0
        var now = 0L
        repeat(60 * 60) {
            now += 16L
            session.tick(now)
        }
        assertTrue(session.phase == GamePhase.RUNNING)
        assertTrue(session.local.pos.none { it.isNaN() })
        assertTrue(session.local.speedKmS > 50.0)
        session.bots.forEach { bot ->
            assertTrue(bot.ship.pos.none { it.isNaN() }, "bot ${bot.name} went NaN")
            assertTrue(abs(SphereMath.length(bot.ship.pos) - 1.0) < 1e-6)
        }
        val standings = session.standings()
        assertEquals(4, standings.size)
        assertTrue(standings.any { it.isLocal })
    }
}
