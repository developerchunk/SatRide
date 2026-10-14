package com.developerstring.satride.feature.play.multiplay.solar

import com.developerstring.satride.feature.play.multiplay.engine.GameEvent
import com.developerstring.satride.feature.play.multiplay.engine.GameEventKind
import com.developerstring.satride.feature.play.multiplay.engine.GamePhase
import com.developerstring.satride.feature.play.multiplay.engine.GameSession
import com.developerstring.satride.feature.play.multiplay.engine.SeededRandom
import com.developerstring.satride.feature.play.multiplay.engine.SphereMath
import com.developerstring.satride.feature.play.multiplay.engine.Standing
import com.developerstring.satride.feature.play.solar.Body
import com.developerstring.satride.feature.play.solar.BodyKind
import com.developerstring.satride.feature.play.solar.Ephemeris
import com.developerstring.satride.feature.play.solar.SolarWorld
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.Instant

/** What one starship has seen: the expedition log the standings are built from. */
class SolarProgress {
    val visited: BooleanArray = BooleanArray(Body.entries.size)
    var score: Int = 0
    var sunGrazes: Int = 0
    var ringDives: Int = 0
    var bounces: Int = 0
    var grandTour: Boolean = false

    /** For remotes: what the network said they are heading for and how. */
    var targetId: String = ""
    var pilotMode: String = ""

    /** Remotes only send a count, not the set. */
    var reportedVisited: Int = 0

    val visitedCount: Int get() = maxOf(visited.count { it }, reportedVisited)

    /** Planets proper (not the Sun, Moon or Pluto) visited, for the Grand Tour. */
    val planetsVisited: Int get() = Body.entries.count { it.isPlanet && visited[it.ordinal] }

    fun reset() {
        visited.fill(false)
        score = 0
        sunGrazes = 0
        ringDives = 0
        bounces = 0
        grandTour = false
    }
}

/**
 * One expedition, from this phone's point of view.
 *
 * Same shape as [GameSession] — a local ship, bots this phone drives, mirrors of everyone else,
 * one [tick] a frame — but the world is the solar system and the ship flies free. Nothing is
 * a race: the score is an expedition log, and the clock is only the room's lifetime.
 */
