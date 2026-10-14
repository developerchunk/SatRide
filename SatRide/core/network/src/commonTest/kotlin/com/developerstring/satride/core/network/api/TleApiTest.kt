package com.developerstring.satride.core.network.api

import com.developerstring.satride.core.common.AppError
import com.developerstring.satride.core.network.GpGroup
import com.developerstring.satride.core.network.SatRideHttpClient
import com.developerstring.satride.core.network.TestFixtures
import com.developerstring.satride.core.network.TleSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercised through [MockEngine] rather than a stubbed client, so the plugin stack the app
 * actually ships — content negotiation, retry, timeouts, user agent — is part of what is tested.
 */
class TleApiTest {

    private fun apiReturning(body: String, status: HttpStatusCode = HttpStatusCode.OK): Pair<TleApi, MockEngine> {
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return TleApi(SatRideHttpClient.create(engine)) to engine
    }

    /**
     * CelesTrak's "you already have this" answer is a 403 carrying an explanation.
     *
     * Their servers refuse a repeat request for an unchanged group rather than resending it — a
     * cache directive wearing an error's status code. Read as a failure it made a perfectly
     * healthy app report a broken catalog for the whole two hours between updates, which is
     * exactly what was happening on a device before this test existed.
     */
    @Test
    fun aRepeatRequestForUnchangedDataIsNotAFailure() = runTest {
        val (api, _) = apiReturning(
            body = "GP data has not updated since your last successful download of GROUP=active " +
                "at 2026-09-05 10:39:34 UTC. Data is updated once every 2 hours.",
            status = HttpStatusCode.Forbidden,
        )

        assertFailsWith<NotModified> { api.fetch(TleSource.celesTrak(GpGroup.ACTIVE)) }
    }

    /** A 403 that means something else is still a failure. */
    @Test
    fun anOrdinaryForbiddenIsStillAnError() = runTest {
        val (api, _) = apiReturning(body = "Forbidden", status = HttpStatusCode.Forbidden)

        val error = assertFailsWith<AppError.Server> { api.fetch(TleSource.celesTrak(GpGroup.ACTIVE)) }
        assertEquals(403, error.statusCode)
    }

    /**
     * A refused request is asked once.
     *
     * `expectSuccess` turns a 4xx into an exception, so a retry predicate that accepted anything
     * non-cancellation retried it three times with backoff — four requests for sixteen thousand
     * element sets in answer to one refusal, which is how a client earns a block.
     */
    @Test
    fun aRefusedRequestIsNotRetried() = runTest {
        val (api, engine) = apiReturning(body = "Forbidden", status = HttpStatusCode.Forbidden)

        assertFailsWith<AppError.Server> { api.fetch(TleSource.celesTrak(GpGroup.ACTIVE)) }

        assertEquals(1, engine.requestHistory.size, "a 4xx is an answer, not a reason to ask again")
    }

    @Test
    fun readsCelesTrakGpJson() = runTest {
        val (api, _) = apiReturning(TestFixtures.read("celestrak_stations.json"))

        val tles = api.fetch(TleSource.celesTrak(GpGroup.STATIONS))

        assertTrue(tles.size >= 8)
        val iss = tles.single { it.noradId == 25544 }
        assertEquals("ISS (ZARYA)", iss.name)
        assertTrue(iss.line1.startsWith("1 25544U"))
    }

    @Test
    fun readsArissTleText() = runTest {
        val (api, _) = apiReturning(TestFixtures.read("ariss_iss.txt"))

        val tles = api.fetch(TleSource.ARISS_ISS)

        assertEquals(1, tles.size)
        assertEquals(25544, tles.first().noradId)
        assertEquals("ISS (ZARYA)", tles.first().name)
    }

    @Test
    fun readsAmsatBareThreeLineSets() = runTest {
        val (api, _) = apiReturning(TestFixtures.read("amsat_nasabare_head.txt"))

        val tles = api.fetch(TleSource.AMSAT)

        assertTrue(tles.size >= 10, "got ${tles.size}")
        assertEquals("AO-07", tles.first().name)
        assertEquals(7530, tles.first().noradId)
    }

