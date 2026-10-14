package com.developerstring.satride.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.developerstring.satride.core.database.entity.PassAlertEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The v1 → v2 migration, run against a real v1 database.
 *
 * A migration is the one piece of this project that cannot be checked by reading it: Room compares
 * the schema the migration leaves behind against the identity hash it generated from the entity
 * classes, and a single column declared in the wrong order fails on the first launch after an
 * update, on every device at once, with the user's data behind it.
 *
 * So this builds a database that looks exactly like a v1 one — the create statements are copied
 * from the checked-in `schemas/1.json` — puts a row in it, opens it through the app's own
 * [openDatabase], and checks both that the new table works and that the old row survived.
 *
 * JVM-only because it needs a temp file on disk, not because the migration is. The SQL runs on the
 * same bundled SQLite build on every platform.
 */
class MigrationTest {

    private val directory: File = Files.createTempDirectory("satride-migration").toFile()
    private val databaseFile = File(directory, "migration-test.db")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun v1DatabasesGainThePassAlertsTableAndKeepTheirData() = runTest {
        createVersionOneDatabase()

        val database = Room.databaseBuilder<SatRideDatabase>(name = databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .addMigrations(*SATRIDE_MIGRATIONS)
            .build()

        try {
            // Room validates the migrated schema against v2's identity hash when the first query
            // runs. Reaching this line at all is most of what this test is for.
            val satellite = database.satelliteDao().find(25544)
            assertNotNull(satellite, "the v1 row must survive the migration")
            assertEquals("ISS (ZARYA)", satellite.name)

            val alerts = database.passAlertDao()
            assertEquals(0, alerts.count())
            alerts.upsert(
                PassAlertEntity(
                    id = "25544@1000",
                    noradId = 25544,
                    satelliteName = "ISS (ZARYA)",
                    aosMillis = 1_000,
                    maxElevationDeg = 62.0,
                    aosAzimuthDeg = 271.0,
                    leadTimeSeconds = 600,
                    createdAtMillis = 500,
                ),
            )
            assertEquals(1, alerts.count())
            assertEquals("ISS (ZARYA)", alerts.find("25544@1000")?.satelliteName)
        } finally {
            database.close()
        }
    }

    /**
     * A database as v1 left it.
     *
     * Statements copied verbatim from `schemas/com.developerstring.satride.core.database.SatRideDatabase/1.json`,
     * including `room_master_table` — Room reads that row to decide whether the schema it found is
     * the one it expects, so a database without it is not a v1 database as far as Room is
     * concerned.
     */
    private fun createVersionOneDatabase() {
        val connection = BundledSQLiteDriver().open(databaseFile.absolutePath)
        try {
            V1_STATEMENTS.forEach(connection::execSQL)
            connection.execSQL("PRAGMA user_version = 1")
            connection.execSQL(
                "INSERT INTO satellites VALUES (25544, 'ISS (ZARYA)', 'CREWED', NULL, NULL, '98067A', 1)",
            )
        } finally {
            connection.close()
        }
    }

    private companion object {
        const val V1_IDENTITY_HASH = "03393ab148981e6040d2088183f2ca02"

        val V1_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `satellites` (`noradId` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "`classType` TEXT NOT NULL, `launchDate` TEXT, `countryCode` TEXT, " +
                "`internationalDesignator` TEXT, `curated` INTEGER NOT NULL, PRIMARY KEY(`noradId`))",
            "CREATE TABLE IF NOT EXISTS `tles` (`noradId` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "`line1` TEXT NOT NULL, `line2` TEXT NOT NULL, `epochMillis` INTEGER NOT NULL, " +
                "`fetchedAtMillis` INTEGER NOT NULL, `sourceId` TEXT NOT NULL, PRIMARY KEY(`noradId`))",
            "CREATE INDEX IF NOT EXISTS `index_tles_epochMillis` ON `tles` (`epochMillis`)",
            "CREATE TABLE IF NOT EXISTS `transmitters` (`id` TEXT NOT NULL, `noradId` INTEGER NOT NULL, " +
                "`description` TEXT NOT NULL, `downlinkLowHz` INTEGER NOT NULL, `downlinkHighHz` INTEGER, " +
                "`mode` TEXT NOT NULL, `baud` INTEGER, `status` TEXT NOT NULL, `invert` INTEGER NOT NULL, " +
                "`service` TEXT, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_transmitters_noradId` ON `transmitters` (`noradId`)",
            "CREATE TABLE IF NOT EXISTS `pass_cache` (`noradId` INTEGER NOT NULL, " +
                "`observerGeohash` TEXT NOT NULL, `tleEpochMillis` INTEGER NOT NULL, " +
                "`aosMillis` INTEGER NOT NULL, `tcaMillis` INTEGER NOT NULL, `losMillis` INTEGER NOT NULL, " +
                "`maxElevationDeg` REAL NOT NULL, `aosAzimuthDeg` REAL NOT NULL, " +
                "`losAzimuthDeg` REAL NOT NULL, `arcJson` TEXT NOT NULL, `computedAtMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`noradId`, `observerGeohash`, `tleEpochMillis`, `aosMillis`))",
            "CREATE INDEX IF NOT EXISTS `index_pass_cache_aosMillis` ON `pass_cache` (`aosMillis`)",
            "CREATE INDEX IF NOT EXISTS `index_pass_cache_observerGeohash_tleEpochMillis` " +
                "ON `pass_cache` (`observerGeohash`, `tleEpochMillis`)",
            "CREATE TABLE IF NOT EXISTS `artifacts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`noradId` INTEGER NOT NULL, `satelliteName` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`filePath` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, `frequencyHz` INTEGER, " +
                "`mode` TEXT, `maxElevationDeg` REAL, `notes` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_artifacts_noradId` ON `artifacts` (`noradId`)",
            "CREATE INDEX IF NOT EXISTS `index_artifacts_createdAtMillis` ON `artifacts` (`createdAtMillis`)",
            "CREATE TABLE IF NOT EXISTS `observations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`noradId` INTEGER NOT NULL, `satelliteName` TEXT NOT NULL, " +
                "`startedAtMillis` INTEGER NOT NULL, `endedAtMillis` INTEGER, `frequencyHz` INTEGER NOT NULL, " +
                "`mode` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, `maxElevationDeg` REAL, " +
                "`artifactCount` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_observations_startedAtMillis` ON `observations` " +
                "(`startedAtMillis`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '$V1_IDENTITY_HASH')",
        )
    }
}
