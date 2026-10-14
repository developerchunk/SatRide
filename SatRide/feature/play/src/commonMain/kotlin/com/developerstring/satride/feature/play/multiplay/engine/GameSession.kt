package com.developerstring.satride.feature.play.multiplay.engine

import com.developerstring.satride.core.model.SatClass
import kotlin.math.PI
import kotlin.math.min

/** Where a game is in its life. */
enum class GamePhase { WAITING, COUNTDOWN, RUNNING, FINISHED }

/** One row of the standings, built a few times a second for the HUD. */
data class Standing(
    val uid: String,
    val name: String,
    val colorArgb: Long,
    val isLocal: Boolean,
    val isBot: Boolean,
    val laps: Int,
    val gatesPassed: Int,
    val score: Int,
    val alive: Boolean,
    val finished: Boolean,
    val isIt: Boolean,
    val itTimeS: Double,
    val racePosition: Double,
    val nearMisses: Int,
    val crashes: Int,
)

/**
 * A whole game, from this phone's point of view.
 *
 * Owns the track, the debris, the local ship and its rules, any bots this phone drives, and the
 * mirrors of everyone else. [tick] is the only thing that moves it, and it is called from the
 * frame loop with wall-clock time so the simulation never gets ahead of what is drawn.
 *
 * ## Who simulates what
 *
 * Every phone simulates its own ship and judges its own gates and crashes. Bots are simulated by
 * whoever created them (the host, or the solo player) and published like any other player, so
 * to everyone else a bot *is* a remote player. That keeps one source of truth per craft and
 * avoids ten phones each running their own diverging copy of the same bot.
 */
