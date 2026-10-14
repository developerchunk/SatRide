package com.developerstring.satride.core.sensors

import org.koin.core.module.Module
import org.koin.dsl.module

public actual fun platformSensorsModule(): Module = module {
    single<PlatformAttitudeSource> { DesktopAttitudeSource() }
    single<PlatformLocationSource> { IpGeolocationSource() }
    // No vibration motor on a desktop, and nothing sensible to substitute for one.
    single<Haptics> { NoHaptics }
}
