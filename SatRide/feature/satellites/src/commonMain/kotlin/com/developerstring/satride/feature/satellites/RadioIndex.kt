package com.developerstring.satride.feature.satellites

import com.developerstring.satride.core.model.DecoderKind
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Transmitter

/**
 * Amateur-radio bands, by the names an operator would use.
 *
 * Boundaries are the amateur allocations rather than the formal ITU ones: what matters to a user
 * choosing a filter is "will my 2 m antenna hear this", and the 2 m band is what they call VHF.
 * The ranges are deliberately wide enough to catch the weather satellites at 137 MHz, which are
 * not amateur but are the other thing people point a VHF antenna at.
 */
enum class RadioBand(val label: String, val rangeHz: LongRange) {
    VHF("VHF", 30_000_000L..300_000_000L),
    UHF("UHF", 300_000_000L..1_000_000_000L),
    L("L-band", 1_000_000_000L..2_000_000_000L),
    S("S-band", 2_000_000_000L..4_000_000_000L),
    ;

    companion object {
        /** The band a downlink falls in, or null above S-band where SatRide has nothing to offer. */
        fun of(frequencyHz: Long): RadioBand? = entries.firstOrNull { frequencyHz in it.rangeHz }
    }
}

/**
 * What each satellite can be heard on, derived once from the transmitter catalog.
 *
 * Built in the feature rather than the data layer because every question here is a product one:
 * which bands are worth offering, what counts as a "ham" satellite, whether a satellite with only
 * an inactive downlink should show the radio dot. Storage should not be making those calls.
 */
data class RadioIndex(
    /** Satellites with at least one receivable downlink. */
    val withDownlink: Set<Int> = emptySet(),
    val byBand: Map<RadioBand, Set<Int>> = emptyMap(),
    /**
     * Satellites SatRide can turn into sound or pictures rather than only display.
     *
     * Drives the "Ham/FM" segment, because a satellite whose only downlink is a mode we cannot
     * demodulate is not something a beginner should be pointed at as their first listen.
     */
    val withListenableDownlink: Set<Int> = emptySet(),
) {
    fun bandsOf(noradId: Int): Set<RadioBand> =
        byBand.entries.mapNotNullTo(mutableSetOf()) { (band, ids) -> band.takeIf { noradId in ids } }

    companion object {
        val EMPTY: RadioIndex = RadioIndex()

        fun from(transmitters: List<Transmitter>): RadioIndex {
            val withDownlink = mutableSetOf<Int>()
            val listenable = mutableSetOf<Int>()
            val byBand = mutableMapOf<RadioBand, MutableSet<Int>>()

            transmitters.forEach { transmitter ->
                withDownlink += transmitter.noradId
                RadioBand.of(transmitter.centerHz)?.let { band ->
                    byBand.getOrPut(band) { mutableSetOf() } += transmitter.noradId
                }
                if (transmitter.mode.decoder != DecoderKind.LISTEN_ONLY) {
                    listenable += transmitter.noradId
                }
            }

            return RadioIndex(
                withDownlink = withDownlink,
                byBand = byBand,
                withListenableDownlink = listenable,
            )
        }
    }
}

/**
 * Narrowing applied on top of a segment.
 *
 * Every one of these is free, and every one of them composes with every other — the filter sheet
 * is the tool for the person who knows exactly what they are looking for, and gating half of it
 * would have left them assembling the query by hand in the search field.
 */
data class SatelliteFilters(
    val classes: Set<SatClass> = emptySet(),
    val hasTransmitterOnly: Boolean = false,
    val bands: Set<RadioBand> = emptySet(),
    /** Hides anything not currently at least this high. Zero means no elevation filter. */
    val minElevationDeg: Double = 0.0,
    /** Restricts to the user's tracking selection. */
    val trackedOnly: Boolean = false,
    /** Restricts to satellites SatRide can actually demodulate, not merely display. */
    val listenableOnly: Boolean = false,
    /** Hides spent stages and debris, which are most of a full catalog by count. */
    val hideDebris: Boolean = false,
    /** Only satellites currently above the horizon. */
    val upNowOnly: Boolean = false,
    /** Inclusive launch-year range; null means unbounded on that end. */
    val launchedFrom: Int? = null,
    val launchedTo: Int? = null,
) {
    val isEmpty: Boolean
        get() = classes.isEmpty() && !hasTransmitterOnly && bands.isEmpty() && minElevationDeg <= 0.0 &&
            !trackedOnly && !listenableOnly && !hideDebris && !upNowOnly &&
            launchedFrom == null && launchedTo == null

    /** How many narrowings are active, for the badge on the filter button. */
    val activeCount: Int
        get() = classes.size + bands.size +
            listOf(
                hasTransmitterOnly,
                minElevationDeg > 0.0,
                trackedOnly,
                listenableOnly,
                hideDebris,
                upNowOnly,
                launchedFrom != null || launchedTo != null,
            ).count { it }

    companion object {
        val NONE: SatelliteFilters = SatelliteFilters()

        /** The elevation slider's stops, in degrees. */
        val ELEVATION_STOPS: List<Double> = listOf(0.0, 5.0, 10.0, 20.0, 40.0, 60.0)
    }
}
