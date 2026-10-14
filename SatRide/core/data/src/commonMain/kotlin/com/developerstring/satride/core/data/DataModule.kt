package com.developerstring.satride.core.data

import com.developerstring.satride.core.common.AppScopeQualifier
import com.developerstring.satride.core.common.RadioLauncher
import com.developerstring.satride.core.common.UnavailableRadioLauncher
import com.developerstring.satride.core.common.commonModule
import com.developerstring.satride.core.database.databaseModule
import com.developerstring.satride.core.datastore.dataStoreModule
import com.developerstring.satride.core.network.networkModule
import com.developerstring.satride.core.notifications.AlertStore
import com.developerstring.satride.core.notifications.notificationsModule
import com.developerstring.satride.core.sensors.SensorScopeQualifier
import com.developerstring.satride.core.sensors.sensorsModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The whole data layer as one Koin module.
 *
 * It pulls in networking, persistence and preferences itself, so the app shell lists this once
 * rather than knowing which storage decisions were made underneath — which is the point of having
 * a data layer at all.
 *
 * A [CatalogSnapshotSource] must be bound by the caller: reading a bundled asset is a
 * platform concern, and it differs enough between targets that nothing down here should pretend
 * otherwise.
 */
fun dataModule(enableNetworkLogging: Boolean = false): Module = module {
    includes(
        commonModule,
        networkModule(enableLogging = enableNetworkLogging),
        databaseModule,
        dataStoreModule,
        sensorsModule,
        notificationsModule,
    )

    // core:notifications declares the port and leaves the storage decision here, which is the same
    // arrangement the repositories use — nothing below this module knows Room exists.
    single<AlertStore> { RoomAlertStore(dao = get(), dispatchers = get()) }

    single {
        PassAlertCoordinator(
            scheduler = get(),
            store = get(),
            passRepository = get(),
            tleRepository = get(),
            observerPositions = get(),
            clock = get(),
        )
    }

    // Replaced in P9 by the implementation that actually starts a session. Bound here so every
    // [Tune] button is written against its final shape today.
    single<RadioLauncher> { UnavailableRadioLauncher() }

    // Eager, unlike everything else here. This object's job is a side effect — pushing the
    // user's saved position into the location provider — so nothing necessarily resolves it.
    // Left lazy, a manually entered position reached the location provider only if some screen
    // happened to ask for this binding first, and the tracking engine reported "no position"
    // while the pass repository was happily computing passes from the same setting.
    single<ObserverPositionProvider>(createdAtStart = true) {
        SettingsBackedObserverPositionProvider(
            locationProvider = get(),
            settings = get(),
            scope = get(SensorScopeQualifier),
        )
    }

    single<SatelliteRepository> {
        DefaultSatelliteRepository(
            satelliteDao = get(),
            tleDao = get(),
            dispatchers = get(),
            clock = get(),
        )
    }

    single<TleRepository> {
        DefaultTleRepository(
            tleApi = get(),
            tleDao = get(),
            satelliteDao = get(),
            settings = get(),
            dispatchers = get(),
            clock = get(),
        )
    }

    single<TransmitterRepository> {
        DefaultTransmitterRepository(
            satNogsApi = get(),
            transmitterDao = get(),
            dispatchers = get(),
        )
    }

    single {
        DefaultPassRepository(
            passCacheDao = get(),
            satelliteDao = get(),
            tleRepository = get(),
            selectionRepository = get(),
            observerPositions = get(),
            settings = get(),
            dispatchers = get(),
            clock = get(),
        )
    }
    single<PassRepository> { get<DefaultPassRepository>() }

    single {
        CatalogSeeder(
            source = get(),
            satelliteDao = get(),
            tleDao = get(),
            transmitterDao = get(),
            dispatchers = get(),
        )
    }

    single {
        CatalogSync(
            seeder = get(),
            tleRepository = get(),
            transmitterRepository = get(),
            passRepository = get(),
            alertCoordinator = get(),
            settings = get(),
            dispatchers = get(),
        )
    }

    single {
        PassPrecomputeCoordinator(
            observerPositions = get(),
            passRepository = get(),
            selectionRepository = get(),
            alertCoordinator = get(),
            dispatchers = get(),
        )
    }

    // Application-scoped: a refresh must survive the user leaving the screen that started it.
    single<CatalogRefresher> { get<CatalogRefreshController>() }

    single {
        CatalogRefreshController(
            tleRepository = get(),
            transmitterRepository = get(),
            settings = get(),
            dispatchers = get(),
            scope = get(AppScopeQualifier),
        )
    }

    single<TrackingSelectionRepository> {
        DefaultTrackingSelectionRepository(
            satelliteDao = get(),
            settings = get(),
            dispatchers = get(),
        )
    }

    single<SatelliteTracker> { get<TrackingEngine>() }

    single {
        TrackingEngine(
            tleRepository = get(),
            selectionRepository = get(),
            locationProvider = get(),
            dispatchers = get(),
            clock = get(),
            scope = get(SensorScopeQualifier),
        )
    }
}
