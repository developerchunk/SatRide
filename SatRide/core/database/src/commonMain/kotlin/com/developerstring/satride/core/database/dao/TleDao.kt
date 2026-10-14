package com.developerstring.satride.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.developerstring.satride.core.database.entity.TleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TleDao {

    /**
     * Writes element sets, keeping the newer of the stored and incoming epoch.
     *
     * The comparison lives in SQL rather than in Kotlin because it has to be atomic against a
     * concurrent refresh: two sources finishing at the same moment must not be able to interleave
     * a read and a write such that the older one lands last. `excluded` refers to the incoming
     * row, so this is a single statement.
     */
    @Query(
        """
        INSERT INTO tles (noradId, name, line1, line2, epochMillis, fetchedAtMillis, sourceId)
        VALUES (:noradId, :name, :line1, :line2, :epochMillis, :fetchedAtMillis, :sourceId)
        ON CONFLICT(noradId) DO UPDATE SET
            name = excluded.name,
            line1 = excluded.line1,
            line2 = excluded.line2,
            epochMillis = excluded.epochMillis,
            fetchedAtMillis = excluded.fetchedAtMillis,
            sourceId = excluded.sourceId
        WHERE excluded.epochMillis > tles.epochMillis
        """,
    )
    suspend fun upsertIfNewer(
        noradId: Int,
        name: String,
        line1: String,
        line2: String,
        epochMillis: Long,
        fetchedAtMillis: Long,
        sourceId: String,
    )

    /**
     * Applies a whole fetch in one transaction.
     *
     * Per-row rather than a bulk statement because the newest-epoch rule has to be evaluated
     * against whatever is already stored; the transaction is what makes the batch atomic, so a
     * refresh interrupted halfway leaves the previous catalog intact rather than a mixture.
     */
    @Transaction
    suspend fun upsertAllIfNewer(tles: List<TleEntity>) {
        tles.forEach {
            upsertIfNewer(
                noradId = it.noradId,
                name = it.name,
                line1 = it.line1,
                line2 = it.line2,
                epochMillis = it.epochMillis,
                fetchedAtMillis = it.fetchedAtMillis,
                sourceId = it.sourceId,
            )
        }
    }

    /** Unconditional write, for the bundled snapshot and for user-imported element sets. */
    @Upsert
    suspend fun upsert(tles: List<TleEntity>)

    @Query("SELECT * FROM tles WHERE noradId = :noradId")
    suspend fun find(noradId: Int): TleEntity?

    @Query("SELECT * FROM tles WHERE noradId IN (:noradIds)")
    suspend fun findAll(noradIds: List<Int>): List<TleEntity>

    @Query("SELECT * FROM tles")
    fun observeAll(): Flow<List<TleEntity>>

    /**
     * The oldest epoch we hold, which is what the staleness indicator reports.
     *
     * The oldest rather than the newest: freshness should be reported by the worst element set
     * the user might point at, not flattered by the best.
     */
    @Query("SELECT MIN(epochMillis) FROM tles")
    fun observeOldestEpochMillis(): Flow<Long?>

    @Query("SELECT MIN(epochMillis) FROM tles")
    suspend fun oldestEpochMillis(): Long?

    @Query("SELECT MAX(fetchedAtMillis) FROM tles")
    fun observeLastFetchMillis(): Flow<Long?>

    @Query("SELECT MAX(fetchedAtMillis) FROM tles")
    suspend fun lastFetchMillis(): Long?

    @Query("SELECT COUNT(*) FROM tles")
    suspend fun count(): Int
}