class SolarSession(
    val seed: Long,
    val localUid: String,
    val localName: String,
    val localColorArgb: Long,
    botCount: Int,
    botSeed: Long,
    val localSlot: Int,
) {
    val world = SolarWorld()

    val local: SolarShipState = SolarShipState()
    val localInput: SolarInput = SolarInput()
    val autopilot: Autopilot = Autopilot()
    val localProgress: SolarProgress = SolarProgress()

    /** The approach governor for manual flight; the autopilot is always governed. */
    var governorEnabled: Boolean = true

    /** When true the dial follows the target: each trip is set to take about half a minute. */
    var autoSpeed: Boolean = true

    val bots: List<SolarBot>
    val remotes: LinkedHashMap<String, RemoteSolarShip> = LinkedHashMap()

    var phase: GamePhase = GamePhase.WAITING
        private set
    var startAtMs: Long = 0L
        private set
    var endAtMs: Long = 0L
        private set

    private val pendingEvents = ArrayList<GameEvent>()
    private var lastTickMs = 0L
    private var elapsedS = 0.0

    // Per-ship rule state that is not progress: whether we are currently inside a graze/dive so
    // each is counted once per pass.
    private var localGrazing = false
    private var localDiving = false
    private val rel = DoubleArray(3)

    init {
        val names = GameSession.BOT_NAMES.shuffled(kotlin.random.Random(botSeed))
        bots = List(botCount.coerceIn(0, GameSession.MAX_BOTS)) { i ->
            SolarBot(name = names[i % names.size], seed = botSeed + i * 17L)
        }
    }

    /** Arms the clock and places every locally simulated ship in orbit around the Earth. */
    fun schedule(startMs: Long, endMs: Long, nowMs: Long) {
        startAtMs = startMs
        endAtMs = endMs
        lastTickMs = nowMs
        world.update(Ephemeris.julianDate(Instant.fromEpochMilliseconds(nowMs)))
        phase = if (nowMs >= startMs) GamePhase.RUNNING else GamePhase.COUNTDOWN
        autopilot.beginInOrbit(local, Body.EARTH, world, localSlot, GameSession.MAX_PLAYERS)
        local.warp = 1.0
        localProgress.reset()
        localProgress.visited[Body.EARTH.ordinal] = true
        bots.forEachIndexed { i, bot ->
            val slot = GameSession.MAX_PLAYERS - 1 - i
            bot.autopilot.beginInOrbit(bot.ship, Body.EARTH, world, slot, GameSession.MAX_PLAYERS)
            bot.progress.reset()
            bot.progress.visited[Body.EARTH.ordinal] = true
            bot.ship.warp = 1.0
        }
    }

    fun resync(nowMs: Long) {
        lastTickMs = nowMs
    }

    fun finishNow(nowMs: Long) {
        phase = GamePhase.FINISHED
        endAtMs = min(endAtMs.takeIf { it > 0 } ?: nowMs, nowMs)
    }

    val secondsToStart: Double get() = ((startAtMs - lastTickMs) / 1000.0).coerceAtLeast(0.0)
    val secondsRemaining: Double get() = ((endAtMs - lastTickMs) / 1000.0).coerceAtLeast(0.0)
    val elapsedSeconds: Double get() = elapsedS

    /** Advances the world to [nowMs]. Call once per frame. */
    fun tick(nowMs: Long) {
        if (lastTickMs == 0L) lastTickMs = nowMs
        var dt = (nowMs - lastTickMs) / 1000.0
        lastTickMs = nowMs
        if (dt <= 0.0) return
        if (dt > 0.25) dt = 0.25

        // The planets keep moving on the real clock; Mercury covers a radius in a few minutes.
        world.update(Ephemeris.julianDate(Instant.fromEpochMilliseconds(nowMs)))

        when (phase) {
            GamePhase.WAITING, GamePhase.COUNTDOWN -> {
                if (phase == GamePhase.COUNTDOWN && nowMs >= startAtMs) phase = GamePhase.RUNNING
                autopilot.update(local, dt, world)
                bots.forEach { it.autopilot.update(it.ship, dt, world) }
                updateRemotes(nowMs, dt)
                return
            }

            GamePhase.FINISHED -> {
                stepLocal(dt, judge = false)
                bots.forEach { it.autopilot.update(it.ship, dt, world) }
                updateRemotes(nowMs, dt)
                return
            }

            GamePhase.RUNNING -> Unit
        }

        elapsedS += dt
        stepLocal(dt, judge = true)
        for (bot in bots) {
            bot.think(world)
            val arrived = bot.autopilot.update(bot.ship, dt, world)
            judge(bot.ship, bot.progress, bot.autopilot, arrived, null, announce = false)
        }
        updateRemotes(nowMs, dt)
        if (endAtMs > 0L && nowMs >= endAtMs) phase = GamePhase.FINISHED
    }

    private fun stepLocal(dt: Double, judge: Boolean) {
        val arrived: Boolean
        var hit: Body? = null
        if (autopilot.mode == PilotMode.AUTO) {
            arrived = autopilot.update(local, dt, world)
        } else {
            if (autopilot.mode == PilotMode.AIM) autopilot.update(local, dt, world)
            arrived = false
            hit = SolarPhysics.step(local, localInput, dt, world, governorEnabled)
        }
        if (judge) judge(local, localProgress, autopilot, arrived, hit, announce = true)
    }

    /**
     * The expedition rules for one ship: a first visit is a discovery, a close pass of the Sun or
     * a dive through Saturn's rings is a stunt, a bounce costs a little, and seeing every planet
     * is the Grand Tour. Events are raised for the local ship only.
     */
    @Suppress("LongParameterList")
    private fun judge(
        ship: SolarShipState,
        progress: SolarProgress,
        pilot: Autopilot,
        arrived: Boolean,
        hit: Body?,
        announce: Boolean,
    ) {
        val nowMs = lastTickMs
        // Visits: within a generous margin of the parking sphere counts, so a manual fly-by works.
        for (body in world.bodies) {
            val d = world.distanceTo(body, ship.pos[0], ship.pos[1], ship.pos[2])
            if (d > body.parkRadiusKm * VISIT_FACTOR) continue
            if (!progress.visited[body.ordinal]) {
                progress.visited[body.ordinal] = true
                val points = discoveryPoints(body)
                progress.score += points
                if (announce) pendingEvents.add(GameEvent(GameEventKind.DISCOVERY, nowMs, body.label.uppercase()))
                if (!progress.grandTour && progress.planetsVisited >= Body.entries.count { it.isPlanet }) {
                    progress.grandTour = true
                    progress.score += GameEventKind.GRAND_TOUR.points
                    if (announce) pendingEvents.add(GameEvent(GameEventKind.GRAND_TOUR, nowMs))
                }
            }
        }
        if (arrived && announce) {
            pendingEvents.add(GameEvent(GameEventKind.ARRIVED, nowMs, pilot.target?.label?.uppercase() ?: ""))
        }

        // Sun graze: inside two radii, once per approach.
        val sunD = world.distanceTo(Body.SUN, ship.pos[0], ship.pos[1], ship.pos[2])
        val grazing = sunD < Body.SUN.radiusKm * GRAZE_RADII
        if (announce) {
            if (grazing && !localGrazing) {
                progress.sunGrazes++
                progress.score += GameEventKind.SUN_GRAZE.points
                pendingEvents.add(GameEvent(GameEventKind.SUN_GRAZE, nowMs))
            }
            localGrazing = grazing
        }

        // Ring dive: through the plane of Saturn's rings, between the C ring and the A ring's edge.
        rel[0] = ship.pos[0] - world.x(Body.SATURN)
        rel[1] = ship.pos[1] - world.y(Body.SATURN)
        rel[2] = ship.pos[2] - world.z(Body.SATURN)
        val s = Body.SATURN.ordinal * 3
        val zRing = rel[0] * world.pole[s] + rel[1] * world.pole[s + 1] + rel[2] * world.pole[s + 2]
        val r2 = SphereMath.dot(rel, rel) - zRing * zRing
        val rRing = sqrt(r2.coerceAtLeast(0.0))
        val diving = kotlin.math.abs(zRing) < RING_THICKNESS_KM && rRing > RING_INNER_KM && rRing < RING_OUTER_KM
        if (announce) {
            if (diving && !localDiving) {
                progress.ringDives++
                progress.score += GameEventKind.RING_DIVE.points
                pendingEvents.add(GameEvent(GameEventKind.RING_DIVE, nowMs))
            }
            localDiving = diving
        }

        if (hit != null) {
            progress.bounces++
            progress.score = (progress.score + GameEventKind.BOUNCE.points).coerceAtLeast(0)
            if (announce) pendingEvents.add(GameEvent(GameEventKind.BOUNCE, nowMs, hit.label.uppercase()))
        }
    }

    private fun discoveryPoints(body: Body): Int = when (body.kind) {
        BodyKind.STAR -> 250

        BodyKind.PLANET ->
            GameEventKind.DISCOVERY.points +
                (world.distanceTo(body, 0.0, 0.0, 0.0) / AU_KM * 10.0).toInt()

        BodyKind.MOON -> 60

        BodyKind.DWARF -> 400
    }

    private fun updateRemotes(nowMs: Long, dt: Double) {
        for (remote in remotes.values) remote.update(nowMs, dt)
    }

    // --- Pilot commands -------------------------------------------------------------------------

    /** The stick moved: the pilot has the ship. The target is kept for a one-tap re-aim. */
    fun takeManualControl() {
        if (autopilot.mode != PilotMode.MANUAL) {
            autopilot.setMode(PilotMode.MANUAL)
            autopilot.resetOrbitBasis()
        }
    }

    fun setTarget(body: Body?) {
        autopilot.setTarget(body)
        if (body != null && autoSpeed) applyAutoSpeed(body)
    }

    fun setPilotMode(mode: PilotMode) {
        if (mode != PilotMode.MANUAL && autopilot.target == null) return
        autopilot.setMode(mode)
        autopilot.resetOrbitBasis()
        val body = autopilot.target
        if (mode == PilotMode.AUTO && body != null && autoSpeed) applyAutoSpeed(body)
    }

    /** The pilot touched the dial: it is theirs now. */
    fun setWarp(warp: Double) {
        local.warp = warp.coerceIn(SolarPhysics.WARP_MIN, SolarPhysics.WARP_MAX)
        autoSpeed = false
    }

    fun enableAutoSpeed() {
        autoSpeed = true
        autopilot.target?.let { applyAutoSpeed(it) }
    }

    private fun applyAutoSpeed(body: Body) {
        val remaining = world.distanceTo(body, local.pos[0], local.pos[1], local.pos[2]) - body.parkRadiusKm
        if (remaining > 0.0) local.warp = SolarPhysics.warpForTrip(remaining, AUTO_TRIP_SECONDS)
    }

    // --- Queries for the HUD --------------------------------------------------------------------

    fun distanceToTargetKm(): Double = autopilot.remainingKm(local, world)

    /** Seconds to the target at the current speed, or at the dial's cruise if stopped. */
    fun etaSeconds(): Double {
        val d = distanceToTargetKm()
        if (d.isNaN()) return Double.NaN
        val v = if (local.speedKmS > 1.0) local.speedKmS else SolarPhysics.cruiseSpeed(local.warp)
        return d / v
    }

    fun nearestBody(): Body = world.nearest(local.pos[0], local.pos[1], local.pos[2])

    fun altitudeAbove(body: Body): Double =
        world.distanceTo(body, local.pos[0], local.pos[1], local.pos[2]) - body.radiusKm

    /** How hot the ship is: 0 far from the Sun, 1 at the surface — drives the HUD's heat haze. */
    fun sunHeat(): Float {
        val d = world.distanceTo(Body.SUN, local.pos[0], local.pos[1], local.pos[2]) / Body.SUN.radiusKm
        return ((3.0 - d) / 2.0).toFloat().coerceIn(0f, 1f)
    }

    fun drainEvents(into: MutableList<GameEvent>) {
        into.addAll(pendingEvents)
        pendingEvents.clear()
    }

    fun remote(uid: String): RemoteSolarShip = remotes.getOrPut(uid) { RemoteSolarShip(uid) }

    fun removeRemote(uid: String) {
        remotes.remove(uid)
    }

    fun botUid(bot: SolarBot): String = "bot-" + bot.name.lowercase().replace(' ', '-')

    fun botColor(bot: SolarBot): Long =
        GameSession.BOT_COLORS[(bot.name.hashCode() and 0x7fffffff) % GameSession.BOT_COLORS.size]

    /** Standings by score, then by worlds seen. Allocates; call at HUD rate. */
    fun standings(): List<Standing> {
        val rows = ArrayList<Standing>(1 + bots.size + remotes.size)
        rows.add(standing(localUid, localName, localColorArgb, isLocal = true, isBot = false, localProgress))
        bots.forEach {
            rows.add(standing(botUid(it), it.name, botColor(it), isLocal = false, isBot = true, it.progress))
        }
        remotes.values.forEach { r ->
            rows.add(
                standing(r.uid, r.name, r.colorArgb, isLocal = false, isBot = r.uid.startsWith("bot-"), r.progress),
            )
        }
        return rows.sortedWith(compareByDescending<Standing> { it.score }.thenByDescending { it.laps })
    }

    private fun standing(uid: String, name: String, color: Long, isLocal: Boolean, isBot: Boolean, p: SolarProgress) =
        Standing(
            uid = uid,
            name = name,
            colorArgb = color,
            isLocal = isLocal,
            isBot = isBot,
            laps = p.visitedCount,
            gatesPassed = p.visitedCount,
            score = p.score,
            alive = true,
            finished = p.grandTour,
            isIt = false,
            itTimeS = 0.0,
            racePosition = p.visitedCount.toDouble(),
            nearMisses = p.sunGrazes + p.ringDives,
            crashes = p.bounces,
        )

    companion object {
        const val AU_KM: Double = 149_597_870.7
        const val VISIT_FACTOR: Double = 1.35
        const val GRAZE_RADII: Double = 2.0
        const val RING_INNER_KM: Double = 74_500.0
        const val RING_OUTER_KM: Double = 140_200.0
        const val RING_THICKNESS_KM: Double = 4_000.0
        const val AUTO_TRIP_SECONDS: Double = 30.0
    }
}

