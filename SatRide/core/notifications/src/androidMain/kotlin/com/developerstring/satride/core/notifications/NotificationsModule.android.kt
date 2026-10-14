package com.developerstring.satride.core.notifications

import android.content.Context
import org.koin.core.module.Module
import org.koin.dsl.module

// The Context comes from `androidContext(...)` in SatRideApplication, so this module does not have
// to depend on koin-android itself — the same arrangement core:database uses.
actual fun platformNotificationsModule(): Module = module {
    single<PlatformAlertScheduler> { AndroidAlertScheduler(get<Context>()) }
}
