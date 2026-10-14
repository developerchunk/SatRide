package com.developerstring.satride.core.network

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * A scheduled ISS transmission — an SSTV weekend, a school contact, a special event.
 *
 * ARISS announces these ad hoc on their site and social channels, with no machine-readable feed.
 * Rather than scrape a page whose layout will change, SatRide reads a small JSON file we publish
 * ourselves, so an event can be surfaced to users without shipping an app update. That matters
 * because SSTV weekends are announced days in advance and last two or three days: an app that
 * needed a release to mention one would always be too late.
 */
@Serializable
data class IssEvent(
    val title: String,
    val description: String = "",
    @SerialName("starts_at") val startsAt: Instant,
    @SerialName("ends_at") val endsAt: Instant,
    /** `SSTV`, `VOICE`, `APRS` — matches the transmitter mode the user should tune. */
    val mode: String = "SSTV",
    @SerialName("frequency_hz") val frequencyHz: Long? = null,
    val url: String? = null,
) {
    fun isActiveAt(time: Instant): Boolean = time >= startsAt && time <= endsAt
    fun isUpcomingAt(time: Instant): Boolean = time < startsAt
}

@Serializable
private data class IssEventsResponse(val events: List<IssEvent> = emptyList())

/**
 * Fetches the ISS event list.
 *
 * **The endpoint is not published yet**, so today every call 404s and the banner simply does not
 * appear. That is the designed behaviour, not a gap: the feature has to degrade silently, because
 * the file is hosted separately from the app and will be missing, malformed or unreachable at some
 * point in the app's life. Failing quietly is the whole contract, which is why the failure path is
 * the one under test.
 */
class IssEventsApi(
    private val client: HttpClient,
    private val json: Json = SatRideHttpClient.json,
    private val url: String = DEFAULT_URL,
) {

    private val logger = Logger.withTag("IssEventsApi")

    private var cachedAt: Instant? = null
    private var cached: List<IssEvent> = emptyList()

    /** The event happening now, if any. Null is by far the common case. */
    suspend fun activeEvent(now: Instant): IssEvent? = events(now).firstOrNull { it.isActiveAt(now) }

    /** The next event that has not started, for a "coming up" line. */
    suspend fun upcomingEvent(now: Instant): IssEvent? =
        events(now).filter { it.isUpcomingAt(now) }.minByOrNull { it.startsAt }

    /**
     * Cached for six hours.
     *
     * Events are announced days ahead and last for days, so anything more eager would be pure
     * traffic. The cache is in memory only: the file is small, and a stale event surviving an app
     * restart is worse than one extra request.
     */
    suspend fun events(now: Instant): List<IssEvent> {
        val fetchedAt = cachedAt
        if (fetchedAt != null && now - fetchedAt < CACHE_TTL) return cached

        val fetched = runCatching {
            json.decodeFromString<IssEventsResponse>(client.get(url).bodyAsText()).events
        }.onFailure {
            // Debug, not warning: with no endpoint published this is the expected path, and a
            // warning on every launch would train everyone to ignore the log.
            logger.d { "no ISS event feed available (${it.message})" }
        }.getOrDefault(emptyList())

        cachedAt = now
        cached = fetched
        return fetched
    }

    companion object {
        const val DEFAULT_URL: String = "https://raw.githubusercontent.com/duosages/satride/main/data/iss-events.json"
        val CACHE_TTL = 6.hours
    }
}
