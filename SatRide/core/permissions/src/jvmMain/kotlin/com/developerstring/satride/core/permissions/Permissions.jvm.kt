package com.developerstring.satride.core.permissions

import androidx.compose.runtime.Composable

/**
 * Desktop permissions.
 *
 * A desktop OS does not gate these behind runtime dialogs, so there is nothing to ask and the
 * answers are known at compile time:
 *
 * - **Camera**: unsupported. There is no meaningful AR without a device to point at the sky, so the
 *   sky view runs in planetarium mode and the permission rows say so rather than offering a button
 *   that would do nothing.
 * - **Location**: granted. The desktop location source geolocates the network connection, which
 *   needs no consent dialog — it is the same class of coarse fix the phones ask for.
 * - **Notifications**: granted. Pass alerts go through the system's notification centre while the
 *   app is open; whether that centre exists at all is reported by the alert scheduler's capability.
 */
@Composable
actual fun rememberCameraPermission(): PermissionController = UnsupportedPermission

@Composable
actual fun rememberLocationPermission(): PermissionController = DesktopGrantedPermission

@Composable
actual fun rememberNotificationPermission(): PermissionController = DesktopGrantedPermission

/** There is no per-app permission page to open on the desktop. */
@Composable
actual fun rememberAppSettingsOpener(): (() -> Unit)? = null

/** A permission the desktop simply has. */
private object DesktopGrantedPermission : PermissionController {
    override val status: PermissionStatus = PermissionStatus.GRANTED
    override fun request() = Unit
    override fun refresh() = Unit
}
