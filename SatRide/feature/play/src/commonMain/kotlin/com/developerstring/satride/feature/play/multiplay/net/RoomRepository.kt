package com.developerstring.satride.feature.play.multiplay.net

import co.touchlab.kermit.Logger
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.Direction
import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The multiplayer backend: rooms, the players in them, and the daily leaderboards.
 *
 * Firestore rather than the Realtime Database because Firestore is the one already linked on all
 * three platforms for profiles. It is not a game server — nothing here is authoritative — it is a
 * shared whiteboard each phone writes its own craft onto a few times a second and reads
 * everyone else's from. That is enough for ten craft at arcade speed once each phone
 * dead-reckons between samples (see `RemoteShip`).
 */
interface RoomRepository {
    /** False on a build with no Firebase, where only solo play is offered. */
    val isAvailable: Boolean

    suspend fun createRoom(room: RoomDoc, host: PlayerDoc): Result<RoomDoc>

    /** Joins an existing room; fails with a readable message when it is full, over, or missing. */
    suspend fun joinRoom(code: String, player: PlayerDoc): Result<RoomDoc>

    suspend fun leaveRoom(code: String, uid: String)

    fun observeRoom(code: String): Flow<RoomDoc?>

    fun observePlayers(code: String): Flow<List<PlayerDoc>>

    /** Merges [fields] into the room document. Host-only by the rules. */
    suspend fun updateRoom(code: String, fields: Map<String, Any?>): Result<Unit>

    /** Merges [fields] into a player document — the live state publish. */
    suspend fun publishPlayer(code: String, uid: String, fields: Map<String, Any?>): Result<Unit>

    suspend fun submitScore(boardId: String, score: ScoreDoc): Result<Unit>

    fun observeLeaderboard(boardId: String, limit: Int = 25): Flow<List<ScoreDoc>>
}

class FirestoreRoomRepository(private val firestore: FirebaseFirestore = Firebase.firestore) : RoomRepository {

    private val logger = Logger.withTag("Rooms")

    override val isAvailable: Boolean = true

    private fun rooms() = firestore.collection(ROOMS)

    private fun players(code: String) = rooms().document(code).collection(PLAYERS)

    override suspend fun createRoom(room: RoomDoc, host: PlayerDoc): Result<RoomDoc> = runCatching {
        // A code collision is astronomically unlikely (31^6) but cheap to check.
        var code = room.code
        repeat(4) {
            val existing = rooms().document(code).get()
            if (!existing.exists) return@repeat
            code = RoomCodes.generate()
        }
        val created = room.copy(code = code)
        rooms().document(code).set(created)
        players(code).document(host.uid).set(host)
        created
    }.onFailure { logger.w(it) { "creating a room failed" } }

    override suspend fun joinRoom(code: String, player: PlayerDoc): Result<RoomDoc> = runCatching {
        val snapshot = rooms().document(code).get()
        if (!snapshot.exists) error("No room with code $code.")
        val room = snapshot.data<RoomDoc>()
        val now = player.joinedAtMs
        if (room.isExpired(now) || room.isFinished) error("That room is over.")
        val roster = players(code).get().documents
        if (roster.any { it.id == player.uid }) {
            players(code).document(player.uid).set(player.copy(slot = roster.indexOfFirst { it.id == player.uid }))
            return@runCatching room
        }
        if (roster.size >= room.maxPlayers) error("That room is full (${room.maxPlayers} players).")
        if (room.isRacing) error("That race has already started.")
        val takenSlots = roster.mapNotNull { runCatching { it.data<PlayerDoc>().slot }.getOrNull() }.toSet()
        val slot = (0 until room.maxPlayers).first { it !in takenSlots }
        players(code).document(player.uid).set(player.copy(slot = slot))
        room
    }.onFailure { logger.w { "joining $code failed: ${it.message}" } }

    override suspend fun leaveRoom(code: String, uid: String) {
        runCatching { players(code).document(uid).delete() }
            .onFailure { logger.w(it) { "leaving $code failed" } }
    }

