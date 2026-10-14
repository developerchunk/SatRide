package com.developerstring.satride.feature.play

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Koin definitions owned by this feature. Aggregated by composeApp's app module, which also
 * supplies the [EarthTextureSource] implementation — the textures live in composeApp's
 * resources, and each platform reads them its own way.
 */
val playModule: Module = module {
    viewModelOf(::PlayViewModel)
}
