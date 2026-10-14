package com.developerstring.satride.core.network.api

import com.developerstring.satride.core.common.AppError
import com.developerstring.satride.core.model.TxMode
import com.developerstring.satride.core.network.SatRideHttpClient
import com.developerstring.satride.core.network.TestFixtures
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

class SatNogsApiTest {

    private fun engineReturningSample() = MockEngine {
        respond(
            content = TestFixtures.read("satnogs_transmitters_sample.json"),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    @Test
    fun mapsTheRecordedTransmitterCatalog() = runTest {
        val api = SatNogsApi(SatRideHttpClient.create(engineReturningSample()))

        val transmitters = api.transmitters()

        assertTrue(transmitters.size >= 90, "got ${transmitters.size}")
        assertTrue(transmitters.all { it.downlinkLowHz > 0 })
        assertTrue(transmitters.any { it.mode == TxMode.SSTV })
    }

    @Test
    fun filtersActiveTransmittersOnTheServer() = runTest {
        val engine = engineReturningSample()
        SatNogsApi(SatRideHttpClient.create(engine)).transmitters()

        val url = engine.requestHistory.single().url.toString()
        assertTrue(
            url.contains("status=active"),
            "the active filter must stay server-side — it halves a 2 MB payload: $url",
        )
    }

    @Test
    fun queriesOneSatelliteByCatalogNumber() = runTest {
        val engine = engineReturningSample()
        SatNogsApi(SatRideHttpClient.create(engine)).transmittersFor(25544)

        val url = engine.requestHistory.single().url.toString()
        assertTrue(url.contains("satellite__norad_cat_id=25544"), url)
        assertTrue(url.contains("status=active"), url)
    }

    @Test
    fun reportsServerFailuresAsAppErrors() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadGateway) }

        val error = assertFailsWith<AppError.Server> {
            SatNogsApi(SatRideHttpClient.create(engine)).transmitters()
        }

        assertEquals(HttpStatusCode.BadGateway.value, error.statusCode)
    }

    @Test
    fun reportsUnreadableBodiesAsMalformed() = runTest {
        val engine = MockEngine {
            respond("<html>maintenance</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
        }

        assertFailsWith<AppError.Malformed> {
            SatNogsApi(SatRideHttpClient.create(engine)).transmitters()
        }
    }
}
