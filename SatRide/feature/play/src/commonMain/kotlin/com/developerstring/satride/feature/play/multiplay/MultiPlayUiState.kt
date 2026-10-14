package com.developerstring.satride.feature.play.multiplay

import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.feature.play.multiplay.engine.GameMode
import com.developerstring.satride.feature.play.multiplay.engine.GamePhase
import com.developerstring.satride.feature.play.multiplay.engine.Standing
import com.developerstring.satride.feature.play.multiplay.net.PlayerDoc
import com.developerstring.satride.feature.play.multiplay.net.PlayerIdentityInfo
import com.developerstring.satride.feature.play.multiplay.net.RoomDoc
import com.developerstring.satride.feature.play.multiplay.net.ScoreDoc
import com.developerstring.satride.feature.play.multiplay.solar.PilotMode
import com.developerstring.satride.feature.play.multiplay.solar.PilotPhase
import com.developerstring.satride.feature.play.solar.Body

/** Which screen of Multi-Play is showing. */
enum class MultiPlayStage { HOME, LOBBY, ARENA, RESULTS }

/**
 * The numbers on the HUD, refreshed a few times a second.
 *
 * Nothing here changes per frame on purpose: the canvas reads the session directly, and this
 * is what the text and gauges around it are built from.
 */
data class HudSnapshot(
    val phase: GamePhase = GamePhase.WAITING,
    val speedKmS: Double = 0.0,
    val altKm: Double = 0.0,
    val lap: Int = 0,
    val laps: Int = 0,
    val nextGate: Int = 0,
    val gateCount: Int = 24,
    val score: Int = 0,
    val boostCharges: Int = 0,
    val boosting: Boolean = false,
    val drifting: Boolean = false,
    val driftCharge: Double = 0.0,
    val shields: Int = 0,
    val maxShields: Int = 0,
    val place: Int = 1,
    val participants: Int = 1,
    val secondsToStart: Double = 0.0,
    val secondsRemaining: Double = 0.0,
    val elapsedSeconds: Double = 0.0,
    val alive: Boolean = true,
    val finished: Boolean = false,
    val isIt: Boolean = false,
    val itName: String = "",
    val nearestSatellite: String = "",
    val nearestSatelliteKm: Double = 0.0,
    val lapFraction: Double = 0.0,
    val nextGateFraction: Double = 0.0,
    val spinningOut: Boolean = false,
)

/**
 * The Solar System arena's HUD, refreshed a few times a second alongside [HudSnapshot].
 *
 * Texts are formatted here rather than in the composable so the frame loop's numbers (AU,
 * light-minutes, multiples of c) are turned into words once per tick, not per recomposition.
 */
data class SolarHud(
    val phase: GamePhase = GamePhase.WAITING,
    val speedText: String = "0 km/s",
    val warp: Double = 1.0,
    val warpDial: Double = 0.5,
    val warpText: String = "1.00 c",
    val autoSpeed: Boolean = true,
    val governor: Boolean = true,
    val governed: Boolean = false,
    val pilotMode: PilotMode = PilotMode.MANUAL,
    val pilotPhase: PilotPhase = PilotPhase.IDLE,
    val target: Body? = null,
    val distanceText: String = "",
    val etaText: String = "",
    val nearest: Body = Body.EARTH,
    val altitudeText: String = "",
    val visited: Set<Body> = emptySet(),
    val score: Int = 0,
    val place: Int = 1,
    val participants: Int = 1,
    val secondsToStart: Double = 0.0,
    val secondsRemaining: Double = 0.0,
    val elapsedSeconds: Double = 0.0,
    val heat: Float = 0f,
    val grandTour: Boolean = false,
    /** Distance from the ship to each world's parking orbit, for the destination chips. */
    val bodyDistances: Map<Body, String> = emptyMap(),
    /** Distance from the ship to each other pilot, keyed by uid, for the standings. */
    val pilotDistances: Map<String, String> = emptyMap(),
    /** Everything on the top-down map: worlds and ships in ecliptic polar coordinates. */
    val mapDots: List<MapDot> = emptyList(),
)

/** One mark on the solar-system minimap. */
data class MapDot(
    val label: String,
    val colorArgb: Long,
    val eclipticLongitudeDeg: Double,
    val radiusAu: Double,
    val isLocal: Boolean,
    val isBody: Boolean,
)

/** A short-lived message over the arena: "NEAR MISS +30". */
data class HudToast(val id: Long, val text: String, val points: Int, val atMs: Long)

/** The end-of-game screen's contents. */
data class GameResults(
    val mode: GameMode,
    val standings: List<Standing>,
    val place: Int,
    val score: Int,
    val isBest: Boolean,
    val unlocked: List<Achievement>,
    val outcome: GameOutcome,
    val leaderboardId: String?,
)

data class MultiPlayUiState(
    val stage: MultiPlayStage = MultiPlayStage.HOME,
    val identity: PlayerIdentityInfo? = null,
    val identityError: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val multiplayerAvailable: Boolean = true,
    val motionAvailable: Boolean = false,

    // The pilot.
    val skin: SatClass = SatClass.OTHER,
    val colorArgb: Long = 0xFF3FE0FF,
    val displayName: String = "",
    val motionCamera: Boolean = true,
    val tiltSteer: Boolean = false,

    // Home.
    val selectedMode: GameMode = GameMode.RACE,
    val soloBots: Int = 4,
    val leaderboard: List<ScoreDoc> = emptyList(),
    val bestScores: Map<String, Int> = emptyMap(),
    val achievements: Set<String> = emptySet(),
    /** Modes whose how-to-play card has already been shown; see `UserSettings`. */
    val howToPlaySeen: Set<String> = emptySet(),
    val gamesPlayed: Int = 0,
    val joinCode: String = "",

    // Lobby.
    val room: RoomDoc? = null,
    val players: List<PlayerDoc> = emptyList(),
    val isHost: Boolean = false,
    val lobbyBots: Int = 0,
    val roomSecondsLeft: Int = 0,

    // Arena.
    val hud: HudSnapshot = HudSnapshot(),
    val solarHud: SolarHud = SolarHud(),
    val standings: List<Standing> = emptyList(),
    val toasts: List<HudToast> = emptyList(),
    val paused: Boolean = false,
    val tiltCalibrated: Boolean = false,
    val isDaily: Boolean = false,
    val isRoomGame: Boolean = false,
    val showEmotes: Boolean = false,
    /**
     * Whether the how-to-play card is over the arena.
     *
     * Part of the arena state rather than a one-shot effect because it has to survive a rotation
     * and a process death — a player who tilts the phone while reading the rules should not lose
     * them.
     */
    val showHowToPlay: Boolean = false,

    // Results.
    val results: GameResults? = null,
) {
    val canPause: Boolean get() = !isRoomGame
}

/** The quick-chat set. Short, universal, and readable at 200 km/s. */
val Emotes: List<String> = listOf("👋", "😎", "🔥", "😱", "🫡", "🎉", "💀", "🐢")
