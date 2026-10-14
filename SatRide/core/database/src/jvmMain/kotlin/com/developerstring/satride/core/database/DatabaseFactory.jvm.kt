package com.developerstring.satride.core.database

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * Desktop storage.
 *
 * The desktop app exists so shared code can be run and profiled quickly, not as a shipping
 * platform, but it still gets a real per-user location rather than the working directory — a
 * database that lands wherever the process happened to start is a database that vanishes.
 */
actual class DatabaseFactory {
    actual fun builder(): RoomDatabase.Builder<SatRideDatabase> {
        val directory = File(System.getProperty("user.home"), ".satride").apply { mkdirs() }
        return Room.databaseBuilder<SatRideDatabase>(
            name = File(directory, SatRideDatabase.FILE_NAME).absolutePath,
        )
    }
}
