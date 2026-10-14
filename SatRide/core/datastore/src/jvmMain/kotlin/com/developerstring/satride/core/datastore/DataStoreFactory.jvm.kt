package com.developerstring.satride.core.datastore

import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

actual class PreferencesPathProvider {
    actual fun path(): String {
        val directory = File(System.getProperty("user.home"), ".satride").apply { mkdirs() }
        return File(directory, PREFERENCES_FILE_NAME).absolutePath
    }
}

actual fun platformDataStoreModule(): Module = module {
    single { PreferencesPathProvider() }
}
