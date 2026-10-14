package com.developerstring.satride.feature.play.multiplay.engine

import kotlin.math.PI
import kotlin.math.sqrt

/** Something that happened to a ship this frame, for the HUD's toasts and the haptics. */
enum class GameEventKind(val label: String, val points: Int) {
    GATE("GATE", 100),
    PERFECT_GATE("PERFECT GATE", 150),
    MISSED_GATE("MISSED GATE", -40),
    NEAR_MISS("NEAR MISS", 30),
    CRASH("CRASH", -80),
    ORB("BOOST ORB", 25),
    DRIFT_BOOST("DRIFT BOOST", 20),
    LAP("LAP", 250),
    FINISH("FINISH", 500),
    TAGGED("TAGGED", 0),
    TAG_PASSED("TAG PASSED", 150),
    SHIELD_LOST("SHIELD LOST", 0),
    OUT("OUT", 0),
    SATELLITE_FLYBY("FLYBY", 60),

    // Solar System mode.
    DISCOVERY("DISCOVERED", 100),
    ARRIVED("IN ORBIT", 0),
    SUN_GRAZE("SUN GRAZE", 200),
    RING_DIVE("RING DIVE", 150),
    BOUNCE("BOUNCED", -20),
    GRAND_TOUR("GRAND TOUR", 1000),
}

class GameEvent(val kind: GameEventKind, val atMs: Long, val detail: String = "")

/**
 * Everything one ship has done in this game.
 *
 * One of these per participant. Bots and remote players keep one too, so the standings can be
 * built the same way for everyone — remote progress arrives over the network and is written
 * into the same fields the local rules compute.
 */
class Progress {
    var nextGate: Int = 0
    var laps: Int = 0
    var gatesPassed: Int = 0
    var score: Int = 0
    var crashes: Int = 0
    var nearMisses: Int = 0
    var shields: Int = 0
    var alive: Boolean = true
    var finished: Boolean = false
    var finishTimeMs: Long = 0L

    /** Seconds spent as "it" in Tag: the number to keep low. */
    var itTimeS: Double = 0.0

    /** Lap fraction 0..1 from the last frame, for the standings' continuous ordering. */
    var lapFraction: Double = 0.0

    val racePosition: Double get() = laps + lapFraction

    fun reset(shields: Int) {
        nextGate = 0
        laps = 0
        gatesPassed = 0
        score = 0
        crashes = 0
        nearMisses = 0
        this.shields = shields
        alive = true
        finished = false
        finishTimeMs = 0L
        itTimeS = 0.0
        lapFraction = 0.0
    }
}

/**
 * The rules: gates, laps, orbs, collisions, and what each is worth.
 *
 * Client-authoritative, like every phone-to-phone arcade game that has ever shipped: each player
 * judges their own gates and crashes and publishes the result. Nobody is defending a prize here,
 * and a server that adjudicated ten players' collisions at 200 km/s would need a tick rate this
 * transport cannot give.
 */
class RaceRules(private val track: Track, private val mode: GameMode) {

    private val gateWorld = DoubleArray(3)
    private val orbWorld = DoubleArray(3)
    private val rel = DoubleArray(3)

    /** When each orb was last taken, per player: they respawn after [ORB_RESPAWN_S]. */
    private val orbTakenAtS = DoubleArray(track.gateCount) { -1e9 }

    /** Per-debris cooldown so one long graze is one near miss, not thirty. */
    private var nearMissAt = DoubleArray(0)

    private var lastGateSignedDistance = 0.0
    private var lastGateIndex = -1

    private val events = ArrayList<GameEvent>()

    /** Events raised during the last [step]; consumed by the caller. */
    fun drainEvents(into: MutableList<GameEvent>) {
        into.addAll(events)
        events.clear()
    }

    fun reset(progress: Progress) {
        progress.reset(mode.shields)
        orbTakenAtS.fill(-1e9)
        lastGateSignedDistance = 0.0
        lastGateIndex = -1
    }

