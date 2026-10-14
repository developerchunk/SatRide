package com.developerstring.satride.core.database

import com.developerstring.satride.core.database.entity.PassCacheEntity
import com.developerstring.satride.core.database.entity.SatelliteEntity
import com.developerstring.satride.core.database.entity.TleEntity
import com.developerstring.satride.core.database.entity.TransmitterEntity
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.TimedTopo
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.model.Topocentric
import com.developerstring.satride.core.model.Transmitter
import com.developerstring.satride.core.model.TxMode
import com.developerstring.satride.core.model.TxStatus
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Conversions between the stored rows and the domain types.
 *
 * Kept explicit rather than reusing the domain types as entities. Room would otherwise dictate the
 * shape of types that the orbit engine and the UI both depend on, and every schema change would
 * ripple into code that has nothing to do with storage.
 *
 * Enums are stored by name and read back defensively: a row written by a newer build must not
 * crash an older one, so an unrecognised value degrades to the neutral member instead of throwing.
 */
internal val passJson = Json { ignoreUnknownKeys = true }

fun SatelliteEntity.toDomain(): com.developerstring.satride.core.model.Satellite =
    com.developerstring.satride.core.model.Satellite(
        noradId = noradId,
        name = name,
        classType = SatClass.entries.firstOrNull { it.name == classType } ?: SatClass.OTHER,
        launchDate = launchDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        countryCode = countryCode,
        internationalDesignator = internationalDesignator,
        curated = curated,
        tracked = tracked,
    )

fun com.developerstring.satride.core.model.Satellite.toEntity(): SatelliteEntity = SatelliteEntity(
    noradId = noradId,
    name = name,
    classType = classType.name,
    launchDate = launchDate?.toString(),
    countryCode = countryCode,
    internationalDesignator = internationalDesignator,
    curated = curated,
)

fun TleEntity.toDomain(): Tle = Tle(
    name = name,
    noradId = noradId,
    line1 = line1,
    line2 = line2,
    epoch = Instant.fromEpochMilliseconds(epochMillis),
)

fun Tle.toEntity(fetchedAt: Instant, sourceId: String): TleEntity = TleEntity(
    noradId = noradId,
    name = name,
    line1 = line1,
    line2 = line2,
    epochMillis = epoch.toEpochMilliseconds(),
    fetchedAtMillis = fetchedAt.toEpochMilliseconds(),
    sourceId = sourceId,
)

fun TransmitterEntity.toDomain(): Transmitter = Transmitter(
    id = id,
    noradId = noradId,
    description = description,
    downlinkLowHz = downlinkLowHz,
    downlinkHighHz = downlinkHighHz,
    mode = TxMode.entries.firstOrNull { it.name == mode } ?: TxMode.UNKNOWN,
    baud = baud,
    status = TxStatus.entries.firstOrNull { it.name == status } ?: TxStatus.UNKNOWN,
    invert = invert,
    service = service,
)

fun Transmitter.toEntity(): TransmitterEntity = TransmitterEntity(
    id = id,
    noradId = noradId,
    description = description,
    downlinkLowHz = downlinkLowHz,
    downlinkHighHz = downlinkHighHz,
    mode = mode.name,
    baud = baud,
    status = status.name,
    invert = invert,
    service = service,
)

/**
 * One sample of a pass arc, as stored.
 *
 * Short field names and rounded values, both on purpose. An arc is forty samples per pass and a
 * populated cache holds thousands of passes, so this shape is repeated hundreds of thousands of
 * times — at full `Double` precision the arcs alone came to 46 MB on a real catalog, which is not
 * a reasonable thing to put on a phone. The precision kept here is far finer than a polar plot can
 * draw: hundredths of a degree is a fraction of a pixel, and the propagator's own accuracy is
 * coarser than that anyway.
 */
@Serializable
internal data class ArcPointDto(val t: Long, val az: Double, val el: Double, val r: Double, val rr: Double)

private fun Double.round(decimals: Int): Double {
    var scale = 1.0
    repeat(decimals) { scale *= 10.0 }
    return kotlin.math.round(this * scale) / scale
}

fun PassCacheEntity.toDomain(): Pass = Pass(
    noradId = noradId,
    aos = Instant.fromEpochMilliseconds(aosMillis),
    tca = Instant.fromEpochMilliseconds(tcaMillis),
    los = Instant.fromEpochMilliseconds(losMillis),
    maxElevationDeg = maxElevationDeg,
    aosAzimuthDeg = aosAzimuthDeg,
    losAzimuthDeg = losAzimuthDeg,
    arc = passJson.decodeFromString<List<ArcPointDto>>(arcJson).map { point ->
        TimedTopo(
            time = Instant.fromEpochSeconds(point.t),
            topo = Topocentric(
                azimuthDeg = point.az,
                elevationDeg = point.el,
                rangeKm = point.r,
                rangeRateKmS = point.rr,
            ),
        )
    },
)

fun Pass.toEntity(observerGeohash: String, tleEpoch: Instant, computedAt: Instant): PassCacheEntity = PassCacheEntity(
    noradId = noradId,
    observerGeohash = observerGeohash,
    tleEpochMillis = tleEpoch.toEpochMilliseconds(),
    aosMillis = aos.toEpochMilliseconds(),
    tcaMillis = tca.toEpochMilliseconds(),
    losMillis = los.toEpochMilliseconds(),
    maxElevationDeg = maxElevationDeg,
    aosAzimuthDeg = aosAzimuthDeg,
    losAzimuthDeg = losAzimuthDeg,
    arcJson = passJson.encodeToString(
        arc.map {
            ArcPointDto(
                // Seconds, not milliseconds: an arc sample is a plot point, and three extra
                // digits per sample buy nothing a chart could show.
                t = it.time.epochSeconds,
                az = it.topo.azimuthDeg.round(2),
                el = it.topo.elevationDeg.round(2),
                r = it.topo.rangeKm.round(1),
                rr = it.topo.rangeRateKmS.round(4),
            )
        },
    ),
    computedAtMillis = computedAt.toEpochMilliseconds(),
)
