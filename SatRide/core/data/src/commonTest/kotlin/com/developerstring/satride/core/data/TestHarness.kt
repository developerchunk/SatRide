package com.developerstring.satride.core.data

import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.network.SatRideHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestDispatcher
import okio.FileSystem
import okio.Path.Companion.toPath

/** Runs everything on the test dispatcher, so `runTest` controls all of it. */
internal class TestDispatchers(dispatcher: TestDispatcher) : AppDispatchers {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}

internal object TestFixtures {
    fun read(name: String): String =
        FileSystem.SYSTEM.read("${com.developerstring.satride.testfixtures.FIXTURE_DIR}/$name".toPath()) { readUtf8() }
}

/**
 * A MockEngine that answers per URL.
 *
 * The refresh logic is entirely about which sources succeed and which fail, so tests need to say
 * "CelesTrak is down but AMSAT is up" — which means routing by URL rather than returning one
 * canned response.
 */
internal class RoutingEngine {
    private val routes = mutableListOf<Pair<(String) -> Boolean, (HttpRequestData) -> Response>>()
    val requestedUrls = mutableListOf<String>()

    sealed interface Response {
        data class Body(val content: String) : Response
        data class Error(val status: HttpStatusCode) : Response

        /** A status *and* a body — the shape CelesTrak's "already current" answer takes. */
        data class Refusal(val status: HttpStatusCode, val content: String) : Response
    }

    fun on(match: (String) -> Boolean, response: Response): RoutingEngine = apply {
        routes += match to { response }
    }

    fun onUrlContaining(fragment: String, body: String): RoutingEngine =
        on({ it.contains(fragment) }, Response.Body(body))

    fun failUrlContaining(
        fragment: String,
        status: HttpStatusCode = HttpStatusCode.ServiceUnavailable,
    ): RoutingEngine = on({ it.contains(fragment) }, Response.Error(status))

    /** Answers with a status and a body, for servers that explain themselves in a 4xx. */
    fun refuseUrlContaining(fragment: String, status: HttpStatusCode, content: String): RoutingEngine =
        on({ it.contains(fragment) }, Response.Refusal(status, content))

    fun build(): MockEngine = MockEngine { request ->
        val url = request.url.toString()
        requestedUrls += url
        val route = routes.firstOrNull { it.first(url) }?.second?.invoke(request)
        when (route) {
            is Response.Body -> respond(
                content = route.content,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )

            is Response.Error -> respondError(route.status)

            is Response.Refusal -> respond(content = route.content, status = route.status)

            // Anything not explicitly routed is "not there", which is what an unconfigured
            // endpoint should look like — and a 404 is not retried, so tests stay fast.
            null -> respondError(HttpStatusCode.NotFound)
        }
    }

    fun client() = SatRideHttpClient.create(build())
}
