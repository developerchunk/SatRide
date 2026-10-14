package com.developerstring.satride.feature.play.multiplay.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A room, at `rooms/{code}`.
 *
 * Fifteen minutes from creation, hard stop. A room is a place people meet for one game, not a
 * clan; letting rooms live on would leave the collection full of empty ones and the rules
 * cannot tell an empty room from a full one without a query per write.
 *
 * Every field is a plain number or string, and every Firestore field name is spelled out with
 * `@SerialName` so the security rules — which name fields explicitly — cannot drift from a
 * Kotlin rename. See `firebase/firestore.rules` at the repository root.
 */
@Serializable
data class RoomDoc(
    @SerialName("code") val code: String = "",
    @SerialName("hostUid") val hostUid: String = "",
    @SerialName("hostName") val hostName: String = "",
    @SerialName("mode") val mode: String = "race",
    @SerialName("seed") val seed: Long = 0L,
    @SerialName("createdAtMs") val createdAtMs: Long = 0L,
    @SerialName("expiresAtMs") val expiresAtMs: Long = 0L,
    /** 0 until the host presses start. */
    @SerialName("startAtMs") val startAtMs: Long = 0L,
    @SerialName("endAtMs") val endAtMs: Long = 0L,
    /** `lobby`, `racing` or `finished`. */
    @SerialName("status") val status: String = STATUS_LOBBY,
    @SerialName("maxPlayers") val maxPlayers: Int = MAX_PLAYERS,
    @SerialName("botCount") val botCount: Int = 0,
    /** Tag only: who is it. Written by whoever was it when they passed it on. */
    @SerialName("itUid") val itUid: String = "",
) {
    val isLobby: Boolean get() = status == STATUS_LOBBY
    val isRacing: Boolean get() = status == STATUS_RACING
    val isFinished: Boolean get() = status == STATUS_FINISHED

    fun isExpired(nowMs: Long): Boolean = expiresAtMs in 1..nowMs

    companion object {
        const val STATUS_LOBBY = "lobby"
        const val STATUS_RACING = "racing"
        const val STATUS_FINISHED = "finished"
        const val MAX_PLAYERS = 10
        const val ROOM_LIFETIME_MS: Long = 15L * 60L * 1000L

        /** Seconds between the host pressing start and the lights going green. */
        const val COUNTDOWN_MS: Long = 5_000L
    }
}

/**
 * One player in a room, at `rooms/{code}/players/{uid}`.
 *
 * Identity and live flight state share one document on purpose: a ship is drawn from a single
 * snapshot, and splitting the two would mean joining two listeners per player on the receiving
 * side. The flight fields are short-named because this document is rewritten several times a
 * second by up to ten players and every byte is billed.
 */
@Serializable
data class PlayerDoc(
    @SerialName("uid") val uid: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("color") val color: Long = 0xFF3FE0FF,
    /** A `SatClass` name: the craft model. */
    @SerialName("skin") val skin: String = "OTHER",
    @SerialName("isBot") val isBot: Boolean = false,
    /** The uid of the phone simulating this craft (the player themself, or a bot's host). */
    @SerialName("owner") val owner: String = "",
    @SerialName("ready") val ready: Boolean = false,
    @SerialName("slot") val slot: Int = 0,
    @SerialName("joinedAtMs") val joinedAtMs: Long = 0L,
    @SerialName("seenAtMs") val seenAtMs: Long = 0L,

    // Live flight state.
    @SerialName("t") val sentAtMs: Long = 0L,
    @SerialName("la") val latDeg: Double = 0.0,
    @SerialName("lo") val lonDeg: Double = 0.0,
    @SerialName("al") val altKm: Double = 620.0,
    @SerialName("hd") val headingDeg: Double = 0.0,
    @SerialName("sp") val speedKmS: Double = 0.0,
    @SerialName("ro") val rollRad: Double = 0.0,
    @SerialName("bo") val boosting: Boolean = false,

    // Progress.
    @SerialName("laps") val laps: Int = 0,
    @SerialName("gate") val nextGate: Int = 0,
    @SerialName("gates") val gatesPassed: Int = 0,
    @SerialName("score") val score: Int = 0,
    @SerialName("alive") val alive: Boolean = true,
    @SerialName("finished") val finished: Boolean = false,
    @SerialName("finishMs") val finishMs: Long = 0L,
    @SerialName("itS") val itTimeS: Double = 0.0,
    @SerialName("nm") val nearMisses: Int = 0,
    @SerialName("cr") val crashes: Int = 0,

    @SerialName("emote") val emote: String = "",
    @SerialName("emoteAtMs") val emoteAtMs: Long = 0L,

    // Solar System mode: heliocentric equatorial position (km) and nose, the dial, the log.
    @SerialName("px") val px: Double = 0.0,
    @SerialName("py") val py: Double = 0.0,
    @SerialName("pz") val pz: Double = 0.0,
    @SerialName("fx") val fx: Double = 0.0,
    @SerialName("fy") val fy: Double = 1.0,
    @SerialName("fz") val fz: Double = 0.0,
    @SerialName("wp") val warp: Double = 1.0,
    @SerialName("vis") val worldsVisited: Int = 0,
    @SerialName("tgt") val targetId: String = "",
    @SerialName("pm") val pilotMode: String = "",
)

/** A daily leaderboard entry at `leaderboards/{mode}-{yyyy-mm-dd}/scores/{uid}`. */
@Serializable
data class ScoreDoc(
    @SerialName("uid") val uid: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("score") val score: Int = 0,
    @SerialName("mode") val mode: String = "race",
    @SerialName("skin") val skin: String = "OTHER",
    @SerialName("laps") val laps: Int = 0,
    @SerialName("nearMisses") val nearMisses: Int = 0,
    @SerialName("atMs") val atMs: Long = 0L,
)

/** Room codes: six characters from an alphabet with no look-alikes, typed across a table. */
object RoomCodes {
    const val ALPHABET: String = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH: Int = 6

    fun generate(random: kotlin.random.Random = kotlin.random.Random.Default): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Uppercases and drops anything not in the alphabet, so a pasted "abc-123" still works. */
    fun normalize(raw: String): String = raw.uppercase().filter { it in ALPHABET }.take(LENGTH)

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }
}
