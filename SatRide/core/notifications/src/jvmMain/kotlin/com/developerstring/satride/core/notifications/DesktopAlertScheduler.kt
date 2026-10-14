package com.developerstring.satride.core.notifications

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Pass alerts as desktop notifications.
 *
 * A desktop has no `AlarmManager`: nothing will wake this process to deliver a reminder after it
 * has quit. So the scope is deliberately **while the app is open** — a timer per armed alert,
 * delivered through the platform's notification centre. That is narrower than a phone's promise
 * and still the right feature to have: someone planning an evening's passes on a laptop leaves
 * the window open in the corner, and a notification that slides in ten minutes before AOS is
 * exactly what they set the reminder for.
 *
 * The store, not this class, is what survives a relaunch. The desktop entry point calls
 * `rearmAll()` on start, which rebuilds every timer from it — the same thing Android's boot
 * receiver does after a reboot.
 *
 * Arming an id that is already armed replaces its timer, which is the idempotence the shared
 * scheduler relies on when a TLE refresh moves a pass by a few seconds.
 */
internal class DesktopAlertScheduler(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val notifier: DesktopNotifier,
) : PlatformAlertScheduler {

    private val logger = Logger.withTag("DesktopAlerts")
    private val timers = HashMap<String, Job>()

    /** Exact: a coroutine timer fires within milliseconds of the moment it was asked for. */
    override val capability: AlertCapability
        get() = AlertCapability(supported = notifier.isAvailable, exact = true)

    override suspend fun arm(alert: PassAlert): Result<Unit> {
        if (!notifier.isAvailable) {
            return Result.failure(IllegalStateException("This desktop cannot show notifications."))
        }
        val wait = alert.fireAt - clock.now()
        synchronized(timers) {
            timers.remove(alert.id)?.cancel()
            timers[alert.id] = scope.launch {
                if (wait > Duration.ZERO) delay(wait)
                runCatching { notifier.notify(alert.title, alert.body) }
                    .onFailure { logger.w(it) { "could not show the reminder for ${alert.id}" } }
                synchronized(timers) { timers.remove(alert.id) }
            }
        }
        return Result.success(Unit)
    }

    override suspend fun disarm(alertId: String) {
        synchronized(timers) { timers.remove(alertId)?.cancel() }
    }

    /** How many timers are currently pending. For tests and diagnostics. */
    internal val armedCount: Int get() = synchronized(timers) { timers.size }
}