/**
 * A tourist. Bots pick an unvisited world, set a dial that makes the trip take twenty to fifty
 * seconds, fly there on autopilot, look around for a while, and move on. They exist so a solo
 * sky is not empty and so a room's newcomers have someone to chase.
 */
class SolarBot(val name: String, seed: Long) {
    val ship: SolarShipState = SolarShipState()
    val autopilot: Autopilot = Autopilot()
    val progress: SolarProgress = SolarProgress()
    private val rng = SeededRandom(seed)
    private var dwellS = 6.0 + rng.nextDouble() * 8.0

    fun think(world: SolarWorld) {
        if (autopilot.phase != PilotPhase.ORBIT || autopilot.orbitSeconds < dwellS) return
        val unvisited = Body.entries.filter { !progress.visited[it.ordinal] && it != Body.SUN }
        val pool = if (unvisited.isNotEmpty()) unvisited else Body.entries.filter { it != autopilot.target }
        val next = pool[rng.nextInt(pool.size)]
        val remaining = world.distanceTo(next, ship.pos[0], ship.pos[1], ship.pos[2]) - next.parkRadiusKm
        ship.warp = SolarPhysics.warpForTrip(remaining, 20.0 + rng.nextDouble() * 30.0)
        autopilot.setTarget(next)
        autopilot.setMode(PilotMode.AUTO)
        autopilot.resetOrbitBasis()
        dwellS = 6.0 + rng.nextDouble() * 8.0
        progress.targetId = next.id
    }
}

