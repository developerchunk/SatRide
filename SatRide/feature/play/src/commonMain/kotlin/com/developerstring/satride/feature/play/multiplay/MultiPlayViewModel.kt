package com.developerstring.satride.feature.play.multiplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.data.SatelliteRepository
import com.developerstring.satride.core.data.TleRepository
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.orbit.time.AstroTime
import com.developerstring.satride.core.sensors.AttitudeProvider
import com.developerstring.satride.feature.play.GlobeAssets
import com.developerstring.satride.feature.play.engine.SatelliteField
import com.developerstring.satride.feature.play.multiplay.engine.GameEvent
import com.developerstring.satride.feature.play.multiplay.engine.GameEventKind
import com.developerstring.satride.feature.play.multiplay.engine.GameMode
import com.developerstring.satride.feature.play.multiplay.engine.GamePhase
import com.developerstring.satride.feature.play.multiplay.engine.GameSession
import com.developerstring.satride.feature.play.multiplay.engine.SphereMath
import com.developerstring.satride.feature.play.multiplay.engine.Standing
import com.developerstring.satride.feature.play.multiplay.engine.Track
import com.developerstring.satride.feature.play.multiplay.input.TiltController
import com.developerstring.satride.feature.play.multiplay.net.PlayerDoc
import com.developerstring.satride.feature.play.multiplay.net.PlayerIdentity
import com.developerstring.satride.feature.play.multiplay.net.RoomCodes
import com.developerstring.satride.feature.play.multiplay.net.RoomDoc
import com.developerstring.satride.feature.play.multiplay.net.RoomRepository
import com.developerstring.satride.feature.play.multiplay.net.ScoreDoc
import com.developerstring.satride.feature.play.multiplay.platform.motionControlsAvailable
import com.developerstring.satride.feature.play.multiplay.render.ChaseCamera
import com.developerstring.satride.feature.play.multiplay.render.LookState
import com.developerstring.satride.feature.play.multiplay.solar.PilotMode
import com.developerstring.satride.feature.play.multiplay.solar.PilotPhase
import com.developerstring.satride.feature.play.multiplay.solar.SolarBot
import com.developerstring.satride.feature.play.multiplay.solar.SolarChaseCamera
import com.developerstring.satride.feature.play.multiplay.solar.SolarPhysics
import com.developerstring.satride.feature.play.multiplay.solar.SolarSession
import com.developerstring.satride.feature.play.render.CameraPose
import com.developerstring.satride.feature.play.solar.Body
import com.developerstring.satride.feature.play.solar.Ephemeris
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Owns Multi-Play: the pilot's identity and preferences, the room the phone is in, and the game
 * being played.
 *
 * Same two-rate split as the Play tab. [uiState] changes at human speed — a lobby roster, HUD
 * numbers a few times a second, a toast — while the frame loop talks to [session], [pose] and
 * [look] directly through [onFrame], which never touches a flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList", "TooManyFunctions")
