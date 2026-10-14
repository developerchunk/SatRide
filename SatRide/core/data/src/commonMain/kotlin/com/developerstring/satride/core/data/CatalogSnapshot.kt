package com.developerstring.satride.core.data

import com.developerstring.satride.core.network.dto.TransmitterDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The catalog SatRide ships with, so the first launch is not a spinner.
 *
 * Deliberately shaped as *the data the app would have downloaded* rather than as a bespoke
 * bundle format: element sets as three-line text and transmitters as the SatNOGS records
 * themselves. It goes through exactly the same parsers and mappers as a live fetch, so the seeded
 * path cannot drift from the fetched one — and there is no second format to keep in sync.
 */
@Serializable
data class CatalogSnapshot(
    /** When the snapshot was built, so the UI can be honest about how old it is. */
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("satellite_count") val satelliteCount: Int = 0,
    /** Classic three-line element sets, exactly as a text source serves them. */
    @SerialName("tle_text") val tleText: String,
    /** SatNOGS transmitter records, exactly as the API serves them. */
    val transmitters: List<TransmitterDto> = emptyList(),
    /**
     * Which group each satellite came from, so classification matches a live refresh.
     *
     * Keyed by group query value (`stations`, `weather`, …) to lists of catalog numbers.
     */
    @SerialName("groups") val groups: Map<String, List<Int>> = emptyMap(),
)

/**
 * Supplies the bundled snapshot's bytes.
 *
 * An interface because reading a bundled asset is a UI-toolkit concern — Compose resources on
 * every platform — and this module has no business depending on Compose. The app shell provides
 * the implementation.
 */
fun interface CatalogSnapshotSource {
    /** Returns the snapshot JSON, or `null` when no snapshot is bundled. */
    suspend fun read(): String?
}
