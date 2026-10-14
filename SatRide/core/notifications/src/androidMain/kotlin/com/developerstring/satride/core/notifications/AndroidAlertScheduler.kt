package com.developerstring.satride.core.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import co.touchlab.kermit.Logger

/**
 * Pass alerts as Android alarms.
 *
 * `AlarmManager` rather than `WorkManager`: a pass is a moment, not a piece of work. WorkManager
 * deliberately batches and defers, which is right for a sync and wrong for "the ISS clears your
 * horizon in ten minutes".
 *
 * ## Exactness
 *
 * Since Android 12 an exact alarm needs `SCHEDULE_EXACT_ALARM`, which the user can revoke. The
 * app asks for it and uses it when it has it, and falls back to `setAndAllowWhileIdle` when it
 * does not — an inexact alarm can slide by several minutes, which is degraded but still useful
 * against a ten-minute lead. What the app does *not* do is claim `USE_EXACT_ALARM`: that is
 * reserved for alarm-clock and calendar apps, and a satellite tracker claiming it is the kind of
 * thing that fails review. [capability] reports which of the two is in force so the UI can say so.
 */
internal class AndroidAlertScheduler(private val context: Context) : PlatformAlertScheduler {

    private val logger = Logger.withTag("AndroidAlerts")

    private val alarmManager: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    override val capability: AlertCapability
        get() {
            val manager = alarmManager ?: return AlertCapability(supported = false)
            val notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
            return AlertCapability(
                supported = true,
                exact = manager.canScheduleExact(),
                needsPermission = !notificationsAllowed,
            )
        }

    override suspend fun arm(alert: PassAlert): Result<Unit> {
        val manager = alarmManager
            ?: return Result.failure(IllegalStateException("No alarm service on this device."))

        ensureChannel()

        val triggerAtMillis = alert.fireAt.toEpochMilliseconds()
        val pending = pendingBroadcast(alert)

        return runCatching {
            if (manager.canScheduleExact()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                // Still `AllowWhileIdle`, so Doze cannot swallow it entirely — it only loses the
                // guarantee about the minute.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        }.onFailure {
            // `SecurityException` if the exact-alarm permission was revoked between the check and
            // the call, which is a real race on a device where the user is in Settings.
            logger.w(it) { "arming ${alert.id} failed" }
        }
    }

    override suspend fun disarm(alertId: String) {
        val manager = alarmManager ?: return
        // A cancel needs a PendingIntent that *matches* the armed one: same action, same data,
        // same request code. The extras are not part of the match, which is why the data URI
        // carries the id rather than only an extra.
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(alertId),
            Intent(context, PassAlertReceiver::class.java)
                .setAction(PassAlertReceiver.ACTION_FIRE)
                .setData(alertUri(alertId)),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        manager.cancel(pending)
        pending.cancel()
    }

    private fun pendingBroadcast(alert: PassAlert): PendingIntent {
        val intent = Intent(context, PassAlertReceiver::class.java)
            .setAction(PassAlertReceiver.ACTION_FIRE)
            .setData(alertUri(alert.id))
            .putExtra(PassAlertReceiver.EXTRA_ID, alert.id)
            .putExtra(PassAlertReceiver.EXTRA_NORAD_ID, alert.noradId)
            .putExtra(PassAlertReceiver.EXTRA_TITLE, alert.title)
            .putExtra(PassAlertReceiver.EXTRA_BODY, alert.body)

        return PendingIntent.getBroadcast(
            context,
            requestCode(alert.id),
            intent,
            // UPDATE_CURRENT so re-arming after a drift replaces the payload rather than
            // delivering the old one; IMMUTABLE because nothing outside the app should be able to
            // rewrite where this points.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pass alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders a few minutes before a satellite you follow comes over."
            },
        )
    }

    companion object {
        const val CHANNEL_ID: String = "pass-alerts"

        fun alertUri(alertId: String): Uri = Uri.parse("satride://alert/$alertId")

        /**
         * `AlarmManager` keys alarms by request code, which is an `Int`.
         *
         * A hash collision would mean one alert silently replacing another. The id is
         * `noradId@aosMillis`, so two live alerts colliding requires two different satellites and
         * times hashing equal — possible in principle, and the consequence is one missed reminder
         * rather than anything worse, which does not justify keeping a side table of int ids.
         */
        fun requestCode(alertId: String): Int = alertId.hashCode()
    }
}

/**
 * True when this build may schedule to the minute.
 *
 * Before Android 12 every app could, and the API to ask did not exist.
 */
private fun AlarmManager.canScheduleExact(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms()
