package com.developerstring.satride.feature.startup

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The splash's view model only.
 *
 * The [StartupCoordinator] itself is bound by the app shell, because only the shell can see every
 * feature — warming the globe means reading `feature:play`'s assets, and a feature module may not
 * depend on another feature. The shell assembles the step map; this module knows nothing about what
 * is in it.
 */
val startupModule: Module = module {
    viewModel { StartupViewModel(coordinator = get(), appStart = get()) }
}
