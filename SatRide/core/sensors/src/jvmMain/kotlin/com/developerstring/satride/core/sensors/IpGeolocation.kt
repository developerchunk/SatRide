package com.developerstring.satride.core.sensors

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.model.GeoPos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * The desktop's location: where its network connection appears to be.
 *
 * A desktop has no GPS, but it is not nowhere. Geolocating the public address puts the observer in
 * the right city, and for this app that is enough — twenty kilometres of observer error moves a
 * satellite at 800 km by under two degrees, which on a planetarium view is invisible. It is the
 * same trade the phones make when they ask for *coarse* location, and it is what makes a fresh
 * desktop install show tonight's passes instead of an empty timeline waiting for coordinates.
 *
 * Anyone who wants better types their position into Settings, and a manual position always wins.
 *
 * ## What it costs
 *
 * One HTTPS request to a public geolocation service, once per launch. The request carries nothing
 * but the connection's own address — which every request the app already makes to CelesTrak and
 * SatNOGS carries too. A failure (offline, a VPN endpoint that resolves nowhere, the service down)
 * is remembered for a while rather than retried on the provider's back-off loop, and the app falls
 * back to the manual-position path it always had.
 */
internal class IpGeolocationSource(
    private val lookup: suspend () -> GeoFix? = { fetchFromIpApi() },
    private val clock: Clock = Clock.System,
) : PlatformLocationSource {

    private val mutex = Mutex()
    private var cached: GeoFix? = null
    private var lastFailureAt: kotlin.time.Instant? = null

    /** Always: the only precondition is a network, and the provider already retries for that. */
    override val isAvailable: Boolean = true

    override suspend fun lastKnown(): GeoFix? = mutex.withLock {
        cached?.let { return it }
        val failedAt = lastFailureAt
        if (failedAt != null && clock.now() - failedAt < RETRY_AFTER) return null

        val fix = runCatching { lookup() }
            .onFailure { Logger.withTag("IpGeolocation").i { "lookup failed: ${it.message}" } }
            .getOrNull()
        if (fix == null) lastFailureAt = clock.now() else cached = fix
        fix
    }

    /** One fix, then done: the machine is not going anywhere, and the provider keeps the last value. */
    override fun fixes(): Flow<GeoFix> = flow { lastKnown()?.let { emit(it) } }

    private companion object {
        val RETRY_AFTER = 5.minutes
    }
}

internal const val IP_API_URL: String = "https://ipapi.co/json/"

/** City-level accuracy, stated as such so the settings screen can show it honestly. */
internal const val IP_FIX_ACCURACY_M: Double = 25_000.0

private val httpClient: HttpClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
}

private suspend fun fetchFromIpApi(): GeoFix? = withContext(Dispatchers.IO) {
    val request = HttpRequest.newBuilder(URI(IP_API_URL))
        .timeout(Duration.ofSeconds(15))
        .header("Accept", "application/json")
        .header("User-Agent", "SatRide-desktop")
        .GET()
        .build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) error("HTTP ${response.statusCode()}")
    parseIpApiFix(response.body())
}

/**
 * Reads the two numbers this needs out of ipapi.co's document. Null for anything that is not a
 * pair of finite coordinates — an error object, a rate-limit notice, a truncated body.
 */
internal fun parseIpApiFix(body: String): GeoFix? {
    val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    if (json["error"]?.jsonPrimitive?.content == "true") return null
    val lat = json["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null
    val lon = json["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
    if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return GeoFix(position = GeoPos(latDeg = lat, lonDeg = lon), accuracyM = IP_FIX_ACCURACY_M, ageMillis = 0L)
}
