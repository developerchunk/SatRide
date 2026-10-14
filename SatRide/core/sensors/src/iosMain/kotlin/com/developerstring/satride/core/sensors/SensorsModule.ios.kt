package com.developerstring.satride.core.sensors

import org.koin.core.module.Module
import org.koin.dsl.module

public actual fun platformSensorsModule(): Module = module {
    single<PlatformAttitudeSource> { IosAttitudeSource() }
    single<PlatformLocationSource> { IosLocationSource() }
    single<Haptics> { IosHaptics() }
}
