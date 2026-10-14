package com.developerstring.satride.core.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * Whether the app may do something the user has to agree to.
 *
 * [NOT_REQUESTED] is kept distinct from [DENIED] on purpose. They look the same to code that only
 * asks "may I?", and they are completely different to a user: one means the question has not been
 * put yet, the other means they said no and must not be asked again. Collapsing them is how apps
 * end up re-prompting on every launch.
 *
 * [PENDING] exists because some callers ask in the middle of an action — the timeline's
 * [Remind me] arms a reminder only *after* the answer arrives. Without a settled/unsettled
 * distinction those screens cannot tell "they said no" from "they have not answered yet", and
 * would report a refusal the moment the system dialog opened.
 */
enum class PermissionStatus {
    NOT_REQUESTED,
    PENDING,
    GRANTED,
    DENIED,

    /** No such hardware, or a platform that cannot offer it. Nothing the user can change. */
    UNSUPPORTED,
    ;

    val isGranted: Boolean get() = this == GRANTED

    /** True only when asking could still change the answer. */
    val canRequest: Boolean get() = this == NOT_REQUESTED

    /** The user has answered, one way or the other. */
    val settled: Boolean get() = this == GRANTED || this == DENIED || this == UNSUPPORTED
}

/**
 * A permission, and the ability to ask for it.
 *
 * Deliberately separate from the thing that needs the permission, so a screen can explain itself
 * before the system dialog appears. A prompt with no preceding context is the most reliable way
 * to earn a permanent refusal, and here that would cost the app its hero feature.
 */
@Stable
interface PermissionController {
    val status: PermissionStatus

    /** Asks the platform. A no-op unless [PermissionStatus.canRequest]. */
    fun request()

    /**
     * Re-reads the platform's answer.
     *
     * Needed because a permission can change while the app is in the background — the user walks
     * into system settings and revokes it, or grants one this app already gave up asking for. A
     * controller that only ever learns from its own dialog would show that user a stale answer
     * forever, which on the profile screen is the difference between an honest switch and a lie.
     */
    fun refresh()
}

/**
 * The three permissions SatRide asks for, with the one-line reason each is worth granting.
 *
 * The copy lives here rather than in either screen because the priming screen and the profile
 * screen say the same things about the same permissions, and two copies of that drift.
 */
enum class SkyPermission(val label: String, val why: String) {
    LOCATION("Location", "To work out which satellites are above you"),
    CAMERA("Camera", "To draw satellites over the real sky"),
    NOTIFICATIONS("Notifications", "To nudge you just before a pass"),
}

/** All three controllers together, for the screens that present them as a set. */
@Stable
class SkyPermissions(
    val location: PermissionController,
    val camera: PermissionController,
    val notifications: PermissionController,
) {
    operator fun get(permission: SkyPermission): PermissionController = when (permission) {
        SkyPermission.LOCATION -> location
        SkyPermission.CAMERA -> camera
        SkyPermission.NOTIFICATIONS -> notifications
    }

    val entries: List<Pair<SkyPermission, PermissionController>>
        get() = SkyPermission.entries.map { it to get(it) }

    /**
     * How far through [requestAll] we are, or -1 when no run is in progress.
     *
     * The index is advanced *before* the permission at it is asked for, so a run can never ask
     * the same permission twice however many times [advance] is driven.
     */
    private var cursor by mutableIntStateOf(-1)

    /** True while a run is working through the list. */
    val asking: Boolean get() = cursor >= 0

    /**
     * Asks for all three, one after another.
     *
     * **One at a time, not all at once.** Android shows a single permission dialog per activity;
     * a second `launch()` issued while the first dialog is up is dropped rather than queued, so
     * firing all three together granted location and silently never asked for the camera or
     * notifications at all — with the screen left showing two rows that looked like refusals.
     * Each one now waits for the previous to settle, which is driven by [rememberSkyPermissions].
     */
    fun requestAll() {
        cursor = 0
        advance()
    }

    /**
     * Asks for the next permission in the run, if the previous one is done being answered.
     *
     * Called on every status change while a run is live. Permissions that cannot be asked for —
     * already granted, already refused, unsupported — are stepped over without a dialog.
     */
    internal fun advance() {
        if (cursor < 0) return
        // A dialog is still up. Its answer will drive this again.
        if (entries.any { (_, controller) -> controller.status == PermissionStatus.PENDING }) return

        val all = entries
        while (cursor < all.size) {
            val controller = all[cursor].second
            cursor++
            if (controller.status.canRequest) {
                controller.request()
                return
            }
        }
        cursor = -1
    }

    fun refreshAll() = entries.forEach { (_, controller) -> controller.refresh() }

    /** True once no permission is still waiting on the user. */
    val allSettled: Boolean get() = entries.all { (_, controller) -> controller.status.settled }
}

@Composable
fun rememberSkyPermissions(): SkyPermissions {
    val location = rememberLocationPermission()
    val camera = rememberCameraPermission()
    val notifications = rememberNotificationPermission()
    val bundle = remember(location, camera, notifications) {
        SkyPermissions(location = location, camera = camera, notifications = notifications)
    }

    // Drives [SkyPermissions.advance] off every status change, which is what turns `requestAll`
    // into a chain: each answer releases the next dialog.
    LaunchedEffect(bundle) {
        snapshotFlow { bundle.entries.map { (_, controller) -> controller.status } }
            .collect { bundle.advance() }
    }

    return bundle
}

/** The camera, for the AR view's backdrop. */
@Composable
expect fun rememberCameraPermission(): PermissionController

/**
 * Location, for knowing which satellites are overhead at all.
 *
 * Requests precise location where the platform distinguishes it. Approximate location is very
 * nearly enough — two kilometres of observer error is about 0.14° at an 800 km range, against the
 * one to five degrees the compass contributes — but it is the one term in the AR accuracy budget
 * that can be removed rather than merely reduced. A user who grants only the approximate form
 * still gets a fully working app.
 */
@Composable
expect fun rememberLocationPermission(): PermissionController

/** Posting a notification, for pass reminders. */
@Composable
expect fun rememberNotificationPermission(): PermissionController

/**
 * Opens this app's page in the system settings, or null where there is no such place.
 *
 * The only route back from [PermissionStatus.DENIED]: the platform will not show its dialog a
 * second time, so an app that cannot send the user to settings has no answer at all for someone
 * who changed their mind.
 */
@Composable
expect fun rememberAppSettingsOpener(): (() -> Unit)?

/** For platforms and previews where the permission cannot exist. */
internal object UnsupportedPermission : PermissionController {
    override val status: PermissionStatus = PermissionStatus.UNSUPPORTED
    override fun request() = Unit
    override fun refresh() = Unit
}
