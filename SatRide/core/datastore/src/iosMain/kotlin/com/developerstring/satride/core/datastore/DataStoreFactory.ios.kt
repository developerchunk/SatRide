package com.developerstring.satride.core.datastore

import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

actual class PreferencesPathProvider {
    @OptIn(ExperimentalForeignApi::class)
    actual fun path(): String {
        val documents: NSURL = requireNotNull(
            NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = false,
                error = null,
            ),
        ) { "Could not resolve the iOS documents directory" }
        return requireNotNull(documents.path) { "Documents directory has no path" } +
            "/$PREFERENCES_FILE_NAME"
    }
}

actual fun platformDataStoreModule(): Module = module {
    single { PreferencesPathProvider() }
}
