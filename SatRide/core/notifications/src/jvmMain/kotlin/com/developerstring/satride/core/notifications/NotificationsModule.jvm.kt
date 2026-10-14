package com.developerstring.satride.core.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Pass alerts on the desktop.
 *
 * Delivered as system notifications while the app is running — see [DesktopAlertScheduler] for
 * why "while running" is the honest scope, and why that is still worth having.
 */
actual fun platformNotificationsModule(): Module = module {
    single<PlatformAlertScheduler> {
        DesktopAlertScheduler(
            // Application lifetime, supervised: one timer failing must not cancel the rest.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock = get(),
            notifier = SystemNotifier(),
        )
    }
}
