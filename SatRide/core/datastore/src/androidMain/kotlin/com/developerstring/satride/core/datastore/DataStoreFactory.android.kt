package com.developerstring.satride.core.datastore

import android.content.Context
import org.koin.core.module.Module
import org.koin.dsl.module

actual class PreferencesPathProvider(private val context: Context) {
    actual fun path(): String = context.filesDir.resolve(PREFERENCES_FILE_NAME).absolutePath
}

actual fun platformDataStoreModule(): Module = module {
    single { PreferencesPathProvider(get<Context>()) }
}
