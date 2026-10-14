package com.developerstring.satride.feature.arsky

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Koin definitions owned by this feature. Aggregated by composeApp's app module. */
val arskyModule: Module = module {
    single { SkyFeatureFlags() }

    viewModel {
        SkyViewModel(
            satelliteRepository = get(),
            transmitterRepository = get(),
            tracker = get(),
            attitudeProvider = get(),
            haptics = get(),
            guideRequests = get(),
            clock = get(),
            dispatchers = get(),
            timeTravelEnabled = get<SkyFeatureFlags>().timeTravel,
        )
    }
}

/**
 * Feature flags for the sky view.
 *
 * [timeTravel] is P5's declared third cut line. It is off, and it is read in exactly one place, so
 * switching it on is a one-line change rather than a hunt through the screen — which is the whole
 * point of naming a cut line before the schedule needs it.
 */
data class SkyFeatureFlags(val timeTravel: Boolean = false)
