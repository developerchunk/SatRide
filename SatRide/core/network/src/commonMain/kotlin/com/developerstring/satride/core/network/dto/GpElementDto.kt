package com.developerstring.satride.core.network.dto

import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.sgp4.OrbitalElements
import com.developerstring.satride.core.orbit.sgp4.TleFormatter
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * One object from CelesTrak's General Perturbations endpoint.
 *
 * The field names are CelesTrak's, in the CCSDS OMM vocabulary. Note that this endpoint serves
 * *mean elements* and not TLE text — [toTle] renders them back into element-set lines so the
 * rest of the app sees the same representation regardless of which source it came from.
 */
@Serializable
data class GpElementDto(
    @SerialName("OBJECT_NAME") val objectName: String = "",
    @SerialName("OBJECT_ID") val objectId: String? = null,
    @SerialName("NORAD_CAT_ID") val noradCatId: Int,
    @SerialName("EPOCH") val epoch: String,
    /** Revolutions per day. */
    @SerialName("MEAN_MOTION") val meanMotion: Double,
    @SerialName("ECCENTRICITY") val eccentricity: Double,
    @SerialName("INCLINATION") val inclination: Double,
    @SerialName("RA_OF_ASC_NODE") val raOfAscNode: Double,
    @SerialName("ARG_OF_PERICENTER") val argOfPericenter: Double,
    @SerialName("MEAN_ANOMALY") val meanAnomaly: Double,
    @SerialName("EPHEMERIS_TYPE") val ephemerisType: Int = 0,
    @SerialName("CLASSIFICATION_TYPE") val classificationType: String = "U",
    @SerialName("ELEMENT_SET_NO") val elementSetNo: Int = 0,
    @SerialName("REV_AT_EPOCH") val revAtEpoch: Int = 0,
    @SerialName("BSTAR") val bstar: Double = 0.0,
    /** First derivative of mean motion over two — already the value the TLE field holds. */
    @SerialName("MEAN_MOTION_DOT") val meanMotionDot: Double = 0.0,
    /** Second derivative of mean motion over six. */
    @SerialName("MEAN_MOTION_DDOT") val meanMotionDdot: Double = 0.0,
) {

    /**
     * Renders this as a [Tle].
     *
     * Verified by round-tripping against ARISS's published text for the same ISS element set:
     * two unrelated endpoints, byte-identical output (see `GpElementDtoTest`).
     */
    fun toTle(): Tle = TleFormatter.format(toOrbitalElements())

    fun toOrbitalElements(): OrbitalElements {
        val instant = parseEpoch(epoch)
        val (epochYear, dayOfYear) = tleEpochFields(instant)
        return OrbitalElements(
            noradId = noradCatId,
            name = objectName.trim(),
            epoch = instant,
            epochYear = epochYear,
            epochDayOfYear = dayOfYear,
            meanMotionDot = meanMotionDot,
            meanMotionDdot = meanMotionDdot,
            bstar = bstar,
            inclinationRad = inclination * Constants.DEG_TO_RAD,
            raanRad = raOfAscNode * Constants.DEG_TO_RAD,
            eccentricity = eccentricity,
            argPerigeeRad = argOfPericenter * Constants.DEG_TO_RAD,
            meanAnomalyRad = meanAnomaly * Constants.DEG_TO_RAD,
            meanMotionRadPerMin = meanMotion * Constants.TWO_PI / Constants.MINUTES_PER_DAY,
            revolutionNumber = revAtEpoch,
            elementNumber = elementSetNo,
            classification = classificationType.firstOrNull() ?: 'U',
            internationalDesignator = tleDesignator(objectId),
            ephemerisType = ephemerisType,
        )
    }
}

// Top-level rather than in a companion: `@Serializable` generates its own companion holding the
// serializer, and declaring a private one alongside it makes the generated code unable to reach
// it — which fails at runtime, not compile time.

/** CelesTrak sends `2026-08-01T19:07:25.749120` — ISO with no zone marker, always UTC. */
private fun parseEpoch(raw: String): Instant = LocalDateTime.parse(raw.removeSuffix("Z")).toInstant(TimeZone.UTC)

/** Splits an instant into the TLE epoch's two-digit year and fractional day of year. */
private fun tleEpochFields(instant: Instant): Pair<Int, Double> {
    val utc = instant.toLocalDateTime(TimeZone.UTC)
    val secondsIntoDay = utc.hour * 3600 + utc.minute * 60 + utc.second
    val fractionOfDay = (secondsIntoDay + utc.nanosecond / 1_000_000_000.0) / 86_400.0
    return (utc.year % 100) to (utc.dayOfYear + fractionOfDay)
}

/**
 * `1998-067A` in the JSON is `98067A` in a TLE: two-digit launch year, launch number, piece.
 * Anything that does not fit the pattern is passed through unchanged rather than dropped, since
 * the field carries no orbital information.
 */
private fun tleDesignator(objectId: String?): String {
    val id = objectId?.trim().orEmpty()
    if (id.isEmpty()) return ""
    val parts = id.split('-', limit = 2)
    if (parts.size != 2 || parts[0].length < 4) return id.take(8)
    return (parts[0].takeLast(2) + parts[1]).take(8)
}
