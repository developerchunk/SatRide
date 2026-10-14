package com.developerstring.satride.core.data

import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.network.GpGroup
import kotlin.test.Test
import kotlin.test.assertEquals

class SatelliteClassifierTest {

    @Test
    fun takesTheGroupAsTheStrongestSignal() {
        assertEquals(SatClass.WEATHER, SatelliteClassifier.classify("METEOR-M2 3", GpGroup.WEATHER))
        assertEquals(SatClass.AMATEUR, SatelliteClassifier.classify("OSCAR 7 (AO-7)", GpGroup.AMATEUR))
        assertEquals(SatClass.CUBESAT, SatelliteClassifier.classify("CUTE-1 (CO-55)", GpGroup.CUBESAT))
    }

    @Test
    fun recognisesCrewedStations() {
        assertEquals(SatClass.CREWED, SatelliteClassifier.classify("ISS (ZARYA)", GpGroup.STATIONS))
        assertEquals(SatClass.CREWED, SatelliteClassifier.classify("CSS (TIANHE)", GpGroup.STATIONS))
        assertEquals(SatClass.CREWED, SatelliteClassifier.classify("SOYUZ MS-30", null))
    }

    /**
     * Modules of a station are not the station. Poisk and Nauka come back in the `stations` group
     * alongside the ISS, and calling them crewed would put three "crewed" glyphs in the same place
     * in the sky view.
     */
    @Test
    fun doesNotCallEveryObjectInTheStationsGroupCrewed() {
        assertEquals(SatClass.OTHER, SatelliteClassifier.classify("PROGRESS-MS 27", GpGroup.STATIONS))
    }

    /**
     * Spent stages and debris arrive inside legitimate groups — the `visual` group is full of
     * them, because a tumbling upper stage is genuinely bright.
     */
    @Test
    fun separatesRocketBodiesAndDebrisFromRealSatellites() {
        assertEquals(SatClass.ROCKET_BODY, SatelliteClassifier.classify("FALCON 9 R/B", GpGroup.VISUAL))
        assertEquals(SatClass.ROCKET_BODY, SatelliteClassifier.classify("ATLAS CENTAUR R/B", GpGroup.VISUAL))
        assertEquals(SatClass.ROCKET_BODY, SatelliteClassifier.classify("NOAA 11 DEB", GpGroup.WEATHER))
    }

    @Test
    fun fallsBackToTheNameWhenThereIsNoGroup() {
        assertEquals(SatClass.WEATHER, SatelliteClassifier.classify("NOAA 19", null))
        assertEquals(SatClass.WEATHER, SatelliteClassifier.classify("METOP-C", null))
        assertEquals(SatClass.NAVIGATION, SatelliteClassifier.classify("GPS BIIR-2  (PRN 13)", null))
        assertEquals(SatClass.OTHER, SatelliteClassifier.classify("SOME UNKNOWN OBJECT", null))
    }

    /**
     * The name path has to reach the same verdict as the group path, because whichever source
     * writes last is the one that sticks — `upsertFromCatalog` overwrites `classType` on conflict,
     * and the SatNOGS fallback, AMSAT and user imports all carry no group.
     *
     * Every name here is a real entry in CelesTrak's `weather` group, so each one is a case where
     * the two paths would otherwise disagree and a satellite would change class between refreshes.
     */
    @Test
    fun classifiesTheWholeWeatherGroupByNameAsWellAsByGroup() {
        listOf(
            // Geostationary — the families the original marker list missed entirely.
            "METEOSAT-9 (MSG-2)",
            "METEOSAT-10 (MSG-3)",
            "METEOSAT-11 (MSG-4)",
            "METEOSAT-12 (MTG-I1)",
            "MTG-S1",
            "INSAT-3D",
            "INSAT-3DR",
            "INSAT-3DS",
            "ELEKTRO-L 2",
            "GEO-KOMPSAT-2A",
            "COMS 1",
            "EWS-G2 (GOES 15)",
            // Polar-orbiting and elliptical.
            "DMSP 5D-3 F18 (USA 210)",
            "SENTINEL-3A",
            "CYGFM01",
            "ARKTIKA-M 1",
            "TIANMU-1 06",
            // Already covered, kept so a future edit cannot quietly drop them.
            "NOAA 20 (JPSS-1)",
            "METOP-SGA1",
            "METEOR-M2 4",
            "FENGYUN 3G",
            "GOES 19",
            "HIMAWARI-9",
            "SUOMI NPP",
        ).forEach { name ->
            assertEquals(SatClass.WEATHER, SatelliteClassifier.classify(name, null), name)
        }
    }

