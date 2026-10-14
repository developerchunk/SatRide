package com.developerstring.satride.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * A throwaway database for tests.
 *
 * In-memory rather than a temp file, and built with the same [BundledSQLiteDriver] the app ships
 * with — so a query verified here is verified against the exact SQLite build that will run it on
 * a device, not against whatever version the host happens to have.
 */
internal fun inMemoryTestDatabase(): SatRideDatabase = Room.inMemoryDatabaseBuilder<SatRideDatabase>()
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.Default)
    .build()