    /**
     * The SatNOGS 3le response mixes both name conventions in a single body — the first entry has
     * a bare name line and the rest are prefixed `0 NAME`. Parsing only one convention silently
     * loses either the first satellite or all the others.
     */
    @Test
    fun readsSatNogsThreeLineSetsWithMixedNamePrefixes() = runTest {
        val (api, _) = apiReturning(TestFixtures.read("satnogs_3le_head.txt"))

        val tles = api.fetch(TleSource.SATNOGS)

        assertTrue(tles.size >= 15, "got ${tles.size}")
        assertEquals("ISS", tles.first().name)
        assertTrue(tles.any { it.name == "NEMO-HD" }, "the 0-prefixed names were dropped")
        assertTrue(tles.none { it.name.startsWith("0 ") }, "a 3le prefix leaked into a name")
    }

    @Test
    fun skipsMalformedEntriesRatherThanLosingTheWholeCatalog() = runTest {
        val good = TestFixtures.read("amsat_nasabare_head.txt")
        val corrupted = "BROKEN SAT\n1 not a real line\n2 also not one\n" + good
        val (api, _) = apiReturning(corrupted)

        val tles = api.fetch(TleSource.AMSAT)

        assertTrue(tles.size >= 10, "one bad entry cost the whole catalog: got ${tles.size}")
    }

    @Test
    fun reportsAServerFailureAsAServerError() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val api = TleApi(SatRideHttpClient.create(engine))

        val error = assertFailsWith<AppError.Server> { api.fetch(TleSource.ARISS_ISS) }

        assertEquals(HttpStatusCode.ServiceUnavailable.value, error.statusCode)
    }

    @Test
    fun reportsUnreadableJsonAsMalformedRatherThanCrashing() = runTest {
        val (api, _) = apiReturning("{\"this\": \"is not the array we asked for\"}")

        assertFailsWith<AppError.Malformed> { api.fetch(TleSource.celesTrak(GpGroup.STATIONS)) }
    }

    @Test
    fun retriesServerErrorsButNotClientErrors() = runTest {
        var serverAttempts = 0
        val flaky = MockEngine {
            serverAttempts++
            respondError(HttpStatusCode.InternalServerError)
        }
        assertFailsWith<AppError.Server> { TleApi(SatRideHttpClient.create(flaky)).fetch(TleSource.ARISS_ISS) }
        assertEquals(4, serverAttempts, "expected the original request plus three retries")

        var clientAttempts = 0
        val missing = MockEngine {
            clientAttempts++
            respondError(HttpStatusCode.NotFound)
        }
        assertFailsWith<AppError.Server> { TleApi(SatRideHttpClient.create(missing)).fetch(TleSource.ARISS_ISS) }
        assertEquals(
            1,
            clientAttempts,
            "a 404 means the URL is wrong; retrying it three more times just annoys the server",
        )
    }

    @Test
    fun identifiesItselfOnEveryRequest() = runTest {
        val (api, engine) = apiReturning(TestFixtures.read("ariss_iss.txt"))

        api.fetch(TleSource.ARISS_ISS)

        val agent = engine.requestHistory.single().headers[HttpHeaders.UserAgent]
        assertEquals(
            SatRideHttpClient.USER_AGENT,
            agent,
            "CelesTrak blocks unidentified clients — losing this header would ban SatRide for everyone",
        )
    }

    @Test
    fun asksForCompressedResponses() = runTest {
        val (api, engine) = apiReturning(TestFixtures.read("ariss_iss.txt"))

        api.fetch(TleSource.ARISS_ISS)

        val encoding = engine.requestHistory.single().headers[HttpHeaders.AcceptEncoding].orEmpty()
        assertTrue(encoding.contains("gzip"), "Accept-Encoding was '$encoding'")
    }
}
