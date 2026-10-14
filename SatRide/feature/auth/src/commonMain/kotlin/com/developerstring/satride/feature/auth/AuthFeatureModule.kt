package com.developerstring.satride.feature.auth

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Koin definitions owned by this feature. Aggregated by composeApp's app module. */
val authFeatureModule: Module = module {
    // Written out rather than `viewModelOf`, for the reason P6 learned the hard way: the
    // constructor DSL resolves every parameter from the graph, including defaulted ones.
    viewModel { SignInViewModel(authRepository = get(), profileRepository = get()) }
    viewModel { CreateProfileViewModel(authRepository = get(), profileRepository = get()) }
    viewModel { TesterSignInViewModel(auth = get()) }
}