class GameSession(
    val mode: GameMode,
    val seed: Long,
    val localUid: String,
    val localName: String,
    val localColorArgb: Long,
    localSkin: SatClass,
    botCount: Int,
    botSeed: Long,
    /** The grid slot this phone starts from. */
    val localSlot: Int,
) {
    val track: Track = Track(seed)

    val debris: DebrisField = DebrisField(
        seed,
        track,
        capacity = if (mode == GameMode.DODGE) mode.debrisPerLap * 3 else mode.debrisPerLap,
    ).apply { activeCount = mode.debrisPerLap }

    private val rules = RaceRules(track, mode)

    val local: ShipState = ShipState().apply { skin = localSkin }
    val localProgress: Progress = Progress()
    val localInput: ShipInput = ShipInput()

    val bots: List<Bot>
    private val botRules: List<RaceRules>

    val remotes: LinkedHashMap<String, RemoteShip> = LinkedHashMap()

    /** Real catalog satellites as extra obstacles, ECEF km packed; written by the owner each tick. */
    var extraWorld: DoubleArray = DoubleArray(0)
    var extraCount: Int = 0
    var extraNames: Array<String>? = null
    val extraRadiusKm: Double = 9.0

    var phase: GamePhase = GamePhase.WAITING
        private set
    var startAtMs: Long = 0L
        private set
    var endAtMs: Long = 0L
        private set

    /** Tag: who is "it". Empty when nobody is (mode ≠ TAG, or before the start). */
    var itUid: String = ""
    private var tagImmuneUntilMs: Long = 0L

    /** Events since the last drain, from the local ship only. */
    private val pendingEvents = ArrayList<GameEvent>()
    private val drain = ArrayList<GameEvent>()

    private var lastTickMs: Long = 0L
    private var elapsedS: Double = 0.0

    private val spawnDir = DoubleArray(3)
    private val spawnTan = DoubleArray(3)

    init {
        val names = BOT_NAMES.shuffled(kotlin.random.Random(botSeed))
        bots = List(botCount.coerceIn(0, MAX_BOTS)) { i ->
            val rng = SeededRandom(botSeed + i * 31L)
            Bot(name = names[i % names.size], skill = 0.35 + rng.nextDouble() * 0.55, seed = botSeed + i).also { bot ->
                bot.ship.skin = SatClass.entries[rng.nextInt(SatClass.entries.size)]
            }
        }
        botRules = bots.map { RaceRules(track, mode) }
        placeOnGrid()
    }

    /** Puts every locally simulated craft on its grid slot, stationary. */
    fun placeOnGrid() {
        val alt = track.startPosition(localSlot, MAX_PLAYERS, spawnDir, spawnTan)
        local.placeAt(spawnDir, spawnTan, alt)
        rules.reset(localProgress)
        bots.forEachIndexed { i, bot ->
            val slot = MAX_PLAYERS - 1 - i
            val botAlt = track.startPosition(slot, MAX_PLAYERS, spawnDir, spawnTan)
            bot.ship.placeAt(spawnDir, spawnTan, botAlt)
            botRules[i].reset(bot.progress)
        }
    }

    /** Arms the countdown: the race runs from [startMs] until [endMs]. */
    fun schedule(startMs: Long, endMs: Long, nowMs: Long) {
        startAtMs = startMs
        endAtMs = endMs
        phase = if (nowMs >= startMs) GamePhase.RUNNING else GamePhase.COUNTDOWN
        lastTickMs = nowMs
        if (mode == GameMode.TAG && itUid.isEmpty()) itUid = localUid
    }

    /** After a pause: the next tick measures from now instead of replaying the gap. */
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
    val timeSeconds: Double get() = elapsedS

    /** Advances the world to [nowMs]. Call once per frame. */
    fun tick(nowMs: Long) {
        if (lastTickMs == 0L) lastTickMs = nowMs
        var dt = (nowMs - lastTickMs) / 1000.0
        lastTickMs = nowMs
        if (dt <= 0.0) return
        // A long stall (app backgrounded) must not become one giant physics step.
        if (dt > 0.25) dt = 0.25

        when (phase) {
            GamePhase.WAITING -> {
                debris.update(0.0, elapsedS)
                updateRemotes(nowMs, dt)
                return
            }

            GamePhase.COUNTDOWN -> {
                if (nowMs >= startAtMs) phase = GamePhase.RUNNING
                debris.update(0.0, elapsedS)
                updateRemotes(nowMs, dt)
                return
            }

            GamePhase.FINISHED -> {
                // Ships coast so the results camera has something alive to look at.
                localInput.clear()
                localInput.throttle = 0.3
                ShipPhysics.step(local, localInput, dt)
                updateRemotes(nowMs, dt)
                return
            }

            GamePhase.RUNNING -> Unit
        }

        elapsedS += dt
        if (mode == GameMode.DODGE) rampDodge()
        debris.update(dt, elapsedS)

        // Fixed substeps keep collisions honest at 200 km/s: 15 km a frame is a whole ship.
        val steps = (dt / MAX_SUBSTEP_S).toInt().coerceIn(1, 6)
        val sub = dt / steps
        val localAlive = localProgress.alive && !localProgress.finished
        for (s in 0 until steps) {
            if (localAlive) {
                val fired = ShipPhysics.step(local, localInput, sub)
                rules.step(
                    local, localProgress, debris, extraWorld, extraCount, extraRadiusKm, extraNames,
                    elapsedS, nowMs, fired,
                )
            } else {
                localInput.clear()
                localInput.throttle = 0.0
                ShipPhysics.step(local, localInput, sub)
            }
            bots.forEachIndexed { i, bot ->
                if (s == 0) bot.drive(track, debris, elapsedS)
                val fired = ShipPhysics.step(bot.ship, bot.input, sub)
                if (bot.progress.alive && !bot.progress.finished) {
                    botRules[i].step(
                        bot.ship, bot.progress, debris, extraWorld, extraCount, extraRadiusKm, null,
                        elapsedS, nowMs, fired,
                    )
                }
            }
        }
        rules.drainEvents(pendingEvents)
        botRules.forEach { it.drainEvents(drain) }
        drain.clear()

        updateRemotes(nowMs, dt)
        if (mode == GameMode.TAG) tagStep(nowMs, dt)

        if (nowMs >= endAtMs && endAtMs > 0L) phase = GamePhase.FINISHED
    }

    private fun rampDodge() {
        // +55% of the base density per minute, up to the capacity.
        val base = mode.debrisPerLap
        val target = (base * (1.0 + elapsedS / 60.0 * 0.55)).toInt()
        if (target > debris.activeCount) debris.activeCount = target
    }

    private fun updateRemotes(nowMs: Long, dt: Double) {
        for (remote in remotes.values) remote.update(nowMs, dt)
    }

    /**
     * Tag is judged by whoever is "it": if that is this phone, a touch on another craft passes it
     * on. Bots this phone drives are judged here too, since nobody else simulates them.
     */
    private fun tagStep(nowMs: Long, dt: Double) {
        if (itUid == localUid) localProgress.itTimeS += dt
        bots.forEach { if (itUid == botUid(it)) it.progress.itTimeS += dt }
        if (nowMs < tagImmuneUntilMs) return

        // The "it" craft this phone owns, if any.
        val itShip: ShipState = (
            if (itUid == localUid) local else bots.firstOrNull { botUid(it) == itUid }?.ship
            ) ?: return

        var bestUid = ""
        var bestD = TAG_RADIUS_KM
        fun consider(uid: String, ship: ShipState) {
            if (uid == itUid) return
            val dx = ship.world[0] - itShip.world[0]
            val dy = ship.world[1] - itShip.world[1]
            val dz = ship.world[2] - itShip.world[2]
            val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (d < bestD) {
                bestD = d
                bestUid = uid
            }
        }
        consider(localUid, local)
        bots.forEach { consider(botUid(it), it.ship) }
        remotes.values.forEach { consider(it.uid, it.ship) }
        if (bestUid.isNotEmpty()) {
            val wasLocal = itUid == localUid
            itUid = bestUid
            tagImmuneUntilMs = nowMs + TAG_IMMUNITY_MS
            if (wasLocal) {
                localProgress.score += GameEventKind.TAG_PASSED.points
                pendingEvents.add(GameEvent(GameEventKind.TAG_PASSED, nowMs))
            }
            if (bestUid == localUid) pendingEvents.add(GameEvent(GameEventKind.TAGGED, nowMs))
        }
    }

    /** A remote player told us who is it (their judgement wins when they were it). */
    fun applyRemoteIt(uid: String, nowMs: Long) {
        if (uid == itUid || uid.isEmpty()) return
        val becameLocal = uid == localUid
        itUid = uid
        tagImmuneUntilMs = nowMs + TAG_IMMUNITY_MS
        if (becameLocal) pendingEvents.add(GameEvent(GameEventKind.TAGGED, nowMs))
    }

    fun botUid(bot: Bot): String = "bot-" + bot.name.lowercase().replace(' ', '-')

    fun drainEvents(into: MutableList<GameEvent>) {
        into.addAll(pendingEvents)
        pendingEvents.clear()
    }

    /** True when every locally judged racer has crossed the line or is out. */
    val localDone: Boolean get() = localProgress.finished || !localProgress.alive

    fun remoteOrNull(uid: String): RemoteShip? = remotes[uid]

    fun remote(uid: String): RemoteShip = remotes.getOrPut(uid) { RemoteShip(uid) }

    fun removeRemote(uid: String) {
        remotes.remove(uid)
    }

    /** Standings for the HUD, best first. Allocates; call at HUD rate, not frame rate. */
    fun standings(): List<Standing> {
        val rows = ArrayList<Standing>(1 + bots.size + remotes.size)
        rows.add(standing(localUid, localName, localColorArgb, isLocal = true, isBot = false, localProgress))
        bots.forEach { bot ->
            rows.add(standing(botUid(bot), bot.name, botColor(bot), isLocal = false, isBot = true, bot.progress))
        }
        remotes.values.forEach { r ->
            rows.add(
                standing(r.uid, r.name, r.colorArgb, isLocal = false, isBot = r.uid.startsWith("bot-"), r.progress),
            )
        }
        return when (mode) {
            GameMode.RACE -> rows.sortedWith(
                compareByDescending<Standing> { it.finished }
                    .thenByDescending { it.racePosition }
                    .thenByDescending { it.score },
            )

            GameMode.DODGE -> rows.sortedWith(
                compareByDescending<Standing> { it.alive }.thenByDescending { it.score },
            )

            GameMode.TAG -> rows.sortedWith(compareBy<Standing> { it.itTimeS }.thenByDescending { it.score })

            // Never built for this mode (see SolarSession); ordered by score for completeness.
            GameMode.SOLAR -> rows.sortedByDescending { it.score }
        }
    }

    private fun standing(uid: String, name: String, color: Long, isLocal: Boolean, isBot: Boolean, p: Progress) =
        Standing(
            uid = uid,
            name = name,
            colorArgb = color,
            isLocal = isLocal,
            isBot = isBot,
            laps = p.laps,
            gatesPassed = p.gatesPassed,
            score = p.score,
            alive = p.alive,
            finished = p.finished,
            isIt = uid == itUid,
            itTimeS = p.itTimeS,
            racePosition = p.racePosition,
            nearMisses = p.nearMisses,
            crashes = p.crashes,
        )

    fun botColor(bot: Bot): Long = BOT_COLORS[(bot.name.hashCode() and 0x7fffffff) % BOT_COLORS.size]

    /** Track parameter of the next gate for the minimap and the guide arrow. */
    fun nextGateParameter(): Double = 2.0 * PI * localProgress.nextGate / track.gateCount

    fun orbAvailable(i: Int): Boolean = rules.orbAvailable(i, elapsedS)

    companion object {
        const val MAX_PLAYERS: Int = 10
        const val MAX_BOTS: Int = 9
        private const val MAX_SUBSTEP_S = 1.0 / 90.0
        const val TAG_RADIUS_KM: Double = 16.0
        private const val TAG_IMMUNITY_MS = 2_500L

        val BOT_NAMES: List<String> = listOf(
            "Sputnik", "Vostok", "Gemini", "Apollo", "Mir", "Soyuz", "Zenit", "Salyut", "Skylab",
            "Voyager", "Hubble", "Kepler", "Cassini", "Juno", "Rosetta", "Aryabhata", "Chandra",
        )
        val BOT_COLORS: List<Long> = listOf(
            0xFFFF7E5C,
            0xFFFFC94D,
            0xFF8E7BFF,
            0xFFB9F6D8,
            0xFF4D7DFF,
            0xFFFF54C8,
            0xFF3FE0FF,
            0xFFFFE14D,
        )

        /** The palette a player can pick a craft colour from. */
        val PLAYER_COLORS: List<Long> = listOf(
            0xFF3FE0FF, 0xFFFF7E5C, 0xFFFFE14D, 0xFF8E7BFF, 0xFFB9F6D8, 0xFFFF54C8, 0xFF4D7DFF, 0xFF7CFF6B,
            0xFFFFA24D, 0xFFFFFFFF,
        )
    }
}
