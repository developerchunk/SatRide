package com.developerstring.satride.feature.onboarding

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Koin definitions owned by this feature. Aggregated by composeApp's app module. */
val onboardingModule: Module = module {
    viewModel { OnboardingViewModel(settings = get()) }
    viewModel { PermissionsViewModel(settings = get()) }
}
