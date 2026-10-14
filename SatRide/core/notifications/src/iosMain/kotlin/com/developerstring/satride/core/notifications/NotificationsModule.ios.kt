package com.developerstring.satride.core.notifications

import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformNotificationsModule(): Module = module {
    single { IosAlertScheduler() }
    single<PlatformAlertScheduler> { get<IosAlertScheduler>() }
}
