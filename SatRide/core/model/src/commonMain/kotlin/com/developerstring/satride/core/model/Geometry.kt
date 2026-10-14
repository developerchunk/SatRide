package com.developerstring.satride.core.model

import kotlin.time.Instant

/**
 * A position on the Earth, WGS-84.
 *
 * [altM] is height above the ellipsoid in metres. For SatRide's purposes altitude barely matters
 * — a 1 km error moves a satellite at 800 km range by well under a tenth of a degree — so a
 * missing altitude is not worth blocking on.
 */
data class GeoPos(val latDeg: Double, val lonDeg: Double, val altM: Double = 0.0)

/**
 * Where a satellite is *as seen from an observer*: the numbers the AR view and the radio both
 * ultimately consume.
 *
 * [rangeRateKmS] is negative while the satellite approaches and positive as it recedes, so
 * Doppler shift is positive on approach. It passes through zero exactly at closest approach.
 */
data class Topocentric(
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val rangeKm: Double,
    val rangeRateKmS: Double,
) {
    /** Above the horizon, ignoring terrain. */
    val isVisible: Boolean get() = elevationDeg > 0.0
}

/** A [Topocentric] fix with the time it applies to; the unit of a plotted pass arc. */
data class TimedTopo(val time: Instant, val topo: Topocentric)

/**
 * Everything live about one satellite at one instant.
 *
 * Produced by the tracking engine each tick and consumed by the sky view, the catalog rows and
 * the radio's Doppler controller.
 */
data class SatelliteSnapshot(
    val noradId: Int,
    val time: Instant,
    val topo: Topocentric,
    /** Point on the Earth directly beneath the satellite, plus its height. */
    val subPoint: GeoPos,
    /** Radius of the circle on the ground that can see the satellite, in km. */
    val footprintRadiusKm: Double,
    /** Speed relative to the Earth's centre, km/s. */
    val speedKmS: Double,
    /** In sunlight, so potentially visible to the naked eye against a dark sky. */
    val sunlit: Boolean,
)

/**
 * A single transit of a satellite over an observer.
 *
 * [arc] is sampled densely enough to draw a polar plot without further propagation, so the UI
 * never has to run the orbit model itself.
 */
data class Pass(
    val noradId: Int,
    val aos: Instant,
    val tca: Instant,
    val los: Instant,
    val maxElevationDeg: Double,
    val aosAzimuthDeg: Double,
    val losAzimuthDeg: Double,
    val arc: List<TimedTopo>,
) {
    val duration: kotlin.time.Duration get() = los - aos

    /**
     * True for a satellite that simply never sets from this observer.
     *
     * A geostationary satellite is permanently above the horizon, so a pass search returns one
     * "pass" spanning the entire search window — which the UI would otherwise present as a
     * fourteen-thousand-minute pass starting "now". Nothing in a non-geosynchronous orbit stays
     * up for a day, so a day is a safe and physically meaningful line to draw.
     */
    val isAlwaysVisible: Boolean get() = duration >= kotlin.time.Duration.parse("PT23H")

    /** How good this pass is for radio work, before considering what is transmitting. */
    val quality: PassQuality
        get() = when {
            maxElevationDeg >= 40.0 -> PassQuality.EXCELLENT
            maxElevationDeg >= 20.0 -> PassQuality.GOOD
            else -> PassQuality.MARGINAL
        }
}

/** Coarse pass rating; combined with transmitter status to produce the badge in the UI. */
enum class PassQuality { EXCELLENT, GOOD, MARGINAL }
