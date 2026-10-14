package com.developerstring.satride.core.network

import com.developerstring.satride.core.network.api.SatNogsApi
import com.developerstring.satride.core.network.api.TleApi
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Networking graph.
 *
 * One [HttpClient] for the whole app: connection pools, DNS caches and the retry policy are all
 * per-client, so a second one would quietly double the sockets and halve the benefit.
 */
fun networkModule(enableLogging: Boolean = false): Module = module {
    single<HttpClient> { SatRideHttpClient.create(enableLogging = enableLogging) }
    single { TleApi(get()) }
    single { SatNogsApi(get()) }
    single { IssEventsApi(get()) }
}
