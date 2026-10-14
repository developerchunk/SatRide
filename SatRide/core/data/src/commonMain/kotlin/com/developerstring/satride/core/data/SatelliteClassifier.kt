package com.developerstring.satride.core.data

import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.network.GpGroup

/**
 * Works out what kind of object a satellite is.
 *
 * Neither CelesTrak nor SatNOGS publishes a category, so it has to be inferred. The group a
 * satellite was fetched from is by far the strongest signal — asking CelesTrak for `weather`
 * returns weather satellites — and the name only refines it.
 *
 * The classification drives the glyph in the sky view and the segments in the catalog, so being
 * wrong is cosmetic rather than dangerous. That is why the name rules are kept few and obvious:
 * chasing the long tail with more patterns would add mistakes faster than accuracy.
 */
object SatelliteClassifier {

    fun classify(name: String, group: GpGroup?): SatClass {
        val upper = name.uppercase()

        // Debris and spent stages come back inside legitimate groups, so the name has to be
        // checked first — otherwise a Falcon 9 upper stage in the `visual` group is listed
        // alongside the ISS as something worth looking at.
        rocketBodyOrDebris(upper)?.let { return it }

        return when (group) {
            GpGroup.STATIONS -> if (isCrewed(upper)) SatClass.CREWED else SatClass.OTHER

            GpGroup.WEATHER, GpGroup.RESOURCE -> SatClass.WEATHER

            GpGroup.AMATEUR -> SatClass.AMATEUR

            GpGroup.CUBESAT, GpGroup.EDUCATION -> SatClass.CUBESAT

            GpGroup.GNSS -> SatClass.NAVIGATION

            // Groups whose members are a mixture, or whose defining property is an orbit rather
            // than a purpose. A Starlink and a OneWeb are both "communications", which is not one
            // of the classes the app draws, so they fall through to the name rules and land on
            // OTHER — which is honest, and which the glyph set is designed to handle.
            GpGroup.SCIENCE,
            GpGroup.GEO,
            GpGroup.STARLINK,
            GpGroup.ONEWEB,
            GpGroup.IRIDIUM,
            GpGroup.GLOBALSTAR,
            GpGroup.ORBCOMM,
            GpGroup.ENGINEERING,
            GpGroup.MILITARY,
            GpGroup.LAST_30_DAYS,
            GpGroup.VISUAL,
            GpGroup.ACTIVE,
            null,
            -> fromNameAlone(upper)
        }
    }

    private fun rocketBodyOrDebris(upper: String): SatClass? = when {
        upper.endsWith(" R/B") || upper.contains("R/B ") -> SatClass.ROCKET_BODY
        upper.contains("DEB") -> SatClass.ROCKET_BODY
        upper.contains("AKM") || upper.contains("PKM") -> SatClass.ROCKET_BODY
        else -> null
    }

    /**
     * Whether a name belongs to a crewed vehicle or station.
     *
     * ## Why this tokenises instead of using `contains`
     *
     * `contains("ISS")` is the obvious implementation and it is wrong, because three letters turn
     * up inside longer words. Against the live SatNOGS feed it classified **SWISSCUBE**,
     * **AISSAT 1** and **OUTPOST MISSION 1** as crewed spacecraft — a Swiss CubeSat, a Norwegian
     * AIS receiver and a rideshare payload, all drawn with the crewed glyph beside the ISS.
     *
     * It only surfaces for satellites that arrive with no CelesTrak group, since the group decides
     * on its own everywhere else — which means the SatNOGS fallback and AMSAT, the two paths least
     * likely to be exercised in testing and most likely to be hit when CelesTrak is down.
     *
     * So single-word markers are matched as whole tokens. Only multi-word markers, which cannot
     * collide by accident, are still matched as substrings.
     */
    private fun isCrewed(upper: String): Boolean {
        if (CREWED_PHRASES.any { upper.contains(it) }) return true
        // Splitting on non-alphanumerics handles every shape the catalogs use: "ISS (ZARYA)",
        // "CSS (TIANHE-1)", "SOYUZ MS-30".
        return upper.split(TOKEN_BOUNDARY).any { it in CREWED_TOKENS }
    }

    /**
     * Used for groups that are a mixture, and for satellites that arrived from a text source with
     * no group attached at all — AMSAT and the user's own imports.
     */
    private fun fromNameAlone(upper: String): SatClass = when {
        isCrewed(upper) -> SatClass.CREWED
        WEATHER_MARKERS.any { upper.startsWith(it) } -> SatClass.WEATHER
        NAVIGATION_MARKERS.any { upper.startsWith(it) } -> SatClass.NAVIGATION
        else -> SatClass.OTHER
    }

    /** Anything the catalogs use as a standalone word for a crewed vehicle or station. */
    private val CREWED_TOKENS = setOf("ISS", "ZARYA", "CSS", "TIANHE", "SOYUZ", "SHENZHOU")

    /** Markers with a space in them, which cannot collide with part of another word. */
    private val CREWED_PHRASES = listOf("CREW DRAGON")

    private val TOKEN_BOUNDARY = Regex("[^A-Z0-9]+")

    /**
     * Weather-satellite name prefixes, mirroring the membership of CelesTrak's `weather` group.
     *
     * ## Why this list is long when the rest are short
     *
     * The two classification paths have to agree, because whichever source writes last decides
     * what is stored: `SatelliteDao.upsertFromCatalog` sets `classType = excluded.classType` on
     * conflict, and the sources that carry no group — the SatNOGS fallback, AMSAT, a user's own
     * import — are classified by name alone. So a satellite fetched from the `weather` group and
     * later refreshed from a group-less one would silently change class, and the glyph in the sky
     * view would change with it. Making the name path cover the same families the group does
     * removes that disagreement rather than papering over it.
     *
     * Every entry was taken from the group's actual contents rather than from memory, which is
     * also why the obvious guesses are absent: `GSAT` is Indian *communications* and is not in the
     * group, and `INSAT` is qualified to `INSAT-3D` because only the 3D series is meteorological —
     * the rest of the INSAT fleet is comms.
     *
     * Matched with `startsWith`, so `METEOR` does not catch `METEOSAT`: the sixth character
     * differs. They need separate entries and have them.
     */
    private val WEATHER_MARKERS = listOf(
        // Polar-orbiting
        "NOAA", "METOP", "METEOR", "FENGYUN", "SUOMI", "DMSP", "SENTINEL-3", "CYGFM",
        // Geostationary
        // "COMS 1" carries the space on purpose: bare "COMS" would also match COMSTAR, a 1970s
        // American communications satellite still in a graveyard orbit and still in the catalogs.
        "GOES", "EWS-G", "HIMAWARI", "METEOSAT", "MTG", "INSAT-3D", "ELEKTRO", "GEO-KOMPSAT", "COMS 1",
        // Highly elliptical and radio-occultation constellations
        "ARKTIKA", "TIANMU",
    )

    private val NAVIGATION_MARKERS = listOf("GPS", "GLONASS", "GALILEO", "BEIDOU", "NAVSTAR", "IRNSS", "QZS")
}
