package com.developerstring.satride.tools.snapshot

import com.developerstring.satride.core.data.CatalogSnapshot
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.network.GpGroup
import com.developerstring.satride.core.network.SatRideHttpClient
import com.developerstring.satride.core.network.TleSource
import com.developerstring.satride.core.network.api.SatNogsApi
import com.developerstring.satride.core.network.api.TleApi
import com.developerstring.satride.core.network.dto.TransmitterDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Builds `catalog_snapshot.json`, the offline catalog bundled with the app.
 *
 * Run with:
 * ```
 * ./gradlew :tools:snapshot:run
 * ```
 *
 * ## What goes in, and why
 *
 * Not the whole catalog. `GROUP=active` is several megabytes of mostly debris and dead hardware,
 * and bundling it would inflate the download for data the user will replace on first refresh
 * anyway. The snapshot is the set worth having *before* any network call succeeds:
 *
 * - every space station and weather satellite — the things people open a tracker for;
 * - the brightest objects, for the naked-eye and AR use case;
 * - amateur and CubeSat objects **that actually have an active downlink**, since a satellite with
 *   nothing transmitting is of no use to the radio half of the app.
 *
 * The last rule is what keeps this at a few hundred entries instead of a few thousand.
 */
private const val TARGET_SIZE = 260

private val ALWAYS_INCLUDE = listOf(GpGroup.STATIONS, GpGroup.WEATHER)
private val INCLUDE_IF_TRANSMITTING = listOf(GpGroup.AMATEUR, GpGroup.CUBESAT)
private val FILL_FROM = listOf(GpGroup.VISUAL)

fun main(args: Array<String>) = runBlocking {
    val outputPath = args.firstOrNull()
        ?: "composeApp/src/commonMain/composeResources/files/catalog_snapshot.json"

    val client = SatRideHttpClient.create(enableLogging = false)
    val tleApi = TleApi(client)
    val satNogsApi = SatNogsApi(client)

    println("Fetching transmitters…")
    val transmitters = satNogsApi.transmitters()
    val transmittingSatellites = transmitters.map { it.noradId }.toSet()
    println("  ${transmitters.size} active transmitters across ${transmittingSatellites.size} satellites")

    val byGroup = LinkedHashMap<GpGroup, List<Tle>>()
    (ALWAYS_INCLUDE + INCLUDE_IF_TRANSMITTING + FILL_FROM).forEach { group ->
        print("Fetching ${group.queryValue}… ")
        val tles = tleApi.fetch(TleSource.celesTrak(group))
        byGroup[group] = tles
        println("${tles.size}")
    }

    // NOAA 15/18/19 have no CelesTrak group any more, so they are fetched one at a time by
    // catalog number — and they are the satellites most likely to produce a new user's first
    // decoded image.
    print("Fetching NOAA APT satellites… ")
    val aptWeather = TleSource.APT_WEATHER.flatMap { tleApi.fetch(it) }
    println("${aptWeather.size}")

    // ARISS last so its fresher ISS element set wins the merge below.
    print("Fetching ARISS ISS… ")
    val ariss = tleApi.fetch(TleSource.ARISS_ISS)
    println("${ariss.size}")

    val selected = LinkedHashMap<Int, Tle>()
    val groupOf = LinkedHashMap<Int, GpGroup>()

    fun take(tles: List<Tle>, group: GpGroup, limit: Int = Int.MAX_VALUE) {
        tles.asSequence().take(limit).forEach { tle ->
            val existing = selected[tle.noradId]
            if (existing == null || tle.epoch > existing.epoch) selected[tle.noradId] = tle
            groupOf.putIfAbsent(tle.noradId, group)
        }
    }

    ALWAYS_INCLUDE.forEach { take(byGroup.getValue(it), it) }
    take(aptWeather, GpGroup.WEATHER)
    INCLUDE_IF_TRANSMITTING.forEach { group ->
        take(byGroup.getValue(group).filter { it.noradId in transmittingSatellites }, group)
    }
    FILL_FROM.forEach { group ->
        val room = (TARGET_SIZE - selected.size).coerceAtLeast(0)
        take(byGroup.getValue(group), group, limit = room)
    }
    ariss.forEach { tle ->
        val existing = selected[tle.noradId]
        if (existing == null || tle.epoch > existing.epoch) selected[tle.noradId] = tle
    }

    val chosenIds = selected.keys
    val snapshot = CatalogSnapshot(
        generatedAt = Instant.now().toString(),
        satelliteCount = selected.size,
        tleText = selected.values
            .sortedBy { it.noradId }
            .joinToString("\n") { "${it.name}\n${it.line1}\n${it.line2}" },
        transmitters = transmitters
            .filter { it.noradId in chosenIds }
            .map { transmitter ->
                // Written back in wire form so the app parses the bundle with the same mapper it
                // uses for a live response — one code path, not two.
                TransmitterDto(
                    uuid = transmitter.id,
                    description = transmitter.description,
                    alive = true,
                    downlinkLow = transmitter.downlinkLowHz,
                    downlinkHigh = transmitter.downlinkHighHz,
                    mode = transmitter.mode.satnogsName ?: transmitter.mode.name,
                    invert = transmitter.invert,
                    baud = transmitter.baud?.toDouble(),
                    noradCatId = transmitter.noradId,
                    status = "active",
                    service = transmitter.service,
                )
            },
        groups = groupOf
            .filterKeys { it in chosenIds }
            .entries
            .groupBy({ it.value.queryValue }, { it.key }),
    )

    val output = File(outputPath)
    output.parentFile?.mkdirs()
    output.writeText(Json { prettyPrint = false }.encodeToString(snapshot))

    println()
    println("Wrote ${output.path}")
    println("  ${snapshot.satelliteCount} satellites, ${snapshot.transmitters.size} transmitters")
    println("  ${output.length() / 1024} KB")
    snapshot.groups.forEach { (group, ids) -> println("  $group: ${ids.size}") }

    client.close()
}