    /**
     * Judges one frame for [ship]: gate crossings, orbs, and collisions against the debris in
     * [debris] and the extra obstacle positions in [extraWorld] (the real catalog).
     */
    @Suppress("LongParameterList")
    fun step(
        ship: ShipState,
        progress: Progress,
        debris: DebrisField,
        extraWorld: DoubleArray,
        extraCount: Int,
        extraRadiusKm: Double,
        extraNames: Array<String>?,
        timeS: Double,
        nowMs: Long,
        driftFired: Boolean,
    ) {
        if (!progress.alive) return
        progress.lapFraction = track.lapFractionOf(ship.pos)
        if (driftFired) award(progress, GameEventKind.DRIFT_BOOST, nowMs)

        judgeGate(ship, progress, nowMs)
        judgeOrbs(ship, timeS, nowMs, progress)
        judgeCollisions(ship, progress, debris, extraWorld, extraCount, extraRadiusKm, extraNames, timeS, nowMs)
    }

    private fun judgeGate(ship: ShipState, progress: Progress, nowMs: Long) {
        if (progress.finished) return
        val g = progress.nextGate
        track.gateWorld(g, gateWorld)
        rel[0] = ship.world[0] - gateWorld[0]
        rel[1] = ship.world[1] - gateWorld[1]
        rel[2] = ship.world[2] - gateWorld[2]
        val axis = track.gateAxis[g]
        val signed = rel[0] * axis[0] + rel[1] * axis[1] + rel[2] * axis[2]
        if (g != lastGateIndex) {
            lastGateIndex = g
            lastGateSignedDistance = signed
            return
        }
        val crossed = lastGateSignedDistance < 0.0 && signed >= 0.0
        lastGateSignedDistance = signed
        if (crossed) {
            // Perpendicular distance from the ring's axis.
            val px = rel[0] - axis[0] * signed
            val py = rel[1] - axis[1] * signed
            val pz = rel[2] - axis[2] * signed
            val off = sqrt(px * px + py * py + pz * pz)
            when {
                off <= track.gateRadiusKm * PERFECT_FRACTION -> award(progress, GameEventKind.PERFECT_GATE, nowMs)
                off <= track.gateRadiusKm * PASS_TOLERANCE -> award(progress, GameEventKind.GATE, nowMs)
                off <= track.gateRadiusKm * MISS_WINDOW -> award(progress, GameEventKind.MISSED_GATE, nowMs)
                else -> return // Far off the line: the gate is still ahead.
            }
            advanceGate(progress, nowMs)
            return
        }
        // Went wide enough to never cross the plane near the ring: count it missed once the ship
        // is clearly past, so a wanderer is not stuck with a gate behind them forever.
        val gateT = 2.0 * PI * g / track.gateCount
        val shipT = track.parameterOf(ship.pos)
        var ahead = shipT - gateT
        if (ahead < -PI) ahead += 2.0 * PI
        if (ahead > PI) ahead -= 2.0 * PI
        val spacing = 2.0 * PI / track.gateCount
        if (ahead > spacing * 0.6) {
            award(progress, GameEventKind.MISSED_GATE, nowMs)
            advanceGate(progress, nowMs)
        }
    }

    private fun advanceGate(progress: Progress, nowMs: Long) {
        progress.gatesPassed++
        progress.nextGate = (progress.nextGate + 1) % track.gateCount
        lastGateIndex = -1
        if (progress.nextGate == 0) {
            progress.laps++
            if (mode.laps > 0 && progress.laps >= mode.laps) {
                progress.finished = true
                progress.finishTimeMs = nowMs
                award(progress, GameEventKind.FINISH, nowMs)
            } else {
                award(progress, GameEventKind.LAP, nowMs, "${progress.laps}")
            }
        }
    }

    private fun judgeOrbs(ship: ShipState, timeS: Double, nowMs: Long, progress: Progress) {
        // Only the orbs near the ship's gate can be in reach; check three.
        for (k in -1..1) {
            val i = ((progress.nextGate + k) % track.gateCount + track.gateCount) % track.gateCount
            if (timeS - orbTakenAtS[i] < ORB_RESPAWN_S) continue
            track.orbWorld(i, orbWorld)
            val d = SphereMath.pointSegmentDistance(
                orbWorld[0], orbWorld[1], orbWorld[2],
                ship.worldPrev[0], ship.worldPrev[1], ship.worldPrev[2],
                ship.world[0], ship.world[1], ship.world[2],
            )
            if (d < ORB_RADIUS_KM) {
                orbTakenAtS[i] = timeS
                ShipPhysics.grantCharge(ship)
                award(progress, GameEventKind.ORB, nowMs)
            }
        }
    }

    fun orbAvailable(i: Int, timeS: Double): Boolean = timeS - orbTakenAtS[i] >= ORB_RESPAWN_S

