package com.developerstring.satride.core.network

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.logging.Logger as KtorLogger

/**
 * The single HTTP client the app uses.
 *
 * Configured in one place so every call gets the same timeouts, retry policy and identification.
 * The endpoints we talk to are volunteer-run — CelesTrak, SatNOGS, ARISS and AMSAT are all
 * community infrastructure — so being a polite client is a real requirement, not a nicety.
 */
object SatRideHttpClient {

    /**
     * Sent on every request.
     *
     * CelesTrak explicitly asks that clients identify themselves and blocks generic agents; an
     * anonymous request is the fastest way to get SatRide's traffic banned for everyone.
     */
    const val USER_AGENT: String = "SatRide/1.0 (+https://github.com/duosages/satride)"

    /**
     * Lenient on purpose. `ignoreUnknownKeys` means SatNOGS or CelesTrak adding a field cannot
     * break a shipped build, and `explicitNulls = false` keeps our DTO defaults when a key is
     * simply absent.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun create(enableLogging: Boolean = false): HttpClient = createPlatformHttpClient { configure(enableLogging) }

    /**
     * Builds a client on a caller-supplied [engine]. Tests pass `MockEngine`, which is how the
     * plugin stack itself — retries, timeouts, JSON — gets exercised rather than bypassed.
     */
    fun create(engine: HttpClientEngine, enableLogging: Boolean = false): HttpClient =
        HttpClient(engine) { configure(enableLogging) }

    private fun HttpClientConfig<*>.configure(enableLogging: Boolean) {
        expectSuccess = true

        install(ContentNegotiation) {
            json(this@SatRideHttpClient.json)
        }

        install(UserAgent) {
            agent = USER_AGENT
        }

        // The transmitter catalog is about 2 MB of JSON. Compressed it is a fraction of that,
        // which matters on the mobile connection this app is usually refreshed over.
        install(ContentEncoding) {
            gzip()
            deflate()
        }

        install(HttpTimeout) {
            // Generous: a full catalog fetch on a slow connection is legitimately slow, and
            // timing out a nearly-complete download only to retry it is worse than waiting.
            requestTimeoutMillis = 60.seconds.inWholeMilliseconds
            connectTimeoutMillis = 15.seconds.inWholeMilliseconds
            socketTimeoutMillis = 30.seconds.inWholeMilliseconds
        }

        install(HttpRequestRetry) {
            // Server errors and connection failures only. A 404 means the group name is wrong
            // and a 403 means we are unwelcome; retrying either is pointless and rude.
            retryOnServerErrors(maxRetries = 3)

            // `ClientRequestException` has to be excluded explicitly, and that is the whole point
            // of this predicate.
            //
            // `expectSuccess = true` turns every 4xx into an exception, so a predicate that
            // accepted anything non-cancellation retried them — three times, with backoff, in
            // flat contradiction of the comment above. Measured against CelesTrak: one refusal of
            // the `active` group became four requests for sixteen thousand element sets, which is
            // exactly the behaviour that earns a block in the first place. A 4xx is an answer,
            // not a failure to answer, and answers are not worth asking for twice.
            retryOnExceptionIf(maxRetries = 3) { _, cause ->
                cause !is kotlin.coroutines.cancellation.CancellationException &&
                    cause !is ClientRequestException
            }
            exponentialDelay(base = 2.0, maxDelayMs = 20_000L)
        }

        if (enableLogging) {
            install(Logging) {
                logger = object : KtorLogger {
                    override fun log(message: String) {
                        Logger.d(tag = "SatRideHttp") { message }
                    }
                }
                // HEADERS, not BODY: a body-level log of the transmitter catalog is 2 MB of
                // noise per refresh and drowns everything useful.
                level = LogLevel.HEADERS
            }
        }
    }
}
