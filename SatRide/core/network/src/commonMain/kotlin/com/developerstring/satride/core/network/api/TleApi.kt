package com.developerstring.satride.core.network.api

import com.developerstring.satride.core.common.AppError
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.network.SatRideHttpClient
import com.developerstring.satride.core.network.TleFormat
import com.developerstring.satride.core.network.TleSource
import com.developerstring.satride.core.network.asAppError
import com.developerstring.satride.core.network.dto.GpElementDto
import com.developerstring.satride.core.orbit.sgp4.TleFormatter
import com.developerstring.satride.core.orbit.sgp4.TleParser
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json

/**
 * Fetches element sets from any configured [TleSource].
 *
 * One entry point for both wire formats, so callers iterate over sources without caring that
 * CelesTrak speaks JSON and everyone else speaks text.
 */
class TleApi(private val client: HttpClient, private val json: Json = SatRideHttpClient.json) {

    /**
     * Downloads and parses [source].
     *
     * Individual malformed element sets are skipped rather than failing the fetch — a single bad
     * line in a catalog of thousands should cost the user one satellite, not all of them. A
     * transport or whole-body failure does throw, as [AppError].
     *
     * Throws [NotModified] when the source says we already have the current data; see there.
     */
    suspend fun fetch(source: TleSource): List<Tle> {
        val body = try {
            client.get(source.url).bodyAsText()
        } catch (throwable: ClientRequestException) {
            // CelesTrak answers a repeat request for an unchanged group with **403** and a line of
            // prose saying so:
            //
            //   "GP data has not updated since your last successful download of GROUP=active at
            //    2026-09-05 10:39:34 UTC. Data is updated once every 2 hours."
            //
            // That is a cache directive wearing an error's status code, and it is the *correct*
            // answer to a client that already has the data. Treated as a failure it produced a
            // refresh that reported itself broken every two hours — which is how this was found:
            // the app told the user the catalog was busy when in fact nothing was wrong and
            // nothing needed downloading.
            if (throwable.response.status.value == HttpStatusCode.Forbidden.value) {
                val explanation = runCatching { throwable.response.bodyAsText() }.getOrDefault("")
                if (explanation.contains(NOT_MODIFIED_MARKER, ignoreCase = true)) {
                    throw NotModified(source.label)
                }
            }
            throw throwable.asAppError(source.label)
        } catch (throwable: Throwable) {
            throw throwable.asAppError(source.label)
        }

        return try {
            when (source.format) {
                TleFormat.GP_JSON -> parseGpJson(body)
                TleFormat.TLE_TEXT -> parseTleText(body)
            }
        } catch (throwable: Throwable) {
            throw throwable.asAppError(source.label)
        }
    }

    private fun parseGpJson(body: String): List<Tle> {
        // CelesTrak answers a bad query with HTTP 200 and a line of prose, so `expectSuccess`
        // never fires and the JSON parser is left to report "expected [ but found I" — which says
        // nothing about the actual problem. Detecting it here turns a retired group name into a
        // message that names the group.
        val start = body.trimStart()
        if (!start.startsWith("[") && !start.startsWith("{")) {
            throw AppError.Server(
                statusCode = null,
                message = "CelesTrak rejected the query: ${start.lineSequence().first().take(200)}",
            )
        }
        return json.decodeFromString<List<GpElementDto>>(body).mapNotNull { dto ->
            runCatching { dto.toTle() }.getOrNull()
        }
    }

    private fun parseTleText(body: String): List<Tle> = TleParser.parseCatalog(body).map { elements ->
        // Re-render rather than slicing the original text: it normalises the handful of
        // characters that carry no information (blank ephemeris type, the sign of a zero
        // exponent), so the same element set from two sources compares equal byte-for-byte.
        TleFormatter.format(elements)
    }

    companion object {
        /**
         * The distinctive part of CelesTrak's "you already have this" body.
         *
         * Matched on the sentence rather than on the status alone, because a 403 from anything
         * else — a blocked user agent, a genuine refusal — still has to be reported as a failure.
         */
        private const val NOT_MODIFIED_MARKER = "has not updated since your last successful download"
    }
}

/**
 * The source has nothing new: we already hold the current data.
 *
 * A distinct type rather than an [AppError] because it is not one. A refresh that meets this has
 * succeeded — there was nothing to fetch — and reporting it as a failed source made a routine,
 * correct answer look like an outage to the user.
 */
class NotModified(val sourceLabel: String) : Exception("$sourceLabel already has the current data")
