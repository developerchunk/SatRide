package com.developerstring.satride.core.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

@Composable
actual fun rememberCameraPermission(): PermissionController {
    val context = LocalContext.current
    val controller = remember(context) {
        MutablePermission { context.cameraStatus() }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.settle(granted)
    }

    DisposableEffect(controller, launcher) {
        controller.launch = { launcher.launch(Manifest.permission.CAMERA) }
        onDispose { controller.launch = null }
    }

    return controller
}

@Composable
actual fun rememberLocationPermission(): PermissionController {
    val context = LocalContext.current
    val controller = remember(context) {
        MutablePermission { context.locationStatus() }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // Granted if *either* is allowed. Since Android 12 the user can downgrade a precise
        // request to approximate from within the dialog, and the result then reports fine denied
        // and coarse granted. Treating that as a refusal would push a user who agreed to share
        // their location into the manual-entry path.
        controller.settle(results.values.any { it })
    }

    DisposableEffect(controller, launcher) {
        controller.launch = {
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
        onDispose { controller.launch = null }
    }

    return controller
}

@Composable
actual fun rememberNotificationPermission(): PermissionController {
    val context = LocalContext.current
    val controller = remember(context) { MutablePermission { context.notificationStatus() } }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.settle(granted)
    }

    DisposableEffect(controller, launcher) {
        controller.launch = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Before Android 13 there is no runtime permission to ask for: whether the app may
                // post is decided entirely in system settings, so the current reading is the final
                // answer and settling on it is what stops a caller waiting forever.
                controller.reread()
            }
        }
        onDispose { controller.launch = null }
    }

    return controller
}

@Composable
actual fun rememberAppSettingsOpener(): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

/**
 * Permission state held in a named class rather than local `remember`s.
 *
 * The shorter-looking alternative — an anonymous object whose `status` getter reads a remembered
 * variable of the same name — compiles and then recurses into itself until the stack runs out.
 *
 * [read] is kept as a lambda rather than a captured initial value so [refresh] can go back to the
 * platform, which is the whole point of having it: the answer changes behind the app's back every
 * time somebody visits system settings.
 */
private class MutablePermission(private val read: () -> PermissionStatus) : PermissionController {

    override var status: PermissionStatus by mutableStateOf(read())
        private set

    /** Set while the launcher is composed, null once it is gone. */
    var launch: (() -> Unit)? = null

    override fun request() {
        if (!status.canRequest) return
        // Marked pending *before* the dialog appears, so a screen waiting on an answer does not
        // read the still-unrequested state as a refusal.
        status = PermissionStatus.PENDING
        launch?.invoke()
    }

    override fun refresh() {
        // A pending request is mid-dialog; the platform still reports the old answer, and
        // overwriting the pending state with it would settle the request early and wrongly.
        if (status == PermissionStatus.PENDING) return
        status = read()
    }

    fun reread() {
        status = read()
    }

    fun settle(granted: Boolean) {
        status = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }
}

private fun Context.cameraStatus(): PermissionStatus = when {
    !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) -> PermissionStatus.UNSUPPORTED
    isGranted(Manifest.permission.CAMERA) -> PermissionStatus.GRANTED
    else -> PermissionStatus.NOT_REQUESTED
}

private fun Context.locationStatus(): PermissionStatus = when {
    isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) -> PermissionStatus.GRANTED

    else -> PermissionStatus.NOT_REQUESTED
}

/**
 * Where notifications stand before anything is asked.
 *
 * Two separate questions on Android 13 and up: whether the runtime permission is held, and whether
 * the user has since switched the app's notifications off in Settings. The permission dialog cannot
 * fix the second, so a channel the user has disabled is reported as [PermissionStatus.DENIED]
 * rather than as something worth prompting about.
 */
private fun Context.notificationStatus(): PermissionStatus {
    val enabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return if (enabled) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }
    val granted = isGranted(Manifest.permission.POST_NOTIFICATIONS)
    return when {
        granted && enabled -> PermissionStatus.GRANTED
        granted -> PermissionStatus.DENIED
        else -> PermissionStatus.NOT_REQUESTED
    }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
