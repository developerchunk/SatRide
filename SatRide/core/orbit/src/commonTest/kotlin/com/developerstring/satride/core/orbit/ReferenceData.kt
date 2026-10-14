package com.developerstring.satride.core.orbit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Skyfield/pygeomag-generated reference set.
 *
 * See `resources/FIXTURES.md` for how it was produced and what it does and does not prove.
 */
@Serializable
internal data class OrbitReference(
    val tle: ReferenceTle,
    val sites: List<ReferenceSite>,
    val ephemeris: List<EphemerisSample>,
    val topocentric: Map<String, List<TopoSample>>,
    val passes: Map<String, List<ReferencePass>>,
    val wmm: WmmReference,
)

@Serializable
internal data class ReferenceTle(val name: String, val line1: String, val line2: String)

@Serializable
internal data class ReferenceSite(
    val name: String,
    val lat: Double,
    val lon: Double,
    @SerialName("alt_m") val altM: Double,
)

@Serializable
internal data class EphemerisSample(
    val utc: String,
    val temePosKm: List<Double>,
    val temeVelKmS: List<Double>,
    val subLatDeg: Double,
    val subLonDeg: Double,
    val subAltKm: Double,
    val sunlit: Boolean,
    val gmstHours: Double,
)

@Serializable
internal data class TopoSample(
    val utc: String,
    val azDeg: Double,
    val elDeg: Double,
    val rangeKm: Double,
    val rangeRateKmS: Double,
)

@Serializable
internal data class ReferencePass(val aos: TopoEvent, val tca: TopoEvent, val los: TopoEvent)

@Serializable
internal data class TopoEvent(val utc: String, val azDeg: Double, val elDeg: Double)

@Serializable
internal data class WmmReference(val epochDecimalYear: Double, val sites: List<WmmSite>)

@Serializable
internal data class WmmSite(
    val name: String,
    val latDeg: Double,
    val lonDeg: Double,
    val altKm: Double,
    val declinationDeg: Double,
)

internal object ReferenceData {
    private val json = Json { ignoreUnknownKeys = true }

    val reference: OrbitReference by lazy {
        json.decodeFromString(TestFixtures.read("orbit_reference.json"))
    }
}
