package com.developerstring.satride.core.notifications

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Provides [PlatformAlertScheduler], which is the only part of alerting that differs per platform.
 *
 * Android needs a `Context` and iOS needs nothing; desktop has no notification system to speak of
 * and binds the unsupported implementation, which is why the desktop build compiles this module
 * at all rather than excluding it.
 */
expect fun platformNotificationsModule(): Module

/**
 * Alert scheduling.
 *
 * An [AlertStore] must be bound by the caller: alerts are persisted in the app's database, and
 * this module has no business knowing about Room. `core:data` binds it.
 */
val notificationsModule: Module =
    module {
        includes(platformNotificationsModule())

        single { DefaultAlertScheduler(store = get(), platform = get(), clock = get()) }
        single<AlertScheduler> { get<DefaultAlertScheduler>() }
    }