/**
 * Another pilot's starship as this phone sees it: the last sample, dead-reckoned along its nose
 * at its speed, eased so it glides rather than jumps. Straight-line extrapolation is exact for
 * a ship in free flight and close enough for one in a slow parking orbit.
 */
class RemoteSolarShip(val uid: String) {
    var name: String = ""
    var colorArgb: Long = 0xFFFFFFFF
    val progress: SolarProgress = SolarProgress()
    var warp: Double = 1.0
    var emote: String = ""
    var emoteAtMs: Long = 0L

    private val samplePos = DoubleArray(3)
    private val sampleFwd = DoubleArray(3)
    private var sampleSpeed = 0.0
    private var sampleAtMs = 0L
    private var hasSample = false
    private val pred = DoubleArray(3)

    val ship: SolarShipState = SolarShipState()

    var lastSeenMs: Long = 0L
        private set

    @Suppress("LongParameterList")
    fun applySample(
        x: Double,
        y: Double,
        z: Double,
        fx: Double,
        fy: Double,
        fz: Double,
        speedKmS: Double,
        rollRad: Double,
        sentAtMs: Long,
        receivedAtMs: Long,
    ) {
        SphereMath.set(samplePos, x, y, z)
        SphereMath.set(sampleFwd, fx, fy, fz)
        if (!SphereMath.normalize(sampleFwd)) SphereMath.set(sampleFwd, 0.0, 1.0, 0.0)
        sampleSpeed = speedKmS
        sampleAtMs = sentAtMs
        lastSeenMs = receivedAtMs
        ship.rollRad = rollRad
        if (!hasSample) {
            hasSample = true
            ship.place(x, y, z, sampleFwd[0], sampleFwd[1], sampleFwd[2], 0.0, 0.0, 1.0)
            ship.speedKmS = speedKmS
        }
    }

