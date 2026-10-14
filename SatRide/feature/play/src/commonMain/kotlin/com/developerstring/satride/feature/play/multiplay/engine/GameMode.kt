package com.developerstring.satride.feature.play.multiplay.engine

/**
 * The three things a room can play.
 *
 * All three share one engine — the same ships, the same track, the same debris — and differ only
 * in what counts. That is deliberate: a player who has learned to fly in one mode has learned to
 * fly in all of them, and the modes are variations on a skill rather than separate games.
 */
enum class GameMode(
    val id: String,
    val title: String,
    val tagline: String,
    /** Laps to finish; 0 means the mode is untimed by laps. */
    val laps: Int,
    /** Debris per lap of track at the start. Dodge ramps this up over time. */
    val debrisPerLap: Int,
    /** Hits before a ship is out. 0 means crashes only cost speed and points. */
    val shields: Int,
) {
    RACE(
        id = "race",
        title = "Orbit Race",
        tagline = "Three laps through the gates. Drift through the debris.",
        laps = 3,
        debrisPerLap = 70,
        shields = 0,
    ),
    DODGE(
        id = "dodge",
        title = "Debris Dodge",
        tagline = "The field gets thicker every minute. Last one flying wins.",
        laps = 0,
        debrisPerLap = 110,
        shields = 3,
    ),
    TAG(
        id = "tag",
        title = "Satellite Tag",
        tagline = "One of you is it. Ram someone to pass it on.",
        laps = 0,
        debrisPerLap = 45,
        shields = 0,
    ),

    /**
     * The odd one out: not a race on a sphere but a starship in the real solar system. It shares
     * the pilot, the rooms, the standings and the trophies, and nothing of the orbital engine —
     * see `multiplay/solar/`.
     */
    SOLAR(
        id = "solar",
        title = "Solar System",
        tagline = "Ride a starship to the real planets, today. Set your own speed of light.",
        laps = 0,
        debrisPerLap = 0,
        shields = 0,
    ),
    ;

    /** True for the free-flight solar mode, false for the three orbital arena modes. */
    val isSolar: Boolean get() = this == SOLAR

    companion object {
        fun fromId(id: String?): GameMode = entries.firstOrNull { it.id == id } ?: RACE
    }
}

/** What a player does with the sticks and buttons, sampled once per physics step. */
class ShipInput {
    /** −1 (hard left) to +1 (hard right). */
    var steer: Double = 0.0

    /** −1 (dive) to +1 (climb). */
    var climb: Double = 0.0

    /** 0 to 1. The engine idles at a minimum fraction, so 0 is "coast", not "stop". */
    var throttle: Double = 1.0

    /** Held: the nose turns faster than the velocity follows, charging a boost for release. */
    var drift: Boolean = false

    /** Tap: spend a stored boost charge. */
    var boost: Boolean = false

    fun clear() {
        steer = 0.0
        climb = 0.0
        throttle = 1.0
        drift = false
        boost = false
    }

    fun copyFrom(other: ShipInput) {
        steer = other.steer
        climb = other.climb
        throttle = other.throttle
        drift = other.drift
        boost = other.boost
    }
}
