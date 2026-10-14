package com.developerstring.satride.core.sensors

import android.content.Context
import org.koin.core.module.Module
import org.koin.dsl.module

public actual fun platformSensorsModule(): Module = module {
    single<PlatformAttitudeSource> { AndroidAttitudeSource(get<Context>()) }
    single<PlatformLocationSource> { AndroidLocationSource(get<Context>()) }
    single<Haptics> { AndroidHaptics(get<Context>()) }
}
