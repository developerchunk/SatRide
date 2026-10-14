package com.developerstring.satride.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module

/** Where the preferences file lives — the only part of this that differs per platform. */
expect class PreferencesPathProvider {
    fun path(): String
}

internal const val PREFERENCES_FILE_NAME: String = "satride.preferences_pb"

fun createSettingsDataStore(pathProvider: PreferencesPathProvider): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(produceFile = { pathProvider.path().toPath() })

expect fun platformDataStoreModule(): Module

val dataStoreModule: Module =
    module {
        includes(platformDataStoreModule())

        single { createSettingsDataStore(get()) }
        single { SettingsRepository(get()) }
    }
