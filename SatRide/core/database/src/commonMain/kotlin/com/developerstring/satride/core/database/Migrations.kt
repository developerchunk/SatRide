package com.developerstring.satride.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/*
 * Schema history.
 *
 * Every migration is written by hand against the exported schema in `core/database/schemas`, and
 * none of them is allowed to drop a table. The catalog and the pass cache are both re-derivable —
 * one from the bundled snapshot, one from the propagator — but the user's own data is not: an
 * imported element set for a launch that no public catalog carries yet exists nowhere else, and a
 * destructive migration would take it with no warning and no way back.
 */

/**
 * v1 → v2: pass alerts (P6).
 *
 * Purely additive. The statements are copied from the schema Room exports for v2, verbatim,
 * because Room verifies the resulting schema against that file's identity hash when it opens the
 * database — a column declared here in a different order or with different nullability fails on
 * the first launch after the update, on every device at once.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pass_alerts` (
                `id` TEXT NOT NULL,
                `noradId` INTEGER NOT NULL,
                `satelliteName` TEXT NOT NULL,
                `aosMillis` INTEGER NOT NULL,
                `maxElevationDeg` REAL NOT NULL,
                `aosAzimuthDeg` REAL NOT NULL,
                `leadTimeSeconds` INTEGER NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pass_alerts_aosMillis` ON `pass_alerts` (`aosMillis`)",
        )
    }
}

/**
 * v2 → v3: the user's tracking selection.
 *
 * Additive, and defaulted to 0 rather than to the curated flag. An upgrade therefore lands on an
 * empty custom selection, which is not a regression: the selection only takes effect in
 * `TrackingMode.CUSTOM`, and an upgrading install stays in `CURATED` until the user opens the
 * tracking screen and chooses otherwise.
 *
 * The two indices are what make the manage screen viable over a full catalog: without them,
 * "everything tracked" and "everything in this class" are table scans over tens of thousands of
 * rows, run on every keystroke in the search field.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `satellites` ADD COLUMN `tracked` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_satellites_tracked` ON `satellites` (`tracked`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_satellites_classType` ON `satellites` (`classType`)")
    }
}

/** Every migration, in order. Passed to the builder in [openDatabase]. */
val SATRIDE_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
