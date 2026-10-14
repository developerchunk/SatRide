package com.developerstring.satride.feature.play.multiplay

import com.developerstring.satride.feature.play.multiplay.engine.GameMode

/**
 * The trophies. Local-only and judged from one game's numbers, so they cost nothing to keep and
 * cannot be lost to a network hiccup.
 */
enum class Achievement(val id: String, val title: String, val description: String, val emoji: String) {
    FIRST_FLIGHT("first_flight", "First Flight", "Finish any game.", "🚀"),
    FULL_LAP("full_lap", "Around the World", "Pass every gate in one lap.", "🌍"),
    UNTOUCHABLE("untouchable", "Untouchable", "Finish a race without a single crash.", "🛡️"),
    CLOSE_CALL("close_call", "Close Call", "Ten near misses in one game.", "😅"),
    DRIFT_KING("drift_king", "Drift King", "Fire eight drift boosts in one game.", "🌀"),
    SURVIVOR("survivor", "Survivor", "Last three minutes in Debris Dodge.", "⏱️"),
    TAG_MASTER("tag_master", "Not It", "Win a game of Satellite Tag.", "🏷️"),
    FLYBY("flyby", "Celebrity Flyby", "Buzz a real satellite from the catalog.", "📡"),
    PODIUM("podium", "Podium", "Finish first in a room of three or more.", "🏆"),
    SOCIALITE("socialite", "Full Grid", "Play in a room with five or more pilots.", "👥"),
    SPEED_DEMON("speed_demon", "Speed Demon", "Score 3,000 in a single game.", "⚡"),
    PATHFINDER("pathfinder", "Pathfinder", "Visit five worlds in one expedition.", "🧭"),
    SUN_GRAZER("sun_grazer", "Sun Grazer", "Fly within two radii of the Sun and live.", "☀️"),
    GRAND_TOUR("grand_tour", "Grand Tour", "See every planet in one expedition.", "🪐"),
    ;

    companion object {
        fun byId(id: String): Achievement? = entries.firstOrNull { it.id == id }
    }
}

/** What one finished game looked like, in the numbers the trophies care about. */
data class GameOutcome(
    val mode: GameMode,
    val score: Int,
    val laps: Int,
    val gatesPassed: Int,
    val crashes: Int,
    val nearMisses: Int,
    val driftBoosts: Int,
    val flybys: Int,
    val survivedSeconds: Double,
    val finished: Boolean,
    val place: Int,
    val participants: Int,
    val isRoom: Boolean,
    // Solar System mode.
    val worldsVisited: Int = 0,
    val sunGrazes: Int = 0,
    val grandTour: Boolean = false,
)

object AchievementJudge {
    fun unlocked(outcome: GameOutcome): List<Achievement> = buildList {
        if (outcome.finished || outcome.survivedSeconds > 30.0) add(Achievement.FIRST_FLIGHT)
        if (outcome.laps >= 1) add(Achievement.FULL_LAP)
        if (outcome.mode == GameMode.RACE && outcome.finished && outcome.crashes == 0) add(Achievement.UNTOUCHABLE)
        if (outcome.nearMisses >= 10) add(Achievement.CLOSE_CALL)
        if (outcome.driftBoosts >= 8) add(Achievement.DRIFT_KING)
        if (outcome.mode == GameMode.DODGE && outcome.survivedSeconds >= 180.0) add(Achievement.SURVIVOR)
        if (outcome.mode == GameMode.TAG && outcome.place == 1 && outcome.participants >= 2) add(Achievement.TAG_MASTER)
        if (outcome.flybys >= 1) add(Achievement.FLYBY)
        if (outcome.isRoom && outcome.place == 1 && outcome.participants >= 3) add(Achievement.PODIUM)
        if (outcome.isRoom && outcome.participants >= 5) add(Achievement.SOCIALITE)
        if (outcome.score >= 3000) add(Achievement.SPEED_DEMON)
        if (outcome.worldsVisited >= 5) add(Achievement.PATHFINDER)
        if (outcome.sunGrazes >= 1) add(Achievement.SUN_GRAZER)
        if (outcome.grandTour) add(Achievement.GRAND_TOUR)
    }
}
