package com.developerstring.satride.core.notifications

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Reminders for upcoming passes.
 *
 * The contract the rest of the app writes against: features ask for an alert and observe what is
 * scheduled, and never learn whether the platform underneath is `AlarmManager`, a calendar
 * trigger, or nothing at all.
 */
interface AlertScheduler {
    /** Arms a reminder for `alert.aos - alert.leadTime`, replacing any alert with the same id. */
    suspend fun schedule(alert: PassAlert): Result<Unit>

    suspend fun cancel(alertId: String)

    fun scheduled(): Flow<List<PassAlert>>

    /**
     * Whether this build can deliver reminders at all.
     *
     * False on desktop, and false on a phone where the user declined notifications. The UI reads
     * it to explain why the button is off rather than to hide the feature — a disabled control
     * with a reason teaches; a missing one just looks like the app forgot.
     */
    val capability: AlertCapability
}

/**
 * What the platform will actually do with a reminder.
 *
 * [exact] is not a detail: Android since 12 refuses exact alarms to apps that have not been
 * granted them, and an inexact alarm can slide by several minutes. A ten-minute warning that
 * arrives four minutes late is still useful, and saying so is better than either lying about the
 * precision or refusing to schedule.
 */
data class AlertCapability(
    val supported: Boolean,
    val exact: Boolean = false,
    /** Set when the platform could support alerts but the user has not allowed them yet. */
    val needsPermission: Boolean = false,
)

/**
 * Where alerts are remembered between launches.
 *
 * Separate from the scheduling itself because the two fail independently: a phone reboot loses
 * every armed alarm but keeps the store, which is exactly what makes rescheduling on boot
 * possible.
 */
interface AlertStore {
    fun observeAll(): Flow<List<PassAlert>>

    suspend fun all(): List<PassAlert>

    suspend fun put(alert: PassAlert)

    suspend fun remove(alertId: String)

    /** Drops alerts whose pass has already happened. Called on boot and after every reconcile. */
    suspend fun removePassed(now: Instant)
}

/**
 * The platform half: arming and disarming one notification.
 *
 * Deliberately dumb. It knows nothing about passes, storage or invalidation — the shared
 * [DefaultAlertScheduler] owns all of that, which is what keeps the two platform implementations
 * small enough to be verified by reading them.
 */
interface PlatformAlertScheduler {
    val capability: AlertCapability

    suspend fun arm(alert: PassAlert): Result<Unit>

    suspend fun disarm(alertId: String)
}

/** Desktop, and any target with no notification system worth the name. */
class UnsupportedPlatformAlertScheduler : PlatformAlertScheduler {
    override val capability: AlertCapability = AlertCapability(supported = false)

    override suspend fun arm(alert: PassAlert): Result<Unit> =
        Result.failure(IllegalStateException("This platform cannot deliver pass alerts."))

    override suspend fun disarm(alertId: String) = Unit
}
