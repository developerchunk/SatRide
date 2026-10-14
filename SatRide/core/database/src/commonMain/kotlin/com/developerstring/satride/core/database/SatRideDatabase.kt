package com.developerstring.satride.core.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.developerstring.satride.core.database.dao.ArtifactDao
import com.developerstring.satride.core.database.dao.ObservationDao
import com.developerstring.satride.core.database.dao.PassAlertDao
import com.developerstring.satride.core.database.dao.PassCacheDao
import com.developerstring.satride.core.database.dao.SatelliteDao
import com.developerstring.satride.core.database.dao.TleDao
import com.developerstring.satride.core.database.dao.TransmitterDao
import com.developerstring.satride.core.database.entity.ArtifactEntity
import com.developerstring.satride.core.database.entity.ObservationEntity
import com.developerstring.satride.core.database.entity.PassAlertEntity
import com.developerstring.satride.core.database.entity.PassCacheEntity
import com.developerstring.satride.core.database.entity.SatelliteEntity
import com.developerstring.satride.core.database.entity.TleEntity
import com.developerstring.satride.core.database.entity.TransmitterEntity

/**
 * SatRide's local store.
 *
 * Everything the app computes — positions, passes, Doppler — is derived from these tables, so the
 * app works fully offline once they are populated. That is the whole architectural posture:
 * fetch rarely, compute locally, never depend on a network round trip to draw a frame.
 *
 * Schemas are exported to `core/database/schemas` and checked in, so a migration can be written
 * and verified against the previous version rather than guessed at.
 */
@Database(
    entities = [
        SatelliteEntity::class,
        TleEntity::class,
        TransmitterEntity::class,
        PassCacheEntity::class,
        PassAlertEntity::class,
        ArtifactEntity::class,
        ObservationEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@ConstructedBy(SatRideDatabaseConstructor::class)
abstract class SatRideDatabase : RoomDatabase() {
    abstract fun satelliteDao(): SatelliteDao
    abstract fun tleDao(): TleDao
    abstract fun transmitterDao(): TransmitterDao
    abstract fun passCacheDao(): PassCacheDao
    abstract fun passAlertDao(): PassAlertDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun observationDao(): ObservationDao

    companion object {
        const val FILE_NAME: String = "satride.db"
    }
}

/**
 * Generated per platform by Room's KSP processor. The `expect` declaration has no body by
 * design — the compiler plugin supplies each `actual`.
 */
@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object SatRideDatabaseConstructor : RoomDatabaseConstructor<SatRideDatabase> {
    override fun initialize(): SatRideDatabase
}
