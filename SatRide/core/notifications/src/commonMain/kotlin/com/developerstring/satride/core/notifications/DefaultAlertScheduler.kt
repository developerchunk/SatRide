package com.developerstring.satride.core.notifications

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything about a reminder that is not platform-specific.
 *
 * Store first, then arm. The order matters on both platforms: an alarm that is armed but not
 * stored is invisible to the UI and cannot be rescheduled after a reboot, whereas one that is
 * stored but failed to arm is a row that the next reconcile will notice and retry. Losing the
 * record is the unrecoverable half, so it is the half that happens first.
 */
class DefaultAlertScheduler(
    private val store: AlertStore,
    private val platform: PlatformAlertScheduler,
    private val clock: Clock,
) : AlertScheduler {

    private val logger = Logger.withTag("AlertScheduler")

    override val capability: AlertCapability get() = platform.capability

    override suspend fun schedule(alert: PassAlert): Result<Unit> {
        val now = clock.now()
        if (alert.aos <= now) {
            return Result.failure(IllegalArgumentException("That pass has already started."))
        }
        if (!platform.capability.supported) {
            return Result.failure(IllegalStateException("This device cannot deliver pass alerts."))
        }

        // A pass that starts sooner than the lead time still gets a reminder, just an immediate
        // one. Refusing would be technically correct and useless: someone tapping [Remind me] on
        // a pass eight minutes out wants to be told when it starts, not lectured about lead times.
        val armed = if (alert.fireAt > now) {
            alert
        } else {
            alert.copy(leadTime = (alert.aos - now - MINIMUM_LEAD).coerceAtLeast(Duration.ZERO))
        }

        store.put(armed)
        return platform.arm(armed)
            .onFailure {
                logger.w(it) { "could not arm ${armed.id}; the stored alert stays for the next reconcile" }
            }
    }

    override suspend fun cancel(alertId: String) {
        platform.disarm(alertId)
        store.remove(alertId)
    }

    override fun scheduled(): Flow<List<PassAlert>> = store.observeAll()

    /**
     * Re-arms everything in the store.
     *
     * Called after a reboot, which wipes every alarm the system was holding, and after the app is
     * reinstalled or its process is recreated on a platform that does not persist alarms.
     * Idempotent: arming an id that is already armed replaces it.
     */
    suspend fun rearmAll(): Int {
        val now = clock.now()
        store.removePassed(now)
        var armed = 0
        store.all().forEach { alert ->
            if (alert.aos <= now) return@forEach
            platform.arm(alert)
                .onSuccess { armed++ }
                .onFailure { logger.w(it) { "re-arming ${alert.id} failed" } }
        }
        logger.i { "re-armed $armed pass alerts" }
        return armed
    }

    private companion object {
        /**
         * Never fire *exactly* at AOS.
         *
         * A notification that arrives at the moment the satellite clears the horizon is already
         * too late to be acted on, so a pass inside the lead time is pulled forward to now rather
         * than pinned to its own start.
         */
        val MINIMUM_LEAD = 5.seconds
    }
}
