package com.developerstring.satride.core.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberCameraPermission(): PermissionController = remember { IosCameraPermission() }

private class IosCameraPermission : PermissionController {

    override var status: PermissionStatus by mutableStateOf(read())
        private set

    override fun request() {
        if (!status.canRequest) return
        status = PermissionStatus.PENDING
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
            // The completion handler arrives on an arbitrary queue; Compose state has to be
            // written from the main one.
            dispatch_async(dispatch_get_main_queue()) {
                status = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
            }
        }
    }

    override fun refresh() {
        if (status == PermissionStatus.PENDING) return
        status = read()
    }

    private fun read(): PermissionStatus = when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
        AVAuthorizationStatusAuthorized -> PermissionStatus.GRANTED

        AVAuthorizationStatusNotDetermined -> PermissionStatus.NOT_REQUESTED

        AVAuthorizationStatusDenied -> PermissionStatus.DENIED

        // Restricted means parental controls or a management profile: the user cannot grant
        // it, so there is nothing to ask and nothing for them to change.
        AVAuthorizationStatusRestricted -> PermissionStatus.UNSUPPORTED

        else -> PermissionStatus.UNSUPPORTED
    }
}

/**
 * Location authorisation.
 *
 * `CLLocationManager` reports its verdict through a delegate rather than a completion handler, so
 * this controller owns one. Re-reading the status straight after asking — the obvious shortcut —
 * always reads `NotDetermined`, because the alert has not been answered yet; a caller chaining
 * several requests would take that as "no dialog is up" and fire the next one over the top of it.
 * With the delegate the request stays [PermissionStatus.PENDING] until the user actually answers.
 *
 * `IosLocationSource` owns a separate manager and calls `requestWhenInUseAuthorization` when its
 * stream starts; the two do not conflict, and the stream begins working the moment authorisation
 * lands without having to be told.
 */
@Composable
actual fun rememberLocationPermission(): PermissionController = remember { IosLocationPermission() }

private class IosLocationPermission : PermissionController {

    private val manager = CLLocationManager()

    override var status: PermissionStatus by mutableStateOf(read())
        private set

    private val listener = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            val resolved = read()
            // Fires once on assignment with the status as it already was. Ignoring the
            // still-undetermined case keeps that initial callback from settling a request that
            // has not been answered.
            if (resolved != PermissionStatus.NOT_REQUESTED) status = resolved
        }
    }

    init {
        manager.delegate = listener
    }

    override fun request() {
        if (!status.canRequest) return
        status = PermissionStatus.PENDING
        manager.requestWhenInUseAuthorization()
    }

    override fun refresh() {
        if (status == PermissionStatus.PENDING) return
        status = read()
    }

    private fun read(): PermissionStatus = when (manager.authorizationStatus) {
        kCLAuthorizationStatusAuthorizedAlways,
        kCLAuthorizationStatusAuthorizedWhenInUse,
        -> PermissionStatus.GRANTED

        kCLAuthorizationStatusNotDetermined -> PermissionStatus.NOT_REQUESTED

        kCLAuthorizationStatusDenied -> PermissionStatus.DENIED

        kCLAuthorizationStatusRestricted -> PermissionStatus.UNSUPPORTED

        else -> PermissionStatus.NOT_REQUESTED
    }
}

@Composable
actual fun rememberNotificationPermission(): PermissionController = remember {
    IosNotificationPermission().also { it.refresh() }
}

/**
 * `UNUserNotificationCenter`, which answers asynchronously and on an arbitrary queue.
 *
 * Every write to [status] is hopped back to the main queue: Compose state must be written from
 * there, and the completion handlers here are not.
 */
private class IosNotificationPermission : PermissionController {

    private val center = UNUserNotificationCenter.currentNotificationCenter()

    override var status: PermissionStatus by mutableStateOf(PermissionStatus.NOT_REQUESTED)
        private set

    override fun refresh() {
        if (status == PermissionStatus.PENDING) return
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val resolved = when (settings?.authorizationStatus) {
                // Provisional counts as granted: iOS delivers those quietly to the notification
                // centre, which for a pass reminder is a degraded but genuine delivery.
                UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional ->
                    PermissionStatus.GRANTED

                UNAuthorizationStatusDenied -> PermissionStatus.DENIED

                UNAuthorizationStatusNotDetermined -> PermissionStatus.NOT_REQUESTED

                else -> PermissionStatus.NOT_REQUESTED
            }
            dispatch_async(dispatch_get_main_queue()) {
                if (status != PermissionStatus.PENDING) status = resolved
            }
        }
    }

    override fun request() {
        if (!status.canRequest) return
        status = PermissionStatus.PENDING
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, _ ->
            dispatch_async(dispatch_get_main_queue()) {
                status = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
            }
        }
    }
}

@Composable
actual fun rememberAppSettingsOpener(): (() -> Unit)? = remember {
    {
        // The three-argument form, because the one-argument overload returns a Bool nobody reads
        // and is deprecated on modern iOS.
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { url ->
            UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>(), null)
        }
        Unit
    }
}