    fun update(nowMs: Long, dt: Double) {
        if (!hasSample) return
        val ageS = ((nowMs - sampleAtMs) / 1000.0).coerceIn(0.0, MAX_EXTRAPOLATION_S)
        val travelled = sampleSpeed * ageS
        pred[0] = samplePos[0] + sampleFwd[0] * travelled
        pred[1] = samplePos[1] + sampleFwd[1] * travelled
        pred[2] = samplePos[2] + sampleFwd[2] * travelled
        val k = 1.0 - exp(-dt / EASE_TAU_S)
        SphereMath.copy(ship.pos, ship.prev)
        for (i in 0..2) {
            ship.pos[i] += (pred[i] - ship.pos[i]) * k
            ship.fwd[i] += (sampleFwd[i] - ship.fwd[i]) * k
        }
        ship.orthonormalize()
        ship.speedKmS += (sampleSpeed - ship.speedKmS) * k
        ship.flame += ((if (sampleSpeed > 1.0) 0.8 else 0.35) - ship.flame) * k
    }

    fun isSilent(nowMs: Long): Boolean = hasSample && nowMs - lastSeenMs > SILENT_AFTER_MS

    companion object {
        const val MAX_EXTRAPOLATION_S: Double = 2.5
        private const val EASE_TAU_S = 0.28
        const val SILENT_AFTER_MS: Long = 6_000L
    }
}