class MultiPlayViewModel(
    private val satelliteRepository: SatelliteRepository,
    private val tleRepository: TleRepository,
    private val assets: GlobeAssets,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
    private val rooms: RoomRepository,
    private val identity: PlayerIdentity,
    private val attitude: AttitudeProvider,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val logger = Logger.withTag("MultiPlay")

    private val _uiState = MutableStateFlow(
        MultiPlayUiState(
            multiplayerAvailable = rooms.isAvailable,
            motionAvailable = motionControlsAvailable && attitude.isAvailable,
        ),
    )
    val uiState: StateFlow<MultiPlayUiState> = _uiState.asStateFlow()

    /** Game events as they happen, for haptics and sounds in the composable. */
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    // --- Frame-rate state -------------------------------------------------------------------

    /** The game in progress; null outside the arena. Read by the renderer every frame. */
    var session: GameSession? = null
        private set

    val pose: CameraPose = CameraPose()
    val look: LookState = LookState()
    private val camera = ChaseCamera()

    /** The Solar System expedition in progress; null outside that mode. Read by its renderer every frame. */
    var solar: SolarSession? = null
        private set
    private val solarCamera = SolarChaseCamera()

    /** The real catalog, propagated as obstacles. */
    private val field = SatelliteField()
    private val temeScratch = DoubleArray(3)
    private var frameCounter = 0
    private var lastFrameNanos = 0L
    private var lookDragging = false
    private var stickActive = false
    private var boostRequested = false
    private var braking = false

    private val tilt = TiltController()
    private var tiltJob: Job? = null

    // --- Room state ---------------------------------------------------------------------------

    private var roomJob: Job? = null
    private var publishJob: Job? = null
    private var hudJob: Job? = null
    private var leaderboardJob: Job? = null
    private var roomCode: String = ""
    private var roomStartedGame = false
    private var localFinishedAtMs = 0L
    private var lastRoomIt = ""

    // Per-game counters the achievements need that the rules do not track.
    private var driftBoosts = 0
    private var flybys = 0
    private var toastCounter = 0L
    private var currentBoardId: String? = null

    init {
        assets.ensureLoaded()
        loadPreferences()
        loadCatalog()
        resolveIdentity()
        observeLeaderboard(GameMode.RACE)
    }

    // region Preferences and identity

    private fun loadPreferences() {
        viewModelScope.launch {
            settings.settings.collect { s ->
                _uiState.update {
                    it.copy(
                        skin =
                        s.multiplaySkin?.let { name -> SatClass.entries.firstOrNull { c -> c.name == name } }
                            ?: it.skin,
                        colorArgb = if (s.multiplayColor != 0L) s.multiplayColor else it.colorArgb,
                        motionCamera = s.multiplayMotionCamera,
                        tiltSteer = s.multiplayTiltSteer,
                        bestScores = s.multiplayBestScores,
                        achievements = s.multiplayAchievements,
                        howToPlaySeen = s.multiplayHowToPlaySeen,
                        gamesPlayed = s.multiplayGamesPlayed,
                    )
                }
            }
        }
    }

    private fun resolveIdentity() {
        viewModelScope.launch {
            identity.resolve()
                .onSuccess { info ->
                    _uiState.update { it.copy(identity = info, displayName = info.name, identityError = null) }
                }
                .onFailure { error ->
                    // Solo still works: a local uid is enough for a game nobody else sees.
                    val guest = runCatching { identity.guestName() }.getOrDefault("Pilot")
                    _uiState.update {
                        it.copy(
                            identity = null,
                            displayName = guest,
                            identityError = error.message ?: "Could not sign in for multiplayer.",
                        )
                    }
                }
        }
    }

    fun setSkin(skin: SatClass) {
        _uiState.update { it.copy(skin = skin) }
        viewModelScope.launch { settings.setMultiplaySkin(skin.name) }
    }

    fun setColor(argb: Long) {
        _uiState.update { it.copy(colorArgb = argb) }
        viewModelScope.launch { settings.setMultiplayColor(argb) }
    }

    /** Opens the rules — from the home screen, the pause menu, or the arena's help button. */
    fun openHowToPlay() {
        _uiState.update { it.copy(showHowToPlay = true) }
    }

    /**
     * Closes the rules and remembers that this mode has been explained.
     *
     * Recorded on dismissal rather than on opening, so a player who backs out of the arena while
     * the card is up meets it again next time — which is what they would want, having not read it.
     */
    fun dismissHowToPlay() {
        val mode = _uiState.value.selectedMode
        val wasHolding = _uiState.value.stage == MultiPlayStage.ARENA && _uiState.value.paused
        _uiState.update { it.copy(showHowToPlay = false) }
        // Resume through `setPaused` rather than by clearing the flag, so the session resyncs its
        // clock: a game left paused for as long as it takes to read a card would otherwise resume
        // convinced it had missed thirty seconds of race.
        if (wasHolding) setPaused(false)
        viewModelScope.launch { settings.recordHowToPlaySeen(mode.id) }
    }

    fun setDisplayName(name: String) {
        val trimmed = name.trim().take(PlayerIdentity.MAX_NAME)
        if (trimmed.isEmpty()) return
        _uiState.update { it.copy(displayName = trimmed) }
        viewModelScope.launch { identity.setGuestName(trimmed) }
    }

    fun setMotionCamera(enabled: Boolean) {
        _uiState.update { it.copy(motionCamera = enabled) }
        viewModelScope.launch { settings.setMultiplayMotionCamera(enabled) }
        if (_uiState.value.stage == MultiPlayStage.ARENA) restartTilt()
    }

    fun setTiltSteer(enabled: Boolean) {
        _uiState.update { it.copy(tiltSteer = enabled) }
        viewModelScope.launch { settings.setMultiplayTiltSteer(enabled) }
    }

    fun selectMode(mode: GameMode) {
        _uiState.update { it.copy(selectedMode = mode) }
        observeLeaderboard(mode)
    }

    fun setSoloBots(count: Int) = _uiState.update { it.copy(soloBots = count.coerceIn(0, GameSession.MAX_BOTS)) }

    fun setJoinCode(raw: String) = _uiState.update { it.copy(joinCode = RoomCodes.normalize(raw)) }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    private fun observeLeaderboard(mode: GameMode) {
        leaderboardJob?.cancel()
        leaderboardJob = viewModelScope.launch {
            rooms.observeLeaderboard(dailyBoardId(mode)).collect { rows ->
                _uiState.update { it.copy(leaderboard = rows) }
            }
        }
    }

    private fun epochDay(): Long = clock.now().toEpochMilliseconds() / 86_400_000L

    private fun dailyBoardId(mode: GameMode): String = "daily-${mode.id}-d${epochDay()}"

    // endregion

    // region Catalog obstacles

    private fun loadCatalog() {
        viewModelScope.launch {
            val satellites = satelliteRepository.observeCurated().first()
            val tles = tleRepository.tlesFor(satellites.map { it.noradId })
            withContext(dispatchers.default) {
                field.load(satellites, tles)
                field.tick(clock.now())
            }
        }
        // A slow propagation heartbeat while a game is on: real satellites barely move at game
        // scale, so two seconds between fixes is plenty.
        viewModelScope.launch(dispatchers.default) {
            while (isActive) {
                if (session != null && field.count > 0) field.tick(clock.now())
                delay(2000L)
            }
        }
    }

    /** Copies the catalog's current ECEF positions into the session's obstacle arrays. */
    private fun refreshCatalogObstacles(session: GameSession) {
        val count = field.count
        if (count == 0) return
        if (session.extraWorld.size < count * 3) {
            session.extraWorld = DoubleArray(count * 3)
            session.extraNames = field.names
        }
        val now = clock.now()
        val gmst = AstroTime.gmstRadians(now)
        val cosG = cos(gmst)
        val sinG = sin(gmst)
        val timeMs = now.toEpochMilliseconds().toDouble()
        var written = 0
        for (i in 0 until count) {
            if (!field.temePositionInto(i, timeMs, temeScratch)) {
                // Park unusable entries far away rather than at the origin (inside the Earth).
                session.extraWorld[i * 3] = 1e7
                session.extraWorld[i * 3 + 1] = 1e7
                session.extraWorld[i * 3 + 2] = 1e7
                continue
            }
            session.extraWorld[i * 3] = cosG * temeScratch[0] + sinG * temeScratch[1]
            session.extraWorld[i * 3 + 1] = -sinG * temeScratch[0] + cosG * temeScratch[1]
            session.extraWorld[i * 3 + 2] = temeScratch[2]
            written++
        }
        session.extraCount = count
    }

    // endregion

    // region Solo

    /** A game against bots on a random track, or on today's shared track for the leaderboard. */
    fun startSolo(daily: Boolean) {
        val state = _uiState.value
        val seed = if (daily) Track.dailySeed(epochDay()) else Random.nextLong()
        val uid = state.identity?.uid ?: "local"
        if (state.selectedMode.isSolar) {
            startSoloSolar(state, seed, uid, daily)
            return
        }
        val session = GameSession(
            mode = state.selectedMode,
            seed = seed,
            localUid = uid,
            localName = state.displayName.ifBlank { "You" },
            localColorArgb = state.colorArgb,
            localSkin = state.skin,
            botCount = state.soloBots,
            botSeed = seed xor 0x1234_5678L,
            localSlot = 0,
        )
        val now = nowMs()
        val duration = when (state.selectedMode) {
            GameMode.RACE -> 12L * 60L * 1000L
            GameMode.DODGE -> 10L * 60L * 1000L
            GameMode.TAG -> 3L * 60L * 1000L
            GameMode.SOLAR -> SOLAR_SOLO_MS
        }
        session.schedule(startMs = now + SOLO_COUNTDOWN_MS, endMs = now + SOLO_COUNTDOWN_MS + duration, nowMs = now)
        currentBoardId = if (daily && state.identity != null) dailyBoardId(state.selectedMode) else null
        enterArena(session, isRoom = false, isDaily = daily)
    }

    // endregion

    // region Rooms

    /** Hosts a room. Joining and hosting are both unlimited. */
    fun createRoom() {
        val state = _uiState.value
        val me = state.identity ?: run {
            _uiState.update { it.copy(error = state.identityError ?: "Multiplayer needs a connection.") }
            return
        }
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val now = nowMs()
            val room = RoomDoc(
                code = RoomCodes.generate(),
                hostUid = me.uid,
                hostName = state.displayName,
                mode = state.selectedMode.id,
                seed = Random.nextLong(),
                createdAtMs = now,
                expiresAtMs = now + RoomDoc.ROOM_LIFETIME_MS,
            )
            rooms.createRoom(room, playerDoc(me.uid, state, slot = 0, now = now))
                .onSuccess { created -> enterLobby(created.code, host = true) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            busy = false,
                            error =
                            error.message ?: "Could not create a room.",
                        )
                    }
                }
        }
    }

    fun joinRoom() {
        val state = _uiState.value
        val me = state.identity ?: run {
            _uiState.update { it.copy(error = state.identityError ?: "Multiplayer needs a connection.") }
            return
        }
        val code = RoomCodes.normalize(state.joinCode)
        if (!RoomCodes.isValid(code)) {
            _uiState.update { it.copy(error = "Room codes are six letters and numbers.") }
            return
        }
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            rooms.joinRoom(code, playerDoc(me.uid, state, slot = 0, now = nowMs()))
                .onSuccess { enterLobby(code, host = false) }
                .onFailure { error ->
                    _uiState.update { it.copy(busy = false, error = error.message ?: "Could not join.") }
                }
        }
    }

    private fun playerDoc(uid: String, state: MultiPlayUiState, slot: Int, now: Long) = PlayerDoc(
        uid = uid,
        name = state.displayName.ifBlank { "Pilot" },
        color = state.colorArgb,
        skin = state.skin.name,
        owner = uid,
        slot = slot,
        joinedAtMs = now,
        seenAtMs = now,
        altKm = 620.0,
    )

    private fun enterLobby(code: String, host: Boolean) {
        roomCode = code
        roomStartedGame = false
        lastRoomIt = ""
        _uiState.update {
            it.copy(
                stage = MultiPlayStage.LOBBY,
                busy = false,
                isHost = host,
                players = emptyList(),
                room = null,
                lobbyBots = 0,
                isRoomGame = true,
            )
        }
        roomJob?.cancel()
        roomJob = viewModelScope.launch {
            launch {
                rooms.observeRoom(code).collect { room -> onRoomChanged(room) }
            }
            launch {
                rooms.observePlayers(code).collect { players -> onRosterChanged(players) }
            }
            launch {
                // Lobby heartbeat and the expiry clock.
                while (isActive) {
                    val room = _uiState.value.room
                    val left = room?.let { ((it.expiresAtMs - nowMs()) / 1000L).toInt().coerceAtLeast(0) } ?: 0
                    _uiState.update { it.copy(roomSecondsLeft = left) }
                    if (_uiState.value.stage == MultiPlayStage.LOBBY) {
                        rooms.publishPlayer(code, myUid(), mapOf("seenAtMs" to nowMs()))
                    }
                    delay(5_000L)
                }
            }
        }
    }

    private fun onRoomChanged(room: RoomDoc?) {
        val now = nowMs()
        if (room == null) {
            // Deleted or unreadable: back to the home screen with a note.
            if (_uiState.value.stage == MultiPlayStage.LOBBY) {
                leaveRoom(message = "The room was closed.")
            }
            return
        }
        _uiState.update { it.copy(room = room, selectedMode = GameMode.fromId(room.mode)) }
        if (room.isExpired(now) && _uiState.value.stage == MultiPlayStage.LOBBY) {
            leaveRoom(message = "The room's fifteen minutes are up.")
            return
        }
        if (room.isRacing && !roomStartedGame) startRoomGame(room)
        val s = session
        if (s != null && room.itUid.isNotEmpty() && room.itUid != lastRoomIt) {
            lastRoomIt = room.itUid
            s.applyRemoteIt(room.itUid, now)
        }
        if (room.isFinished && s != null && s.phase != GamePhase.FINISHED) {
            s.finishNow(now)
        }
    }

    private fun onRosterChanged(players: List<PlayerDoc>) {
        _uiState.update { it.copy(players = players.sortedBy { p -> p.slot }) }
        val sol = solar
        if (sol != null) {
            onSolarRosterChanged(sol, players)
            return
        }
        val s = session ?: return
        val me = myUid()
        val now = nowMs()
        val seen = HashSet<String>()
        for (doc in players) {
            if (doc.uid == me || doc.owner == me) continue
            seen.add(doc.uid)
            val remote = s.remote(doc.uid)
            remote.name = doc.name
            remote.colorArgb = doc.color
            remote.skin = SatClass.entries.firstOrNull { it.name == doc.skin } ?: SatClass.OTHER
            if (doc.sentAtMs > 0L) {
                remote.applySample(
                    doc.latDeg, doc.lonDeg, doc.altKm, doc.headingDeg, doc.speedKmS, doc.rollRad, doc.boosting,
                    sentAtMs = doc.sentAtMs, receivedAtMs = now,
                )
            }
            remote.progress.apply {
                laps = doc.laps
                nextGate = doc.nextGate
                gatesPassed = doc.gatesPassed
                score = doc.score
                alive = doc.alive
                finished = doc.finished
                finishTimeMs = doc.finishMs
                itTimeS = doc.itTimeS
                nearMisses = doc.nearMisses
                crashes = doc.crashes
                lapFraction = (doc.nextGate.toDouble() / s.track.gateCount)
            }
            if (doc.emote.isNotEmpty() && doc.emoteAtMs != remote.emoteAtMs) {
                remote.emote = doc.emote
                remote.emoteAtMs = doc.emoteAtMs
            }
        }
        // Anyone gone from the roster is gone from the arena.
        val stale = s.remotes.keys.filter { it !in seen }
        stale.forEach { s.removeRemote(it) }
    }

    fun setLobbyBots(count: Int) = _uiState.update { it.copy(lobbyBots = count.coerceIn(0, GameSession.MAX_BOTS)) }

    fun setRoomMode(mode: GameMode) {
        val state = _uiState.value
        if (!state.isHost) return
        viewModelScope.launch { rooms.updateRoom(roomCode, mapOf("mode" to mode.id)) }
    }

    /** Host only: lights out in five seconds. */
    fun startRoom() {
        val state = _uiState.value
        val room = state.room ?: return
        if (!state.isHost) return
        val now = nowMs()
        val startAt = now + RoomDoc.COUNTDOWN_MS
        val endAt = room.expiresAtMs
        if (endAt - startAt < 30_000L) {
            _uiState.update { it.copy(error = "Not enough room time left to race.") }
            return
        }
        val bots = state.lobbyBots.coerceAtMost(room.maxPlayers - state.players.size)
        viewModelScope.launch {
            rooms.updateRoom(
                roomCode,
                mapOf(
                    "status" to RoomDoc.STATUS_RACING,
                    "startAtMs" to startAt,
                    "endAtMs" to endAt,
                    "botCount" to bots,
                    "itUid" to if (GameMode.fromId(room.mode) == GameMode.TAG) room.hostUid else "",
                ),
            )
        }
    }

    private fun startRoomGame(room: RoomDoc) {
        roomStartedGame = true
        val state = _uiState.value
        val me = myUid()
        val mySlot = state.players.firstOrNull { it.uid == me }?.slot ?: 0
        val botCount = if (state.isHost) room.botCount else 0
        if (GameMode.fromId(room.mode).isSolar) {
            startRoomSolar(room, me, mySlot, botCount)
            return
        }
        val session = GameSession(
            mode = GameMode.fromId(room.mode),
            seed = room.seed,
            localUid = me,
            localName = state.displayName.ifBlank { "You" },
            localColorArgb = state.colorArgb,
            localSkin = state.skin,
            botCount = botCount,
            botSeed = room.seed xor 0xB07L,
            localSlot = mySlot,
        )
        val now = nowMs()
        session.schedule(startMs = room.startAtMs, endMs = room.endAtMs, nowMs = now)
        if (room.itUid.isNotEmpty()) {
            session.itUid = room.itUid
            lastRoomIt = room.itUid
        }
        currentBoardId = if (state.identity != null) "rooms-${session.mode.id}" else null
        enterArena(session, isRoom = true, isDaily = false)
        // Re-apply the roster so everyone already in the room appears on the first frame.
        onRosterChanged(state.players)
        startPublishing()
    }

    private fun startPublishing() {
        publishJob?.cancel()
        publishJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                val s = session
                val sol = solar
                if (s == null && sol == null) break
                if (sol != null) {
                    publishLocalSolar(sol)
                    if (sol.bots.isNotEmpty()) publishBotSolar(sol, sol.bots[tick % sol.bots.size])
                } else if (s != null) {
                    publishLocal(s)
                    // Bots are the host's to publish; spread them across ticks to smooth the writes.
                    if (s.bots.isNotEmpty()) {
                        val bot = s.bots[tick % s.bots.size]
                        publishBot(s, bot)
                    }
                }
                tick++
                val running = (s?.phase ?: sol?.phase) == GamePhase.RUNNING
                delay(if (running) PUBLISH_INTERVAL_MS else 1000L)
            }
        }
    }

    private suspend fun publishLocal(s: GameSession) {
        val ship = s.local
        val p = s.localProgress
        val now = nowMs()
        rooms.publishPlayer(
            roomCode,
            myUid(),
            mapOf(
                "t" to now,
                "la" to SphereMath.latitudeDeg(ship.pos),
                "lo" to SphereMath.longitudeDeg(ship.pos),
                "al" to ship.altKm,
                "hd" to SphereMath.headingDeg(ship.pos, ship.vel),
                "sp" to ship.speedKmS,
                "ro" to ship.rollRad,
                "bo" to ship.boosting,
                "laps" to p.laps,
                "gate" to p.nextGate,
                "gates" to p.gatesPassed,
                "score" to p.score,
                "alive" to p.alive,
                "finished" to p.finished,
                "finishMs" to p.finishTimeMs,
                "itS" to p.itTimeS,
                "nm" to p.nearMisses,
                "cr" to p.crashes,
                "seenAtMs" to now,
            ),
        )
        // Tag: the phone that was "it" announces who is now.
        if (s.mode == GameMode.TAG && s.itUid != lastRoomIt && s.itUid.isNotEmpty()) {
            lastRoomIt = s.itUid
            rooms.updateRoom(roomCode, mapOf("itUid" to s.itUid))
        }
    }

    private suspend fun publishBot(s: GameSession, bot: com.developerstring.satride.feature.play.multiplay.engine.Bot) {
        val ship = bot.ship
        val p = bot.progress
        val now = nowMs()
        rooms.publishPlayer(
            roomCode,
            s.botUid(bot),
            mapOf(
                "uid" to s.botUid(bot),
                "name" to bot.name,
                "color" to s.botColor(bot),
                "skin" to ship.skin.name,
                "isBot" to true,
                "owner" to myUid(),
                "slot" to 99,
                "t" to now,
                "la" to SphereMath.latitudeDeg(ship.pos),
                "lo" to SphereMath.longitudeDeg(ship.pos),
                "al" to ship.altKm,
                "hd" to SphereMath.headingDeg(ship.pos, ship.vel),
                "sp" to ship.speedKmS,
                "ro" to ship.rollRad,
                "bo" to ship.boosting,
                "laps" to p.laps,
                "gate" to p.nextGate,
                "gates" to p.gatesPassed,
                "score" to p.score,
                "alive" to p.alive,
                "finished" to p.finished,
                "itS" to p.itTimeS,
                "seenAtMs" to now,
            ),
        )
    }

    fun sendEmote(emote: String) {
        _uiState.update { it.copy(showEmotes = false) }
        pushToast(emote, 0)
        if (!_uiState.value.isRoomGame || roomCode.isEmpty()) return
        viewModelScope.launch {
            rooms.publishPlayer(roomCode, myUid(), mapOf("emote" to emote, "emoteAtMs" to nowMs()))
        }
    }

    fun toggleEmotes() = _uiState.update { it.copy(showEmotes = !it.showEmotes) }

    /** Leaves whatever room this phone is in and returns home. */
    fun leaveRoom(message: String? = null) {
        val code = roomCode
        val wasHost = _uiState.value.isHost
        val me = myUid()
        roomJob?.cancel()
        publishJob?.cancel()
        roomJob = null
        publishJob = null
        roomCode = ""
        if (code.isNotEmpty()) {
            viewModelScope.launch {
                rooms.leaveRoom(code, me)
                // A host walking out ends the room for everyone rather than leaving a lobby with
                // no one who can start it.
                if (wasHost) rooms.updateRoom(code, mapOf("status" to RoomDoc.STATUS_FINISHED))
            }
        }
        exitArena()
        _uiState.update {
            it.copy(
                stage = MultiPlayStage.HOME,
                room = null,
                players = emptyList(),
                isHost = false,
                isRoomGame = false,
                busy = false,
                error = message,
            )
        }
    }

    private fun myUid(): String = _uiState.value.identity?.uid ?: "local"

    // endregion

    // region Arena lifecycle

    private fun enterArena(session: GameSession, isRoom: Boolean, isDaily: Boolean) {
        exitArenaLoops()
        this.session = session
        driftBoosts = 0
        flybys = 0
        localFinishedAtMs = 0L
        val firstTime = _uiState.value.selectedMode.id !in _uiState.value.howToPlaySeen
        lastFrameNanos = 0L
        lastPhaseSeen = session.phase
        look.recentre()
        look.tiltYawDeg = 0.0
        look.tiltPitchDeg = 0.0
        camera.snapTo(session.local)
        refreshCatalogObstacles(session)
        _uiState.update {
            it.copy(
                stage = MultiPlayStage.ARENA,
                isRoomGame = isRoom,
                isDaily = isDaily,
                results = null,
                toasts = emptyList(),
                standings = emptyList(),
                hud = HudSnapshot(phase = session.phase, participants = 1 + session.bots.size),
                tiltCalibrated = false,
                showEmotes = false,
                // Shown the first time a player meets a mode, and never again unless they ask.
                //
                // Paused underneath it wherever pausing is allowed — reading the rules while the
                // countdown runs out and the race starts without you is worse than not being shown
                // them. A room game cannot pause (everyone else is flying), so there the card sits
                // over a live countdown and the player can dismiss it whenever they like.
                showHowToPlay = firstTime,
                paused = firstTime && !isRoom,
            )
        }
        startHudLoop()
        restartTilt()
    }

    private fun exitArenaLoops() {
        hudJob?.cancel()
        hudJob = null
        tiltJob?.cancel()
        tiltJob = null
    }

    private fun exitArena() {
        exitArenaLoops()
        session = null
        solar = null
        tilt.clear()
    }

    /** Back to the home screen from the arena or the results. */
    fun quitToHome() {
        if (_uiState.value.isRoomGame) {
            leaveRoom()
        } else {
            exitArena()
            _uiState.update { it.copy(stage = MultiPlayStage.HOME, paused = false, results = null) }
        }
    }

    /** From the results screen: the same solo setup again, new track. */
    fun playAgain() {
        val daily = _uiState.value.isDaily
        if (_uiState.value.isRoomGame) {
            quitToHome()
            return
        }
        startSolo(daily)
    }

    fun setPaused(paused: Boolean) {
        if (!_uiState.value.canPause) return
        _uiState.update { it.copy(paused = paused) }
        if (!paused) session?.resync(nowMs())
    }

    // endregion

    // region Controls

    /** The left stick: x steers, y climbs (screen up is negative y, so it is inverted here). */
    fun setStick(x: Float, y: Float) {
        val sol = solar
        if (sol != null) {
            stickActive = x != 0f || y != 0f
            // The stick is the pilot's veto over the autopilot.
            if (stickActive) sol.takeManualControl()
            sol.localInput.steer = x.toDouble().coerceIn(-1.0, 1.0)
            sol.localInput.climb = (-y).toDouble().coerceIn(-1.0, 1.0)
            return
        }
        val s = session ?: return
        stickActive = x != 0f || y != 0f
        s.localInput.steer = x.toDouble().coerceIn(-1.0, 1.0)
        s.localInput.climb = (-y).toDouble().coerceIn(-1.0, 1.0)
    }

    fun setDrift(held: Boolean) {
        session?.localInput?.drift = held
    }

    fun setBrake(held: Boolean) {
        braking = held
        solar?.let {
            if (held) it.takeManualControl()
            it.localInput.brake = held
        }
    }

    fun tapBoost() {
        boostRequested = true
    }

    fun lookDrag(dxPx: Float, dyPx: Float, widthPx: Float) {
        lookDragging = true
        look.drag(dxPx, dyPx, widthPx)
    }

    fun lookRelease() {
        lookDragging = false
    }

    fun recentreLook() {
        look.recentre()
        tilt.recentreLook()
        calibrateTilt()
    }

    fun calibrateTilt() {
        calibrateAtMs = nowMs()
        tiltPendingCalibration = true
    }

    private var tiltPendingCalibration = true

    /** Calibration waits for this instant: the pose at the very first sample is still mid-rotation. */
    private var calibrateAtMs = 0L
    private var lastPhaseSeen: GamePhase = GamePhase.WAITING

    private fun restartTilt() {
        tiltJob?.cancel()
        tiltJob = null
        look.tiltYawDeg = 0.0
        look.tiltPitchDeg = 0.0
        look.leanDeg = 0.0
        val state = _uiState.value
        if (!state.motionAvailable || (!state.motionCamera && !state.tiltSteer)) return
        tiltPendingCalibration = true
        calibrateAtMs = nowMs() + CALIBRATION_SETTLE_MS
        tiltJob = viewModelScope.launch(dispatchers.default) {
            attitude.rawAttitude.collect { sample ->
                if (tiltPendingCalibration) {
                    if (nowMs() < calibrateAtMs) return@collect
                    tilt.calibrate(sample.rotation)
                    tiltPendingCalibration = false
                    _uiState.update { it.copy(tiltCalibrated = true) }
                }
                if (!tilt.update(sample.rotation, sample.elapsedNanos)) return@collect
                val current = _uiState.value
                if (current.motionCamera) {
                    // Gyro-as-a-mouse: the look follows how the phone turns and settles home when
                    // it stops. A little of the wheel angle leans the camera into a banked turn.
                    look.tiltYawDeg = tilt.lookYawDeg
                    look.tiltPitchDeg = tilt.lookPitchDeg
                    look.leanDeg = (tilt.rollDeg * 0.25).coerceIn(-12.0, 12.0)
                }
                if (current.tiltSteer && !stickActive) {
                    session?.localInput?.steer = tilt.steer
                    solar?.let { if (it.autopilot.mode == PilotMode.MANUAL) it.localInput.steer = tilt.steer }
                }
            }
        }
    }

    // endregion

    // region Frame loop

    /**
     * One frame: advance the game, refresh the catalog obstacles now and then, and place the
     * camera. Called from the arena canvas's frame clock; touches no flows.
     */
    fun onFrame(frameNanos: Long) {
        solar?.let {
            solarFrame(it, frameNanos)
            return
        }
        val s = session ?: return
        val dt = if (lastFrameNanos == 0L) 1.0 / 60.0 else ((frameNanos - lastFrameNanos) / 1e9).coerceIn(0.0, 0.1)
        lastFrameNanos = frameNanos
        val now = nowMs()

        // Lights out: the player is holding the phone the way they will race, so that pose —
        // not the one from before the countdown — is the tilt camera's neutral.
        if (s.phase == GamePhase.RUNNING && lastPhaseSeen == GamePhase.COUNTDOWN) calibrateTilt()
        lastPhaseSeen = s.phase

        if (!_uiState.value.paused) {
            s.localInput.throttle = if (braking) 0.0 else 1.0
            s.localInput.boost = boostRequested
            s.tick(now)
            boostRequested = false
            s.localInput.boost = false
            frameCounter++
            if (frameCounter % 4 == 0) refreshCatalogObstacles(s)
            s.drainEvents(eventScratch)
            if (eventScratch.isNotEmpty()) {
                for (event in eventScratch) onGameEvent(event)
                eventScratch.clear()
            }
        }
        if (!lookDragging) look.relax(dt)
        camera.update(s.local, look, dt, pose, boosting = s.local.boosting, spinning = s.local.spinOutS > 0.0)
    }

    private val eventScratch = ArrayList<GameEvent>()

    private fun onGameEvent(event: GameEvent) {
        _events.tryEmit(event)
        when (event.kind) {
            GameEventKind.CRASH -> camera.shake(6.0)
            GameEventKind.BOUNCE -> solarCamera.shake(7.0)
            GameEventKind.DRIFT_BOOST -> driftBoosts++
            GameEventKind.SATELLITE_FLYBY -> flybys++
            else -> Unit
        }
        val text = when (event.kind) {
            GameEventKind.LAP -> "LAP ${event.detail}"
            GameEventKind.SATELLITE_FLYBY -> if (event.detail.isNotEmpty()) "FLYBY · ${event.detail}" else "FLYBY"
            GameEventKind.SHIELD_LOST -> "SHIELD LOST · ${event.detail} left"
            GameEventKind.TAGGED -> "YOU'RE IT!"
            GameEventKind.DISCOVERY -> "DISCOVERED · ${event.detail}"
            GameEventKind.ARRIVED -> "IN ORBIT · ${event.detail}"
            GameEventKind.BOUNCE -> "BOUNCED OFF ${event.detail}"
            else -> event.kind.label
        }
        pushToast(text, event.kind.points)
    }

    private fun pushToast(text: String, points: Int) {
        val toast = HudToast(id = toastCounter++, text = text, points = points, atMs = nowMs())
        _uiState.update { it.copy(toasts = (it.toasts + toast).takeLast(4)) }
    }

    // endregion

    // region HUD loop and results

    private fun startHudLoop() {
        hudJob?.cancel()
        hudJob = viewModelScope.launch {
            while (isActive) {
                val sol = solar
                if (sol != null) {
                    if (!solarHudTick(sol)) break
                    delay(HUD_INTERVAL_MS)
                    continue
                }
                val s = session ?: break
                val now = nowMs()
                val standings = s.standings()
                val place = standings.indexOfFirst { it.isLocal } + 1
                val ship = s.local
                val p = s.localProgress
                val itName = when {
                    s.itUid.isEmpty() -> ""
                    s.itUid == s.localUid -> "You"
                    else -> standings.firstOrNull { it.uid == s.itUid }?.name ?: ""
                }
                val (nearName, nearKm) = nearestCatalogSatellite(s)
                _uiState.update {
                    it.copy(
                        hud = HudSnapshot(
                            phase = s.phase,
                            speedKmS = ship.speedKmS,
                            altKm = ship.altKm,
                            lap = p.laps,
                            laps = s.mode.laps,
                            nextGate = p.nextGate,
                            gateCount = s.track.gateCount,
                            score = p.score,
                            boostCharges = ship.boostCharges,
                            boosting = ship.boosting,
                            drifting = ship.drifting,
                            driftCharge = ship.driftCharge,
                            shields = p.shields,
                            maxShields = s.mode.shields,
                            place = place,
                            participants = standings.size,
                            secondsToStart = s.secondsToStart,
                            secondsRemaining = s.secondsRemaining,
                            elapsedSeconds = s.elapsedSeconds,
                            alive = p.alive,
                            finished = p.finished,
                            isIt = s.itUid == s.localUid,
                            itName = itName,
                            nearestSatellite = nearName,
                            nearestSatelliteKm = nearKm,
                            lapFraction = p.lapFraction,
                            nextGateFraction = s.nextGateParameter() / (2.0 * kotlin.math.PI),
                            spinningOut = ship.spinOutS > 0.0,
                        ),
                        standings = standings,
                        toasts = it.toasts.filter { t -> now - t.atMs < TOAST_MS },
                    )
                }
                if (shouldFinish(s, now)) {
                    finishGame(s, standings)
                    break
                }
                delay(HUD_INTERVAL_MS)
            }
        }
    }

    private fun nearestCatalogSatellite(s: GameSession): Pair<String, Double> {
        var best = -1
        var bestD2 = 900.0 * 900.0
        val w = s.extraWorld
        val lx = s.local.world[0]
        val ly = s.local.world[1]
        val lz = s.local.world[2]
        for (i in 0 until s.extraCount) {
            val dx = w[i * 3] - lx
            val dy = w[i * 3 + 1] - ly
            val dz = w[i * 3 + 2] - lz
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 < bestD2) {
                bestD2 = d2
                best = i
            }
        }
        if (best < 0) return "" to 0.0
        return (s.extraNames?.getOrNull(best) ?: "") to sqrt(bestD2)
    }

    private fun shouldFinish(s: GameSession, now: Long): Boolean {
        if (s.phase == GamePhase.FINISHED) return true
        if (s.phase != GamePhase.RUNNING) return false
        val p = s.localProgress
        return when (s.mode) {
            GameMode.RACE -> {
                if (p.finished && localFinishedAtMs == 0L) localFinishedAtMs = now
                if (!p.finished) return false
                val others = s.bots.map { it.progress } + s.remotes.values.map { it.progress }
                val everyoneDone = others.all { it.finished || !it.alive }
                everyoneDone || now - localFinishedAtMs > RACE_GRACE_MS
            }

            GameMode.DODGE -> {
                if (!p.alive && localFinishedAtMs == 0L) localFinishedAtMs = now
                val others = s.bots.map { it.progress } + s.remotes.values.map { it.progress }
                val lastStanding = p.alive && others.isNotEmpty() && others.none { it.alive }
                lastStanding || (!p.alive && now - localFinishedAtMs > DODGE_GRACE_MS)
            }

            GameMode.TAG -> false

            GameMode.SOLAR -> false
        }
    }

    private fun finishGame(s: GameSession, standings: List<Standing>) {
        s.finishNow(nowMs())
        publishJob?.cancel()
        publishJob = null
        val p = s.localProgress
        val place = standings.indexOfFirst { it.isLocal } + 1
        val state = _uiState.value
        val outcome = GameOutcome(
            mode = s.mode,
            score = p.score,
            laps = p.laps,
            gatesPassed = p.gatesPassed,
            crashes = p.crashes,
            nearMisses = p.nearMisses,
            driftBoosts = driftBoosts,
            flybys = flybys,
            survivedSeconds = s.elapsedSeconds,
            finished = p.finished || s.mode != GameMode.RACE,
            place = place,
            participants = standings.size,
            isRoom = state.isRoomGame,
        )
        val fresh = AchievementJudge.unlocked(outcome).filter { it.id !in state.achievements }
        val previousBest = state.bestScores[s.mode.id] ?: -1
        val results = GameResults(
            mode = s.mode,
            standings = standings,
            place = place,
            score = p.score,
            isBest = p.score > previousBest,
            unlocked = fresh,
            outcome = outcome,
            leaderboardId = currentBoardId,
        )
        exitArenaLoops()
        _uiState.update {
            it.copy(stage = MultiPlayStage.RESULTS, results = results, paused = false, showEmotes = false)
        }
        viewModelScope.launch {
            settings.recordMultiplayResult(s.mode.id, p.score)
            if (fresh.isNotEmpty()) settings.unlockMultiplayAchievements(fresh.map { it.id })
            val board = currentBoardId
            val me = state.identity
            if (board != null && me != null) {
                rooms.submitScore(
                    board,
                    ScoreDoc(
                        uid = me.uid,
                        name = state.displayName,
                        score = p.score,
                        mode = s.mode.id,
                        skin = state.skin.name,
                        laps = p.laps,
                        nearMisses = p.nearMisses,
                        atMs = nowMs(),
                    ),
                )
            }
            // A finished room stays "racing" for the others until the host's phone says so.
            if (state.isRoomGame && state.isHost) {
                val allDone = standings.all { it.finished || !it.alive || it.isBot }
                if (allDone) rooms.updateRoom(roomCode, mapOf("status" to RoomDoc.STATUS_FINISHED))
            }
        }
    }

    // endregion

    // region Solar System mode

    private fun startSoloSolar(state: MultiPlayUiState, seed: Long, uid: String, daily: Boolean) {
        val sol = SolarSession(
            seed = seed,
            localUid = uid,
            localName = state.displayName.ifBlank { "You" },
            localColorArgb = state.colorArgb,
            botCount = state.soloBots,
            botSeed = seed xor 0x50L,
            localSlot = 0,
        )
        val now = nowMs()
        sol.schedule(startMs = now + SOLAR_COUNTDOWN_MS, endMs = now + SOLAR_COUNTDOWN_MS + SOLAR_SOLO_MS, nowMs = now)
        currentBoardId = if (daily && state.identity != null) dailyBoardId(GameMode.SOLAR) else null
        enterSolar(sol, isRoom = false, isDaily = daily)
    }

    private fun startRoomSolar(room: RoomDoc, me: String, mySlot: Int, botCount: Int) {
        val state = _uiState.value
        val sol = SolarSession(
            seed = room.seed,
            localUid = me,
            localName = state.displayName.ifBlank { "You" },
            localColorArgb = state.colorArgb,
            botCount = botCount,
            botSeed = room.seed xor 0x50L,
            localSlot = mySlot,
        )
        sol.schedule(startMs = room.startAtMs, endMs = room.endAtMs, nowMs = nowMs())
        currentBoardId = if (state.identity != null) "rooms-solar" else null
        enterSolar(sol, isRoom = true, isDaily = false)
        onRosterChanged(state.players)
        startPublishing()
    }

    private fun enterSolar(sol: SolarSession, isRoom: Boolean, isDaily: Boolean) {
        val solarFirstTime = !isRoom && GameMode.SOLAR.id !in _uiState.value.howToPlaySeen
        exitArenaLoops()
        session = null
        solar = sol
        localFinishedAtMs = 0L
        lastFrameNanos = 0L
        lastPhaseSeen = sol.phase
        look.recentre()
        look.tiltYawDeg = 0.0
        look.tiltPitchDeg = 0.0
        solarCamera.snapTo(sol.local)
        _uiState.update {
            it.copy(
                stage = MultiPlayStage.ARENA,
                isRoomGame = isRoom,
                isDaily = isDaily,
                results = null,
                toasts = emptyList(),
                standings = emptyList(),
                hud = HudSnapshot(phase = sol.phase, participants = 1 + sol.bots.size),
                solarHud = SolarHud(
                    phase = sol.phase,
                    target = Body.EARTH,
                    pilotMode = PilotMode.AUTO,
                    pilotPhase = PilotPhase.ORBIT,
                    participants = 1 + sol.bots.size,
                ),
                tiltCalibrated = false,
                showEmotes = false,
                // Shown the first time a player meets this mode; see the arena's copy of this.
                // A Solar expedition is always solo, so it can always be held while it is read.
                showHowToPlay = solarFirstTime,
                paused = solarFirstTime,
            )
        }
        startHudLoop()
        restartTilt()
    }

    private fun solarFrame(sol: SolarSession, frameNanos: Long) {
        val dt = if (lastFrameNanos == 0L) 1.0 / 60.0 else ((frameNanos - lastFrameNanos) / 1e9).coerceIn(0.0, 0.1)
        lastFrameNanos = frameNanos
        val now = nowMs()
        if (sol.phase == GamePhase.RUNNING && lastPhaseSeen == GamePhase.COUNTDOWN) calibrateTilt()
        lastPhaseSeen = sol.phase
        if (!_uiState.value.paused) {
            sol.tick(now)
            sol.drainEvents(eventScratch)
            if (eventScratch.isNotEmpty()) {
                for (event in eventScratch) onGameEvent(event)
                eventScratch.clear()
            }
        }
        if (!lookDragging) look.relax(dt)
        if (sol.local.bumpKm > 0.0) solarCamera.shake(sol.local.bumpKm)
        solarCamera.update(sol.local, look, dt, pose, orbiting = sol.autopilot.phase == PilotPhase.ORBIT)
    }

    /** Picks a destination. Auto direction and autopilot both fly toward it. */
    fun selectTarget(body: Body) {
        val sol = solar ?: return
        sol.setTarget(body)
        if (sol.autopilot.mode == PilotMode.MANUAL) pushToast("TARGET · ${body.label.uppercase()}", 0)
    }

    fun cycleTarget(step: Int) {
        val sol = solar ?: return
        val entries = Body.entries
        val current = sol.autopilot.target?.ordinal ?: -1
        val next = ((current + step) % entries.size + entries.size) % entries.size
        selectTarget(entries[next])
    }

    /** MANUAL, AIM (auto direction) or AUTO (autopilot). Needs a target for anything but MANUAL. */
    fun setPilotMode(mode: PilotMode) {
        val sol = solar ?: return
        if (mode != PilotMode.MANUAL && sol.autopilot.target == null) {
            pushToast("PICK A DESTINATION FIRST", 0)
            return
        }
        sol.setPilotMode(mode)
        stickActive = false
        sol.localInput.steer = 0.0
        sol.localInput.climb = 0.0
    }

    /** The speed-of-light dial, 0..1 on a logarithmic scale. */
    fun setWarpDial(dial: Double) {
        solar?.setWarp(SolarPhysics.dialToWarp(dial))
    }

    fun nudgeWarp(steps: Int) {
        val sol = solar ?: return
        val dial = SolarPhysics.warpToDial(sol.local.warp) + steps * 0.05
        sol.setWarp(SolarPhysics.dialToWarp(dial))
    }

    fun setAutoSpeed(enabled: Boolean) {
        val sol = solar ?: return
        if (enabled) sol.enableAutoSpeed() else sol.autoSpeed = false
    }

    fun setGovernor(enabled: Boolean) {
        solar?.governorEnabled = enabled
    }

    /** Ends the expedition now and goes to the log. */
    fun endExpedition() {
        val sol = solar ?: return
        sol.finishNow(nowMs())
        _uiState.update { it.copy(paused = false) }
    }

    private val shipScratch = DoubleArray(3)

    /** One HUD refresh for the solar arena. Returns false once the expedition has ended. */
    private fun solarHudTick(sol: SolarSession): Boolean {
        val now = nowMs()
        val standings = sol.standings()
        val place = standings.indexOfFirst { it.isLocal } + 1
        val ship = sol.local
        val p = sol.localProgress
        val target = sol.autopilot.target
        val remaining = sol.distanceToTargetKm()
        val eta = sol.etaSeconds()
        val nearest = sol.nearestBody()
        val bodyDistances = Body.entries.associateWith { b ->
            val d = sol.world.distanceTo(b, ship.pos[0], ship.pos[1], ship.pos[2]) - b.parkRadiusKm
            SolarPhysics.describeDistance(d.coerceAtLeast(0.0))
        }
        val pilotDistances = buildMap {
            fun put(uid: String, other: DoubleArray) {
                val dx = other[0] - ship.pos[0]
                val dy = other[1] - ship.pos[1]
                val dz = other[2] - ship.pos[2]
                put(uid, SolarPhysics.describeDistance(sqrt(dx * dx + dy * dy + dz * dz)))
            }
            sol.bots.forEach { put(sol.botUid(it), it.ship.pos) }
            sol.remotes.values.forEach { put(it.uid, it.ship.pos) }
        }
        val mapDots = buildList {
            for (body in Body.entries) {
                if (body == Body.SUN) continue
                sol.world.positionInto(body, shipScratch)
                add(mapDot(body.label, body.colorArgb, shipScratch, isLocal = false, isBody = true))
            }
            sol.bots.forEach { add(mapDot(it.name, sol.botColor(it), it.ship.pos, isLocal = false, isBody = false)) }
            sol.remotes.values.forEach {
                add(mapDot(it.name, it.colorArgb, it.ship.pos, isLocal = false, isBody = false))
            }
            add(mapDot(sol.localName, sol.localColorArgb, ship.pos, isLocal = true, isBody = false))
        }
        _uiState.update {
            it.copy(
                solarHud = SolarHud(
                    phase = sol.phase,
                    speedText = SolarPhysics.describeSpeed(ship.speedKmS),
                    warp = ship.warp,
                    warpDial = SolarPhysics.warpToDial(ship.warp),
                    warpText = SolarPhysics.describeWarp(ship.warp),
                    autoSpeed = sol.autoSpeed,
                    governor = sol.governorEnabled,
                    governed = ship.governed,
                    pilotMode = sol.autopilot.mode,
                    pilotPhase = sol.autopilot.phase,
                    target = target,
                    distanceText = if (remaining.isNaN()) "" else SolarPhysics.describeDistance(remaining),
                    etaText = if (eta.isNaN()) "" else SolarPhysics.describeEta(eta),
                    nearest = nearest,
                    altitudeText = SolarPhysics.describeDistance(sol.altitudeAbove(nearest).coerceAtLeast(0.0)),
                    visited = Body.entries.filter { b -> p.visited[b.ordinal] }.toSet(),
                    score = p.score,
                    place = place,
                    participants = standings.size,
                    secondsToStart = sol.secondsToStart,
                    secondsRemaining = sol.secondsRemaining,
                    elapsedSeconds = sol.elapsedSeconds,
                    heat = sol.sunHeat(),
                    grandTour = p.grandTour,
                    bodyDistances = bodyDistances,
                    pilotDistances = pilotDistances,
                    mapDots = mapDots,
                ),
                hud = it.hud.copy(phase = sol.phase, score = p.score, place = place, participants = standings.size),
                standings = standings,
                toasts = it.toasts.filter { t -> now - t.atMs < TOAST_MS },
            )
        }
        if (sol.phase == GamePhase.FINISHED) {
            finishSolar(sol, standings)
            return false
        }
        return true
    }

    private fun mapDot(label: String, color: Long, pos: DoubleArray, isLocal: Boolean, isBody: Boolean): MapDot {
        val r = sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2]) / SolarSession.AU_KM
        return MapDot(
            label = label,
            colorArgb = color,
            eclipticLongitudeDeg = Ephemeris.eclipticLongitudeDeg(pos[0], pos[1], pos[2]),
            radiusAu = r,
            isLocal = isLocal,
            isBody = isBody,
        )
    }

    private fun finishSolar(sol: SolarSession, standings: List<Standing>) {
        publishJob?.cancel()
        publishJob = null
        val p = sol.localProgress
        val place = standings.indexOfFirst { it.isLocal } + 1
        val state = _uiState.value
        val outcome = GameOutcome(
            mode = GameMode.SOLAR,
            score = p.score,
            laps = p.visitedCount,
            gatesPassed = p.visitedCount,
            crashes = p.bounces,
            nearMisses = p.ringDives,
            driftBoosts = 0,
            flybys = 0,
            survivedSeconds = sol.elapsedSeconds,
            finished = true,
            place = place,
            participants = standings.size,
            isRoom = state.isRoomGame,
            worldsVisited = p.visitedCount,
            sunGrazes = p.sunGrazes,
            grandTour = p.grandTour,
        )
        val fresh = AchievementJudge.unlocked(outcome).filter { it.id !in state.achievements }
        val previousBest = state.bestScores[GameMode.SOLAR.id] ?: -1
        val results = GameResults(
            mode = GameMode.SOLAR,
            standings = standings,
            place = place,
            score = p.score,
            isBest = p.score > previousBest,
            unlocked = fresh,
            outcome = outcome,
            leaderboardId = currentBoardId,
        )
        exitArenaLoops()
        _uiState.update {
            it.copy(stage = MultiPlayStage.RESULTS, results = results, paused = false, showEmotes = false)
        }
        viewModelScope.launch {
            settings.recordMultiplayResult(GameMode.SOLAR.id, p.score)
            if (fresh.isNotEmpty()) settings.unlockMultiplayAchievements(fresh.map { it.id })
            val board = currentBoardId
            val me = state.identity
            if (board != null && me != null) {
                rooms.submitScore(
                    board,
                    ScoreDoc(
                        uid = me.uid,
                        name = state.displayName,
                        score = p.score,
                        mode = GameMode.SOLAR.id,
                        skin = state.skin.name,
                        laps = p.visitedCount,
                        nearMisses = p.sunGrazes + p.ringDives,
                        atMs = nowMs(),
                    ),
                )
            }
            // The host ending the expedition ends the room for everyone, as when the host leaves.
            if (state.isRoomGame && state.isHost) {
                rooms.updateRoom(roomCode, mapOf("status" to RoomDoc.STATUS_FINISHED))
            }
        }
    }

    private suspend fun publishLocalSolar(sol: SolarSession) {
        val ship = sol.local
        val p = sol.localProgress
        val now = nowMs()
        rooms.publishPlayer(roomCode, myUid(), solarFields(ship, p, sol.autopilot, now))
    }

    private suspend fun publishBotSolar(sol: SolarSession, bot: SolarBot) {
        val now = nowMs()
        val fields = HashMap(solarFields(bot.ship, bot.progress, bot.autopilot, now))
        fields["uid"] = sol.botUid(bot)
        fields["name"] = bot.name
        fields["color"] = sol.botColor(bot)
        fields["skin"] = "OTHER"
        fields["isBot"] = true
        fields["owner"] = myUid()
        fields["slot"] = 99
        rooms.publishPlayer(roomCode, sol.botUid(bot), fields)
    }

    private fun solarFields(
        ship: com.developerstring.satride.feature.play.multiplay.solar.SolarShipState,
        p: com.developerstring.satride.feature.play.multiplay.solar.SolarProgress,
        pilot: com.developerstring.satride.feature.play.multiplay.solar.Autopilot,
        now: Long,
    ): Map<String, Any?> = mapOf(
        "t" to now,
        "px" to ship.pos[0],
        "py" to ship.pos[1],
        "pz" to ship.pos[2],
        "fx" to ship.fwd[0],
        "fy" to ship.fwd[1],
        "fz" to ship.fwd[2],
        "sp" to ship.speedKmS,
        "ro" to ship.rollRad,
        "wp" to ship.warp,
        "vis" to p.visitedCount,
        "tgt" to (pilot.target?.id ?: ""),
        "pm" to pilot.mode.name,
        "score" to p.score,
        "laps" to p.visitedCount,
        "gates" to p.visitedCount,
        "nm" to p.sunGrazes,
        "cr" to p.bounces,
        "alive" to true,
        "finished" to p.grandTour,
        "seenAtMs" to now,
    )

    private fun onSolarRosterChanged(sol: SolarSession, players: List<PlayerDoc>) {
        val me = myUid()
        val now = nowMs()
        val seen = HashSet<String>()
        for (doc in players) {
            if (doc.uid == me || doc.owner == me) continue
            seen.add(doc.uid)
            val remote = sol.remote(doc.uid)
            remote.name = doc.name
            remote.colorArgb = doc.color
            remote.warp = doc.warp
            if (doc.sentAtMs > 0L && (doc.px != 0.0 || doc.py != 0.0 || doc.pz != 0.0)) {
                remote.applySample(
                    doc.px, doc.py, doc.pz, doc.fx, doc.fy, doc.fz, doc.speedKmS, doc.rollRad,
                    sentAtMs = doc.sentAtMs, receivedAtMs = now,
                )
            }
            remote.progress.apply {
                score = doc.score
                reportedVisited = doc.worldsVisited
                sunGrazes = doc.nearMisses
                bounces = doc.crashes
                grandTour = doc.finished
                targetId = doc.targetId
                pilotMode = doc.pilotMode
            }
            if (doc.emote.isNotEmpty() && doc.emoteAtMs != remote.emoteAtMs) {
                remote.emote = doc.emote
                remote.emoteAtMs = doc.emoteAtMs
            }
        }
        val stale = sol.remotes.keys.filter { it !in seen }
        stale.forEach { sol.removeRemote(it) }
    }

    // endregion

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    /** Wall-clock instant for the renderer's sun and star positions. */
    fun simTime(): kotlin.time.Instant = clock.now()

    /** Wall-clock milliseconds for the renderer's emote timing. */
    fun wallMs(): Long = nowMs()

    override fun onCleared() {
        val code = roomCode
        val me = myUid()
        if (code.isNotEmpty()) {
            // Fire and forget: the view model is going away, but the seat should be freed.
            viewModelScope.launch { rooms.leaveRoom(code, me) }
        }
        super.onCleared()
    }

    private companion object {
        const val SOLO_COUNTDOWN_MS = 3_500L
        const val PUBLISH_INTERVAL_MS = 200L
        const val HUD_INTERVAL_MS = 125L
        const val TOAST_MS = 1_800L
        const val RACE_GRACE_MS = 40_000L
        const val DODGE_GRACE_MS = 2_500L
        const val CALIBRATION_SETTLE_MS = 1_200L
        const val SOLAR_COUNTDOWN_MS = 2_500L
        const val SOLAR_SOLO_MS = 30L * 60L * 1000L
    }
}
