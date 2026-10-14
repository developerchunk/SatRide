package com.developerstring.satride.core.database

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.developerstring.satride.core.common.AppDispatchers

/**
 * Supplies the platform-specific half of opening the database: where the file lives.
 *
 * That path is the only thing that differs — the driver, the dispatcher and every setting are
 * configured once in [openDatabase].
 */
expect class DatabaseFactory {
    fun builder(): RoomDatabase.Builder<SatRideDatabase>
}

/**
 * Opens the database.
 *
 * Uses the bundled SQLite build on every platform rather than each system's own. Android ships a
 * different SQLite version per OS release and iOS a different one again; pinning one build means
 * a query that works in a test works on a five-year-old phone, and that the `ON CONFLICT` and
 * `COLLATE NOCASE` behaviour the DAOs rely on is the same everywhere.
 */
fun openDatabase(factory: DatabaseFactory, dispatchers: AppDispatchers): SatRideDatabase = factory.builder()
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(dispatchers.io)
    // Hand-written and checked in — see Migrations.kt. Nothing here falls back to destroying the
    // database: a failed migration should be a loud crash in testing, not silent data loss on a
    // user's phone.
    .addMigrations(*SATRIDE_MIGRATIONS)
    .build()
