package com.developerstring.satride.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

actual class DatabaseFactory(private val context: Context) {
    actual fun builder(): RoomDatabase.Builder<SatRideDatabase> {
        // getDatabasePath rather than filesDir: it is the directory Android's own backup and
        // clear-data handling knows about, so the database is treated as a database.
        val file = context.getDatabasePath(SatRideDatabase.FILE_NAME)
        file.parentFile?.mkdirs()
        return Room.databaseBuilder<SatRideDatabase>(context, file.absolutePath)
    }
}
