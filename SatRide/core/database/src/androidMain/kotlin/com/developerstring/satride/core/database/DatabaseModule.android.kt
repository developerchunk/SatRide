package com.developerstring.satride.core.database

import android.content.Context
import org.koin.core.module.Module
import org.koin.dsl.module

// The Context comes from `androidContext(...)` in SatRideApplication, so this resolves without
// core:database having to depend on koin-android itself.
actual fun platformDatabaseModule(): Module = module {
    single { DatabaseFactory(get<Context>()) }
}
