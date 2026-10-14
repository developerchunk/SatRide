package com.developerstring.satride.core.notifications

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.SkyDeepLink
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * Pass alerts as iOS local notifications.
 *
 * A calendar trigger rather than a time-interval one. An interval trigger is relative to the
 * moment it is created, so an alert armed and then re-armed after a TLE refresh would drift by
 * however long the app took to notice; a calendar trigger names the wall-clock moment, which is
 * what a pass actually is.
 *
 * iOS delivers local notifications at the requested minute without any equivalent of Android's
 * exact-alarm permission, so [AlertCapability.exact] is always true here.
 */
internal class IosAlertScheduler : PlatformAlertScheduler {

    private val logger = Logger.withTag("IosAlerts")
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    /**
     * Cached because the real answer arrives asynchronously and this is read from composition.
     *
     * Seeded optimistically as "supported, needs permission" and corrected the first time
     * [refreshAuthorization] runs, which the passes screen does when it opens.
     */
    private var authorized: Boolean = false
    private var undetermined: Boolean = true

    override val capability: AlertCapability
        get() = AlertCapability(
            supported = true,
            exact = true,
            needsPermission = !authorized,
        )

    /** Asks iOS, if it has not been asked before. Safe to call repeatedly. */
    suspend fun requestAuthorization(): Boolean {
        refreshAuthorization()
        if (authorized) return true
        if (!undetermined) return false

        val granted = suspendCancellableCoroutine { continuation ->
            center.requestAuthorizationWithOptions(
                UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
            ) { granted, error ->
                if (error != null) logger.w { "notification authorisation failed: ${error.localizedDescription}" }
                continuation.resume(granted)
            }
        }
        authorized = granted
        undetermined = false
        return granted
    }

    suspend fun refreshAuthorization() {
        val status = suspendCancellableCoroutine { continuation ->
            center.getNotificationSettingsWithCompletionHandler { settings ->
                continuation.resume(settings?.authorizationStatus)
            }
        }
        // Provisional counts as authorised: iOS delivers those quietly to the notification centre,
        // which for a pass reminder is a degraded but genuine delivery.
        authorized = status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional
        undetermined = status == UNAuthorizationStatusNotDetermined
    }

    override suspend fun arm(alert: PassAlert): Result<Unit> {
        if (!requestAuthorization()) {
            return Result.failure(IllegalStateException("Notifications are turned off for SatRide."))
        }

        val content = UNMutableNotificationContent().apply {
            setTitle(alert.title)
            setBody(alert.body)
            setSound(UNNotificationSound.defaultSound())
            // Read by `onOpenURL` on the Swift side, so a tap lands on the same guided sky view
            // an Android tap does.
            setUserInfo(mapOf(USER_INFO_LINK to SkyDeepLink.guide(alert.noradId)))
        }

        val fireDate = NSDate.dateWithTimeIntervalSince1970(alert.fireAt.toEpochMilliseconds() / 1000.0)
        val components = NSCalendar.currentCalendar.components(
            NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
                NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond,
            fromDate = fireDate,
        )

        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = alert.id,
            content = content,
            trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = false),
        )

        return suspendCancellableCoroutine { continuation ->
            // Adding a request with an identifier that is already pending replaces it, which is
            // exactly the semantics a drift-driven re-arm needs.
            center.addNotificationRequest(request) { error ->
                continuation.resume(
                    if (error == null) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IllegalStateException(error.localizedDescription))
                    },
                )
            }
        }
    }

    override suspend fun disarm(alertId: String) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(alertId))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(alertId))
    }

    companion object {
        const val USER_INFO_LINK: String = "satrideLink"
    }
}
