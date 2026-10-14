package com.developerstring.satride.core.database

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Provides [DatabaseFactory], whose constructor differs per platform — Android needs a `Context`,
 * the others resolve a path themselves. This is the only reason the module is split.
 */
expect fun platformDatabaseModule(): Module

/** The database and every DAO, as singletons. */
val databaseModule: Module =
    module {
        includes(platformDatabaseModule())

        single { openDatabase(get(), get()) }
        single { get<SatRideDatabase>().satelliteDao() }
        single { get<SatRideDatabase>().tleDao() }
        single { get<SatRideDatabase>().transmitterDao() }
        single { get<SatRideDatabase>().passCacheDao() }
        single { get<SatRideDatabase>().passAlertDao() }
        single { get<SatRideDatabase>().artifactDao() }
        single { get<SatRideDatabase>().observationDao() }
    }
