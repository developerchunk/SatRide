package com.developerstring.satride.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.developerstring.satride.core.database.entity.PassAlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PassAlertDao {

    /** Upsert, so re-arming a drifted alert replaces the row rather than adding one beside it. */
    @Upsert
    suspend fun upsert(alert: PassAlertEntity)

    @Query("SELECT * FROM pass_alerts ORDER BY aosMillis")
    fun observeAll(): Flow<List<PassAlertEntity>>

    @Query("SELECT * FROM pass_alerts ORDER BY aosMillis")
    suspend fun all(): List<PassAlertEntity>

    @Query("SELECT * FROM pass_alerts WHERE id = :id")
    suspend fun find(id: String): PassAlertEntity?

    @Query("DELETE FROM pass_alerts WHERE id = :id")
    suspend fun delete(id: String)

    /** Forgets reminders for passes that have already begun. */
    @Query("DELETE FROM pass_alerts WHERE aosMillis <= :nowMillis")
    suspend fun deletePassed(nowMillis: Long)

    @Query("SELECT COUNT(*) FROM pass_alerts")
    suspend fun count(): Int
}
