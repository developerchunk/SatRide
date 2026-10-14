package com.developerstring.satride.feature.satellites

import com.developerstring.satride.feature.satellites.detail.SatelliteDetailViewModel
import com.developerstring.satride.feature.satellites.import.ImportAllowance
import com.developerstring.satride.feature.satellites.import.SettingsImportAllowance
import com.developerstring.satride.feature.satellites.import.TleImportViewModel
import com.developerstring.satride.feature.satellites.tracking.TrackingViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Koin definitions owned by this feature. Aggregated by composeApp's app module. */
val satellitesModule: Module = module {
    viewModelOf(::SatellitesViewModel)
    viewModelOf(::TrackingViewModel)
    single<ImportAllowance> { SettingsImportAllowance(get()) }
    viewModelOf(::TleImportViewModel)

    // The catalog number comes from the route, so it is a runtime parameter rather than a
    // dependency — which is what keeps the view model constructible without a factory of our own.
    viewModel { parameters ->
        SatelliteDetailViewModel(
            noradId = parameters.get(),
            satelliteRepository = get(),
            transmitterRepository = get(),
            passRepository = get(),
            tleRepository = get(),
            trackingEngine = get(),
            radioLauncher = get(),
            issEventsApi = get(),
            clock = get(),
            dispatchers = get(),
        )
    }
}
