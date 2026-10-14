package com.developerstring.satride.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.developerstring.satride.core.database.entity.TransmitterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransmitterDao {

    @Upsert
    suspend fun upsert(transmitters: List<TransmitterEntity>)

    /**
     * Ordered by frequency so a satellite's downlinks read as a band plan rather than in
     * whatever order the API returned them.
     */
    @Query("SELECT * FROM transmitters WHERE noradId = :noradId ORDER BY downlinkLowHz")
    fun observeForSatellite(noradId: Int): Flow<List<TransmitterEntity>>

    @Query("SELECT * FROM transmitters WHERE noradId = :noradId ORDER BY downlinkLowHz")
    suspend fun forSatellite(noradId: Int): List<TransmitterEntity>

    @Query("SELECT * FROM transmitters ORDER BY noradId, downlinkLowHz")
    fun observeAll(): Flow<List<TransmitterEntity>>

    @Query("SELECT DISTINCT noradId FROM transmitters")
    suspend fun satelliteIdsWithTransmitters(): List<Int>

    @Query("SELECT * FROM transmitters WHERE mode IN (:modes) ORDER BY noradId, downlinkLowHz")
    fun observeByModes(modes: List<String>): Flow<List<TransmitterEntity>>

    /**
     * Replaces the catalog wholesale.
     *
     * A refresh has to delete first: transmitters get retired upstream, and an upsert-only
     * refresh would leave the user tuning a downlink SatNOGS has since marked dead. Callers run
     * this inside the same transaction as the insert.
     */
    @Query("DELETE FROM transmitters")
    suspend fun deleteAll()

    /**
     * Swaps in a freshly fetched catalog atomically.
     *
     * Delete-then-insert outside a transaction would leave a window in which the user has no
     * transmitters at all, and a failure partway through would leave them with none permanently.
     */
    @Transaction
    suspend fun replaceAll(transmitters: List<TransmitterEntity>) {
        deleteAll()
        upsert(transmitters)
    }

    @Query("SELECT COUNT(*) FROM transmitters")
    suspend fun count(): Int
}
