package com.developerstring.satride.core.model

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Broad category of an orbiting object.
 *
 * Drives glyph choice in the sky view and the segments in the catalog. Deliberately coarse:
 * users care whether something is crewed, a weather bird or a ham satellite, not its exact
 * mission designation.
 */
enum class SatClass {
    CREWED,
    WEATHER,
    AMATEUR,
    CUBESAT,
    NAVIGATION,
    ROCKET_BODY,
    OTHER,
}

/**
 * A tracked object, independent of its orbital elements.
 *
 * [noradId] is the catalog number and the primary key everywhere in the app; it is stable
 * across TLE updates, which element sets are not.
 */
data class Satellite(
    val noradId: Int,
    val name: String,
    val classType: SatClass = SatClass.OTHER,
    val launchDate: LocalDate? = null,
    val countryCode: String? = null,
    /**
     * Launch designator in TLE form — `98067A` is the ISS: launch year 98, launch 067, piece A.
     *
     * The only field in the public catalogs that says anything about a satellite's history.
     * Neither CelesTrak's element sets nor SatNOGS carries an operator or a country, so this is
     * what there is, and [launchYear] is the part of it a person can read.
     */
    val internationalDesignator: String? = null,
    /** True for the bundled starter set that ships with the app and works offline. */
    val curated: Boolean = false,
    /** True when the user has selected this object for live tracking. */
    val tracked: Boolean = false,
) {
    /**
     * Four-digit launch year, from the international designator.
     *
     * The designator's two-digit year is disambiguated the way the catalog defines it: 57–99 mean
     * 1957–1999 (Sputnik onwards), 00–56 mean 2000 onwards.
     */
    val launchYear: Int?
        get() {
            val twoDigit = internationalDesignator?.take(2)?.toIntOrNull() ?: return null
            return if (twoDigit < 57) 2000 + twoDigit else 1900 + twoDigit
        }
}

/**
 * A two-line element set: a snapshot of an orbit at [epoch].
 *
 * The raw [line1]/[line2] are kept verbatim rather than only their parsed form so an element
 * set can be re-parsed, exported, or compared byte-for-byte after a refresh.
 *
 * Accuracy decays with age — roughly 1–3 km/day for LEO — which is why [epoch] is surfaced in
 * the UI rather than hidden.
 */
data class Tle(val name: String, val noradId: Int, val line1: String, val line2: String, val epoch: Instant)