    /**
     * `startsWith("METEOR")` does not match `METEOSAT` — the sixth character differs — so the two
     * genuinely need separate markers. Pinned because it is exactly the kind of near-miss that
     * looks covered when skimming the list.
     */
    @Test
    fun doesNotAssumeMeteorCoversMeteosat() {
        assertEquals(SatClass.WEATHER, SatelliteClassifier.classify("METEOR-M2 3", null))
        assertEquals(SatClass.WEATHER, SatelliteClassifier.classify("METEOSAT-11 (MSG-4)", null))
    }

    /**
     * The marker list mirrors one CelesTrak group, and stops there. Widening `INSAT-3D` to `INSAT`
     * or adding `GSAT` would sweep in India's communications fleet, which is not weather and is
     * not in the group.
     */
    @Test
    fun doesNotClaimCommunicationsSatellitesAreWeatherSatellites() {
        assertEquals(SatClass.OTHER, SatelliteClassifier.classify("INSAT-4B", null))
        assertEquals(SatClass.OTHER, SatelliteClassifier.classify("GSAT-30", null))
    }

    /**
     * "ISS" and "CSS" are three letters, and three letters turn up inside longer words. Every name
     * here is real and was returned by the live SatNOGS feed; with substring matching all three
     * were classified crewed and drawn with the crewed glyph beside the ISS.
     *
     * It only bites for satellites arriving with no CelesTrak group — the SatNOGS fallback, AMSAT,
     * and user imports — which is to say the paths least likely to be exercised until CelesTrak is
     * down and they are the only ones left.
     */
    @Test
    fun doesNotMistakeWordsContainingIssForCrewedSpacecraft() {
        assertEquals(SatClass.CUBESAT, SatelliteClassifier.classify("SWISSCUBE", GpGroup.CUBESAT))
        assertEquals(SatClass.OTHER, SatelliteClassifier.classify("SWISSCUBE", null))
        assertEquals(SatClass.OTHER, SatelliteClassifier.classify("AISSAT 1", null))
        assertEquals(SatClass.OTHER, SatelliteClassifier.classify("OUTPOST MISSION 1", null))
    }

    /**
     * The other half of the same test: tokenising must not lose the genuine articles, in any of
     * the three shapes the catalogs write them.
     */
    @Test
    fun stillRecognisesCrewedSpacecraftInEveryCatalogSpelling() {
        // AMSAT's nasabare.txt.
        assertEquals(SatClass.CREWED, SatelliteClassifier.classify("ISS", null))
        assertEquals(SatClass.CREWED, SatelliteClassifier.classify("CSS (TIANHE-1)", null))
        // CelesTrak's stations group.
        assertEquals(SatClass.CREWED, SatelliteClassifier.classify("ISS (ZARYA)", null))
        // Visiting vehicles.
        assertEquals(SatClass.CREWED, SatelliteClassifier.classify("SOYUZ MS-30", null))
        assertEquals(SatClass.CREWED, SatelliteClassifier.classify("SHENZHOU-19", null))
        assertEquals(SatClass.CREWED, SatelliteClassifier.classify("CREW DRAGON 8", null))
    }

    /**
     * `COMS 1` keeps its space for the same reason. Bare `COMS` would also match COMSTAR, a 1970s
     * American communications satellite still sitting in a graveyard orbit and still catalogued.
     */
    @Test
    fun doesNotMistakeComstarForAWeatherSatellite() {
        assertEquals(SatClass.WEATHER, SatelliteClassifier.classify("COMS 1", null))
        assertEquals(SatClass.OTHER, SatelliteClassifier.classify("COMSTAR 4 (D-4)", null))
    }

    @Test
    fun isNotConfusedByCase() {
        assertEquals(SatClass.CREWED, SatelliteClassifier.classify("iss (zarya)", GpGroup.STATIONS))
    }
}
