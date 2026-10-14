package com.developerstring.satride.core.orbit

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.model.SatelliteSnapshot
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.model.Topocentric
import com.developerstring.satride.core.orbit.doppler.Doppler
import com.developerstring.satride.core.orbit.doppler.DopplerState
import com.developerstring.satride.core.orbit.frames.Frames
import com.developerstring.satride.core.orbit.passes.PassPredictor
import com.developerstring.satride.core.orbit.passes.PassSearchOptions
import com.developerstring.satride.core.orbit.sgp4.OrbitalElements
import com.developerstring.satride.core.orbit.sgp4.Sgp4
import com.developerstring.satride.core.orbit.sgp4.TemeState
import com.developerstring.satride.core.orbit.sgp4.TleParser
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One satellite, ready to be asked questions.
 *
 * Wraps an initialised propagator so callers work in terms the app cares about — where is it,
 * when does it come over, what frequency do I tune — rather than in orbital mechanics.
 *
 * Construction does real work (the propagator initialises its coefficients), so keep an
 * instance for as long as the element set is current rather than rebuilding one per frame.
 */
public class OrbitingSatellite internal constructor(public val elements: OrbitalElements, private val sgp4: Sgp4) {
    public val noradId: Int get() = elements.noradId
    public val name: String get() = elements.name
    public val epoch: Instant get() = elements.epoch

    /** Raw inertial state — the input to everything else. */
    public fun stateAt(time: Instant): TemeState = sgp4.propagate(time)

    /** Where the satellite appears from [observer]. */
    public fun observe(time: Instant, observer: GeoPos): Topocentric =
        Frames.topocentric(sgp4.propagate(time), observer)

    /** Full live state for the tracking engine and the sky view. */
    public fun snapshot(time: Instant, observer: GeoPos): SatelliteSnapshot {
        val state = sgp4.propagate(time)
        val topo = Frames.topocentric(state, observer)
        val subPoint = Frames.subPoint(state)
        return SatelliteSnapshot(
            noradId = elements.noradId,
            time = time,
            topo = topo,
            subPoint = subPoint,
            footprintRadiusKm = Frames.footprintRadiusKm(subPoint.altM / 1000.0),
            speedKmS = state.velocity.magnitude,
            sunlit = Frames.isSunlit(state.position, time),
        )
    }

    /**
     * Doppler-corrected receive frequency for a downlink at [transmitHz].
     *
     * The drift term is computed from the range rate one second later, both values coming
     * straight from the orbit model, so the receiver can ramp smoothly between updates.
     */
    public fun doppler(time: Instant, observer: GeoPos, transmitHz: Long, invert: Boolean = false): DopplerState {
        val now = observe(time, observer)
        val next = observe(time + 1.seconds, observer)
        return Doppler.state(
            transmitHz = transmitHz,
            rangeRateKmS = now.rangeRateKmS,
            rangeRateOneSecondLaterKmS = next.rangeRateKmS,
            invert = invert,
        )
    }

    /** Transits over [observer] between [start] and [end]. */
    public fun passes(
        observer: GeoPos,
        start: Instant,
        end: Instant,
        options: PassSearchOptions = PassSearchOptions(),
    ): List<Pass> = PassPredictor(sgp4, observer, options).findPasses(start, end)

    /** The next transit over [observer], or `null` if there is none within [searchLimit]. */
    public fun nextPass(
        observer: GeoPos,
        from: Instant,
        searchLimit: Duration = Duration.parse("P7D"),
        options: PassSearchOptions = PassSearchOptions(),
    ): Pass? = PassPredictor(sgp4, observer, options).nextPass(from, searchLimit)

    /**
     * How stale the element set is at [now].
     *
     * SGP4 error grows roughly 1–3 km per day for a low orbit, so this is surfaced in the UI
     * rather than hidden: a user pointing at empty sky deserves to know the data is old.
     */
    public fun elementAge(now: Instant): Duration = now - elements.epoch
}

/**
 * Entry point to the orbit engine.
 *
 * Everything here is pure computation over shared Kotlin — no platform APIs, no I/O, no
 * clocks. That is what lets the same code produce identical answers on Android and iOS, and
 * what makes this module publishable on its own as `orbitk`.
 */
public object Orbit {

    /** Builds a propagator from a stored element set. */
    public fun satellite(tle: Tle): OrbitingSatellite = satellite(TleParser.parse(tle))

    /**
     * Builds a propagator from raw TLE lines.
     *
     * Set [strict] when the lines came from a human — see [TleParser.parse] for why checksum
     * enforcement is not the default.
     */
    public fun satellite(name: String, line1: String, line2: String, strict: Boolean = false): OrbitingSatellite =
        satellite(TleParser.parse(name, line1, line2, strict))

    /** Builds a propagator from already-parsed elements. */
    public fun satellite(elements: OrbitalElements): OrbitingSatellite = OrbitingSatellite(elements, Sgp4(elements))

    /**
     * Parses a multi-satellite TLE file, skipping entries that fail to parse.
     *
     * One corrupt element set in a catalog of thousands should cost the user that satellite,
     * not the whole download.
     */
    public fun parseCatalog(text: String, strict: Boolean = false): List<OrbitalElements> =
        TleParser.parseCatalog(text, strict)
}
