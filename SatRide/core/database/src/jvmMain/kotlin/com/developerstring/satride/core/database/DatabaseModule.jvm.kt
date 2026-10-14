package com.developerstring.satride.core.database

import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformDatabaseModule(): Module = module {
    single { DatabaseFactory() }
}
