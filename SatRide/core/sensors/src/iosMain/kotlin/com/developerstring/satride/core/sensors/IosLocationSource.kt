package com.developerstring.satride.core.sensors

import com.developerstring.satride.core.model.GeoPos
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyNearestTenMeters
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * Location from CoreLocation.
 *
 * Requests ten-metre accuracy. Kilometre accuracy would very nearly do — two kilometres of
 * observer error is about 0.14 degrees of pointing error at 800 km, well inside the one to five
 * degrees the compass contributes — but the observer position is the one term in the AR view's
 * accuracy budget that can be eliminated rather than merely reduced, so it is.
 *
 * The cost is real and accepted: this powers the GPS radio, where kilometre accuracy could have
 * been answered from cell and Wi-Fi alone. [distanceFilter] keeps that from mattering much, since
 * a stationary observer produces almost no updates.
 *
 * Requires `NSLocationWhenInUseUsageDescription` in the app's Info.plist. Without it iOS silently
 * refuses the authorisation request rather than reporting an error, which looks exactly like a
 * user who declined.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosLocationSource : PlatformLocationSource {

    private val manager = CLLocationManager()

    override val isAvailable: Boolean
        get() = CLLocationManager.locationServicesEnabled()

    override suspend fun lastKnown(): GeoFix? = manager.location?.toGeoFix()

    override fun fixes(): Flow<GeoFix> = callbackFlow {
        if (!CLLocationManager.locationServicesEnabled()) {
            close()
            return@callbackFlow
        }

        val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
                val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
                trySend(location.toGeoFix())
            }

            override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
                // Deliberately swallowed. A location failure is not an app failure: the observer
                // position falls back to Unknown or to the user's manual entry, and every screen
                // is built to handle that. Closing the flow here would mean a transient error
                // permanently stopped updates.
            }
        }

        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        // Fifty metres, matching Android's GPS settings. Tight enough to let an initial fix
        // converge, loose enough that a person shifting their feet does not wake the radio.
        manager.distanceFilter = 50.0
        manager.requestWhenInUseAuthorization()
        manager.startUpdatingLocation()

        awaitClose {
            manager.stopUpdatingLocation()
            manager.delegate = null
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CLLocation.toGeoFix(): GeoFix {
    val position = coordinate.useContents {
        GeoPos(latDeg = latitude, lonDeg = longitude, altM = altitude)
    }
    return GeoFix(
        position = position,
        // CoreLocation signals "unknown" with a negative accuracy rather than with null.
        accuracyM = horizontalAccuracy.takeIf { it >= 0.0 },
    )
}
