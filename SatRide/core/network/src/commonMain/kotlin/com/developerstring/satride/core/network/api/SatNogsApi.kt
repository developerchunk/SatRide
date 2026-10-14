package com.developerstring.satride.core.network.api

import com.developerstring.satride.core.model.Transmitter
import com.developerstring.satride.core.network.SatRideHttpClient
import com.developerstring.satride.core.network.asAppError
import com.developerstring.satride.core.network.dto.TransmitterDto
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json

/**
 * The SatNOGS transmitter database.
 *
 * Data is CC BY-SA 4.0 — see `DataAttribution.SATNOGS`, which the About screen renders. The
 * `status=active` filter is applied server-side rather than by us: it is what Look4Sat does, it
 * halves the payload, and filtering client-side would mean downloading records only to discard
 * them on a mobile connection.
 */
class SatNogsApi(private val client: HttpClient, private val json: Json = SatRideHttpClient.json) {

    /**
     * Every active transmitter, mapped and filtered to receivable downlinks.
     *
     * The endpoint returns the whole set in one response — about 2 MB of JSON, roughly 2,900
     * records — with no pagination, so there is no page loop to write. If that ever changes it
     * will show up as a truncated catalog, which the count in `RefreshResult` makes visible.
     */
    suspend fun transmitters(): List<Transmitter> = fetch(ALL_ACTIVE_URL)

    /** Transmitters for one satellite, for the detail screen's on-demand refresh. */
    suspend fun transmittersFor(noradId: Int): List<Transmitter> =
        fetch("$BASE_URL?format=json&status=active&satellite__norad_cat_id=$noradId")

    private suspend fun fetch(url: String): List<Transmitter> {
        val body = try {
            client.get(url).bodyAsText()
        } catch (throwable: Throwable) {
            throw throwable.asAppError("SatNOGS transmitters")
        }

        return try {
            json.decodeFromString<List<TransmitterDto>>(body).mapNotNull { it.toTransmitter() }
        } catch (throwable: Throwable) {
            throw throwable.asAppError("SatNOGS transmitters")
        }
    }

    private companion object {
        const val BASE_URL = "https://db.satnogs.org/api/transmitters/"
        const val ALL_ACTIVE_URL = "$BASE_URL?format=json&status=active"
    }
}
