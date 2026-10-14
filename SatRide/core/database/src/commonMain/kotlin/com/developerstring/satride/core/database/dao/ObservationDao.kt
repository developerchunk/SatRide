package com.developerstring.satride.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.developerstring.satride.core.database.entity.ArtifactEntity
import com.developerstring.satride.core.database.entity.ObservationEntity
import kotlinx.coroutines.flow.Flow

/**
 * The decoded-artifact gallery and the radio session log.
 *
 * Both tables exist from this migration onwards even though P10 is what fills them: adding them
 * now costs nothing and means the gallery ships without a schema change.
 */
@Dao
interface ArtifactDao {

    @Insert
    suspend fun insert(artifact: ArtifactEntity): Long

    @Query("SELECT * FROM artifacts ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts WHERE noradId = :noradId ORDER BY createdAtMillis DESC")
    fun observeForSatellite(noradId: Int): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts WHERE kind = :kind ORDER BY createdAtMillis DESC")
    fun observeByKind(kind: String): Flow<List<ArtifactEntity>>

    @Query("DELETE FROM artifacts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ObservationDao {

    @Upsert
    suspend fun upsert(observation: ObservationEntity): Long

    @Query("SELECT * FROM observations ORDER BY startedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<ObservationEntity>>

    @Query("SELECT * FROM observations WHERE endedAtMillis IS NULL ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun activeSession(): ObservationEntity?

    @Query("UPDATE observations SET endedAtMillis = :endedAtMillis WHERE id = :id")
    suspend fun close(id: Long, endedAtMillis: Long)
}