    override fun observeRoom(code: String): Flow<RoomDoc?> = rooms().document(code).snapshots
        .map { snapshot -> if (snapshot.exists) snapshot.data<RoomDoc>() else null }
        .catch { error ->
            logger.w(error) { "room $code listener failed" }
            emit(null)
        }

    override fun observePlayers(code: String): Flow<List<PlayerDoc>> = players(code).snapshots
        .map { snapshot ->
            snapshot.documents.mapNotNull { doc ->
                runCatching { doc.data<PlayerDoc>() }
                    .onFailure { logger.w { "bad player doc ${doc.id}: ${it.message}" } }
                    .getOrNull()
            }
        }
        .catch { error ->
            logger.w(error) { "players of $code listener failed" }
            emit(emptyList())
        }

    override suspend fun updateRoom(code: String, fields: Map<String, Any?>): Result<Unit> =
        runCatching { rooms().document(code).set(fields, merge = true) }
            .onFailure { logger.w(it) { "updating room $code failed" } }

    override suspend fun publishPlayer(code: String, uid: String, fields: Map<String, Any?>): Result<Unit> =
        runCatching { players(code).document(uid).set(fields, merge = true) }
            .onFailure { logger.w { "publishing $uid to $code failed: ${it.message}" } }

    override suspend fun submitScore(boardId: String, score: ScoreDoc): Result<Unit> = runCatching {
        val doc = firestore.collection(LEADERBOARDS).document(boardId).collection(SCORES).document(score.uid)
        // Only ever improve: a bad run must not overwrite a good one.
        val existing = doc.get()
        if (existing.exists) {
            val previous = runCatching { existing.data<ScoreDoc>().score }.getOrDefault(0)
            if (previous >= score.score) return@runCatching
        }
        doc.set(score)
    }.onFailure { logger.w(it) { "submitting a score failed" } }

    override fun observeLeaderboard(boardId: String, limit: Int): Flow<List<ScoreDoc>> =
        firestore.collection(LEADERBOARDS).document(boardId).collection(SCORES)
            .orderBy("score", Direction.DESCENDING)
            .limit(limit)
            .snapshots
            .map { snapshot -> snapshot.documents.mapNotNull { runCatching { it.data<ScoreDoc>() }.getOrNull() } }
            .catch { error ->
                logger.w(error) { "leaderboard $boardId listener failed" }
                emit(emptyList())
            }

    private companion object {
        const val ROOMS = "rooms"
        const val PLAYERS = "players"
        const val LEADERBOARDS = "leaderboards"
        const val SCORES = "scores"
    }
}

/** The backend on a build with no Firebase: solo play only, and every room call says so. */
class UnavailableRoomRepository : RoomRepository {
    override val isAvailable: Boolean = false

    private fun unavailable(): Result<Nothing> =
        Result.failure(IllegalStateException("Multiplayer needs Firebase, which this build does not have."))

    override suspend fun createRoom(room: RoomDoc, host: PlayerDoc): Result<RoomDoc> = unavailable()

    override suspend fun joinRoom(code: String, player: PlayerDoc): Result<RoomDoc> = unavailable()

    override suspend fun leaveRoom(code: String, uid: String) = Unit

    override fun observeRoom(code: String): Flow<RoomDoc?> = flowOf(null)

    override fun observePlayers(code: String): Flow<List<PlayerDoc>> = flowOf(emptyList())

    override suspend fun updateRoom(code: String, fields: Map<String, Any?>): Result<Unit> = unavailable()

    override suspend fun publishPlayer(code: String, uid: String, fields: Map<String, Any?>): Result<Unit> =
        unavailable()

    override suspend fun submitScore(boardId: String, score: ScoreDoc): Result<Unit> = unavailable()

    override fun observeLeaderboard(boardId: String, limit: Int): Flow<List<ScoreDoc>> = flowOf(emptyList())
}