    @Suppress("LongParameterList")
    private fun judgeCollisions(
        ship: ShipState,
        progress: Progress,
        debris: DebrisField,
        extraWorld: DoubleArray,
        extraCount: Int,
        extraRadiusKm: Double,
        extraNames: Array<String>?,
        timeS: Double,
        nowMs: Long,
    ) {
        if (nearMissAt.size < debris.capacity + extraCount) {
            nearMissAt = DoubleArray(debris.capacity + extraCount) { -1e9 }
        }
        val ax = ship.worldPrev[0]
        val ay = ship.worldPrev[1]
        val az = ship.worldPrev[2]
        val bx = ship.world[0]
        val by = ship.world[1]
        val bz = ship.world[2]
        val canHit = ShipPhysics.canCollide(ship)

        // Debris: a coarse distance gate first, the swept test only for what is close.
        for (i in 0 until debris.activeCount) {
            val wx = debris.world[i * 3]
            val wy = debris.world[i * 3 + 1]
            val wz = debris.world[i * 3 + 2]
            val dx = wx - bx
            val dy = wy - by
            val dz = wz - bz
            if (dx * dx + dy * dy + dz * dz > COARSE_RANGE_SQ) continue
            val d = SphereMath.pointSegmentDistance(wx, wy, wz, ax, ay, az, bx, by, bz)
            val hitRadius = debris.radiusKm[i] + SHIP_RADIUS_KM
            if (d < hitRadius) {
                if (canHit) {
                    crash(ship, progress, nowMs)
                    return
                }
            } else if (d < hitRadius * NEAR_MISS_FACTOR && timeS - nearMissAt[i] > NEAR_MISS_COOLDOWN_S) {
                nearMissAt[i] = timeS
                progress.nearMisses++
                award(progress, GameEventKind.NEAR_MISS, nowMs)
            }
        }

        // The real catalog: same test, bigger reward for the near miss — it has a name.
        for (i in 0 until extraCount) {
            val wx = extraWorld[i * 3]
            val wy = extraWorld[i * 3 + 1]
            val wz = extraWorld[i * 3 + 2]
            val dx = wx - bx
            val dy = wy - by
            val dz = wz - bz
            if (dx * dx + dy * dy + dz * dz > COARSE_RANGE_SQ) continue
            val d = SphereMath.pointSegmentDistance(wx, wy, wz, ax, ay, az, bx, by, bz)
            val hitRadius = extraRadiusKm + SHIP_RADIUS_KM
            val slot = debris.capacity + i
            if (d < hitRadius) {
                if (canHit) {
                    crash(ship, progress, nowMs)
                    return
                }
            } else if (d < hitRadius * NEAR_MISS_FACTOR * 1.6 && timeS - nearMissAt[slot] > NEAR_MISS_COOLDOWN_S) {
                nearMissAt[slot] = timeS
                progress.nearMisses++
                award(progress, GameEventKind.SATELLITE_FLYBY, nowMs, extraNames?.getOrNull(i) ?: "")
            }
        }
    }

    private fun crash(ship: ShipState, progress: Progress, nowMs: Long) {
        ShipPhysics.crash(ship)
        progress.crashes++
        award(progress, GameEventKind.CRASH, nowMs)
        if (mode.shields > 0) {
            progress.shields--
            if (progress.shields <= 0) {
                progress.alive = false
                events.add(GameEvent(GameEventKind.OUT, nowMs))
            } else {
                events.add(GameEvent(GameEventKind.SHIELD_LOST, nowMs, "${progress.shields}"))
            }
        }
    }

    private fun award(progress: Progress, kind: GameEventKind, nowMs: Long, detail: String = "") {
        progress.score = (progress.score + kind.points).coerceAtLeast(0)
        events.add(GameEvent(kind, nowMs, detail))
    }

    companion object {
        const val SHIP_RADIUS_KM: Double = 4.5
        const val ORB_RADIUS_KM: Double = 26.0
        const val ORB_RESPAWN_S: Double = 25.0
        private const val PERFECT_FRACTION = 0.42
        private const val PASS_TOLERANCE = 1.12
        private const val MISS_WINDOW = 4.0
        private const val NEAR_MISS_FACTOR = 2.6
        private const val NEAR_MISS_COOLDOWN_S = 4.0
        private const val COARSE_RANGE_SQ = 400.0 * 400.0
    }
}
