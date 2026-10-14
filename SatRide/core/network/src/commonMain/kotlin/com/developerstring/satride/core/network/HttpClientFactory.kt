package com.developerstring.satride.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * Creates a platform [HttpClient] with [config] applied.
 *
 * Only the engine differs per platform — OkHttp on Android, Darwin on iOS, the JDK client on
 * desktop — so this is the whole expect/actual surface for networking. Every plugin, header and
 * timeout is configured once in [SatRideHttpClient].
 */
internal expect fun createPlatformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient
