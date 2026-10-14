package com.developerstring.satride.core.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.developerstring.satride.core.model.GeoPos
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Location from the platform's own [LocationManager].
 *
 * Deliberately **not** Google Play Services' fused provider. Fused gives a faster and slightly
 * better first fix, but it would make the app unusable on devices without Play Services — Huawei
 * hardware and de-Googled ROMs, which overlap heavily with the self-hosting, radio-tinkering
 * audience this app is for. The platform manager reaches the same GPS hardware; it simply does
 * less to hide which provider is in use, which is a fair trade for working everywhere.
 *
 * ## Precision
 *
 * GPS is preferred whenever fine location has been granted, with the network provider as the
 * fallback. At satellite ranges the difference is modest — two kilometres of observer error is
 * about 0.14 degrees of pointing error at 800 km, against one to five degrees from the compass —
 * but it is the one term in the budget that can be removed outright rather than merely reduced.
 *
 * A user who grants only approximate location still gets a fully working app. That is why the
 * coarse path is a supported route and not a failure case.
 */
internal class AndroidLocationSource(private val context: Context) : PlatformLocationSource {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val hasFinePermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private val hasPermission: Boolean
        get() = hasFinePermission ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override val isAvailable: Boolean
        get() = locationManager != null && hasPermission

    override suspend fun lastKnown(): GeoFix? {
        val manager = locationManager ?: return null
        if (!hasPermission) return null

        // Best of whatever is cached. For satellite tracking a fix from an hour ago is as useful
        // as one from this second, so a cached position beats making the user wait for a new one.
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
                ?.toGeoFix()
        }.getOrNull()
    }

    override fun fixes(): Flow<GeoFix> = callbackFlow {
        val manager = locationManager
        if (manager == null || !hasPermission) {
            close()
            return@callbackFlow
        }

        val listener = LocationListener { location -> trySend(location.toGeoFix()) }

        // **Both** providers, not one or the other.
        //
        // The original code picked GPS whenever fine location was granted and never registered for
        // the network provider at all. Indoors — which is where an app is set up, and where this
        // was found on a Pixel 6a — GPS may produce no fix for minutes or never, so the app fell
        // back to the cached position, and the cached position was a network fix from *four days
        // earlier*. The Passes screen then computed a whole day of passes for wherever the phone
        // had been on Tuesday, with nothing anywhere saying so.
        //
        // Listening to both costs almost nothing at these intervals and means a coarse fix arrives
        // in seconds while GPS converges behind it. `DefaultLocationProvider` decides which to keep
        // — see `isBetterFix` — so the two interleaving cannot make the answer worse.
        val providers = buildList {
            if (hasFinePermission && manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
            // Only as a last resort: with coarse-only permission the platform refuses GPS updates
            // anyway, but a device with no network provider at all should still get something.
            if (isEmpty() && manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
        }

        val registered = providers.count { provider ->
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    if (provider == LocationManager.GPS_PROVIDER) GPS_INTERVAL_MS else UPDATE_INTERVAL_MS,
                    if (provider == LocationManager.GPS_PROVIDER) GPS_DISTANCE_M else UPDATE_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
            }.isSuccess
        }

        if (registered == 0) {
            close()
            return@callbackFlow
        }

        awaitClose { runCatching { manager.removeUpdates(listener) } }
    }

    private companion object {
        /**
         * Five minutes and five kilometres.
         *
         * Far coarser than a navigation app would use, and correct for this one: the observer
         * position feeds pass prediction, where five kilometres is worth about two seconds of
         * AOS — inside the ten-second accuracy the predictor was verified to. Polling harder
         * would cost battery for numbers that cannot change the answer.
         */
        const val UPDATE_INTERVAL_MS = 5L * 60L * 1000L
        const val UPDATE_DISTANCE_M = 5_000f

        /**
         * A minute and fifty metres, for GPS.
         *
         * Tighter than the network provider's settings because there is a point to it: GPS
         * converges over the first minute or so of a fix, and the thresholds above would reject
         * every improvement after the first reading. Still far looser than a navigation app —
         * the observer is a person standing in a field, not a car.
         */
        const val GPS_INTERVAL_MS = 60L * 1000L
        const val GPS_DISTANCE_M = 50f
    }
}

private fun Location.toGeoFix(): GeoFix = GeoFix(
    position = GeoPos(
        latDeg = latitude,
        lonDeg = longitude,
        altM = if (hasAltitude()) altitude else 0.0,
    ),
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
    // `elapsedRealtimeNanos` rather than `time`: it is monotonic since boot, so a fix's age stays
    // correct even if the wall clock is adjusted — which it routinely is, by the network, moments
    // after a phone boots.
    ageMillis = ((SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L)
        .coerceAtLeast(0L),
)
