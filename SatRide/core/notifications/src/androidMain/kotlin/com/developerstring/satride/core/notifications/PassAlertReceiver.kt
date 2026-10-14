package com.developerstring.satride.core.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.SkyDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Turns a fired alarm into the notification the user sees.
 *
 * Everything it needs travels in the intent, so it does no I/O and touches no database. A
 * broadcast receiver has about ten seconds of main-thread budget and is woken on a device that
 * has been asleep — opening Room there to look up a row that was already known when the alarm was
 * armed would be the one part of this feature most likely to be killed mid-flight.
 */
class PassAlertReceiver : BroadcastReceiver() {

    private val logger = Logger.withTag("PassAlertReceiver")

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return

        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val noradId = intent.getIntExtra(EXTRA_NORAD_ID, -1)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Satellite pass"
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()

        if (!context.canPostNotifications()) {
            logger.w { "alarm fired for $id but notifications are not permitted" }
            return
        }

        val tap = PendingIntent.getActivity(
            context,
            AndroidAlertScheduler.requestCode(id),
            // The same deep link a user could type: the notification is not a special path into
            // the app, it is the ordinary one, which is what keeps cold start and warm start
            // behaving identically.
            Intent(Intent.ACTION_VIEW, Uri.parse(SkyDeepLink.guide(noradId)))
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, AndroidAlertScheduler.CHANNEL_ID)
            .setSmallIcon(context.notificationIcon())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(id.hashCode(), notification) }
            .onFailure { logger.w(it) { "posting the notification for $id failed" } }

        // The alert has served its purpose; forgetting it here keeps the store from filling with
        // reminders for passes that are already over.
        val scheduler = GlobalContext.getOrNull()?.getOrNull<DefaultAlertScheduler>()
        if (scheduler != null) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                runCatching { scheduler.cancel(id) }
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE: String = "com.developerstring.satride.PASS_ALERT"
        const val EXTRA_ID: String = "alertId"
        const val EXTRA_NORAD_ID: String = "noradId"
        const val EXTRA_TITLE: String = "title"
        const val EXTRA_BODY: String = "body"
    }
}

/**
 * Re-arms every stored alert after a reboot.
 *
 * Android drops every alarm on restart, without telling the app. Without this the feature works
 * perfectly until the user's phone runs out of battery, and then quietly never works again.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    private val logger = Logger.withTag("AlertBootReceiver")

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val scheduler = GlobalContext.getOrNull()?.getOrNull<DefaultAlertScheduler>()
        if (scheduler == null) {
            logger.w { "boot completed but DI is not running; alerts will re-arm on next launch" }
            return
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { scheduler.rearmAll() }
                .onFailure { logger.e(it) { "re-arming alerts after boot failed" } }
            pending.finish()
        }
    }
}

private fun Context.canPostNotifications(): Boolean {
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
    // POST_NOTIFICATIONS only exists from API 33; below it, being enabled is the whole check.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

/**
 * The status-bar icon.
 *
 * Looked up by name so `core:notifications` does not have to own Android resources — the app
 * module supplies `ic_notification`, and a build that has not got round to it falls back to the
 * launcher icon rather than crashing.
 */
private fun Context.notificationIcon(): Int {
    @Suppress("DiscouragedApi")
    val named = resources.getIdentifier("ic_notification", "drawable", packageName)
    return if (named != 0) named else applicationInfo.icon
}
