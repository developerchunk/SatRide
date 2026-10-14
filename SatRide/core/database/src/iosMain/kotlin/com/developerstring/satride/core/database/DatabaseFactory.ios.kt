package com.developerstring.satride.core.database

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

actual class DatabaseFactory {
    @OptIn(ExperimentalForeignApi::class)
    actual fun builder(): RoomDatabase.Builder<SatRideDatabase> {
        val documents: NSURL = requireNotNull(
            NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = false,
                error = null,
            ),
        ) { "Could not resolve the iOS documents directory" }

        return Room.databaseBuilder<SatRideDatabase>(
            name = requireNotNull(documents.path) { "Documents directory has no path" } +
                "/${SatRideDatabase.FILE_NAME}",
        )
    }
}
