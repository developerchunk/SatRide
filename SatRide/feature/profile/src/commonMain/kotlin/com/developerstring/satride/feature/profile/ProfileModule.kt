package com.developerstring.satride.feature.profile

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Koin definitions owned by this feature. Aggregated by composeApp's app module. */
val profileModule: Module = module {
    viewModel {
        ProfileViewModel(
            authRepository = get(),
            profileRepository = get(),
            locationProvider = get(),
        )
    }
}
