package com.developerstring.satride.core.network.dto

import com.developerstring.satride.core.network.SatRideHttpClient
import com.developerstring.satride.core.network.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpElementDtoTest {

    private val elements: List<GpElementDto> by lazy {
        SatRideHttpClient.json.decodeFromString<List<GpElementDto>>(
            TestFixtures.read("celestrak_stations.json"),
        )
    }

    @Test
    fun parsesTheRecordedCelesTrakResponse() {
        assertTrue(elements.size >= 8, "expected the stations group, got ${elements.size} objects")

        val iss = elements.single { it.noradCatId == 25544 }
        assertEquals("ISS (ZARYA)", iss.objectName)
        assertEquals("1998-067A", iss.objectId)
        assertEquals(15.49304641, iss.meanMotion)
    }

    /**
     * The check the whole JSON path rests on.
     *
     * CelesTrak serves mean elements; ARISS serves TLE text. Both fixtures were captured minutes
     * apart and hold the same ISS element set, so rendering the JSON must reproduce the ARISS
     * bytes exactly. Nothing here depends on our own code being right — if the epoch conversion,
     * the unit conversions, the fixed-column widths or the checksum were wrong, this fails.
     */
    @Test
    fun celesTrakJsonReproducesTheArissTextForTheSameElementSet() {
        val arissLines = TestFixtures.read("ariss_iss.txt")
            .lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }

        val expectedLine1 = arissLines.single { it.startsWith("1 ") }
        val expectedLine2 = arissLines.single { it.startsWith("2 ") }

        val tle = elements.single { it.noradCatId == 25544 }.toTle()

        assertEquals(
            expectedLine1,
            tle.line1,
            "CelesTrak JSON did not reproduce the ARISS element set — if the two fixtures were " +
                "recaptured at different times their epochs differ; see FIXTURES.md",
        )
        assertEquals(expectedLine2, tle.line2)
    }

    @Test
    fun everyRecordedObjectRendersToAParsableElementSet() {
        val tles = elements.map { it.toTle() }

        assertEquals(elements.size, tles.size)
        tles.forEach { tle ->
            assertEquals(69, tle.line1.length, "line 1 of ${tle.name} is the wrong width")
            assertEquals(69, tle.line2.length, "line 2 of ${tle.name} is the wrong width")
        }
    }

    @Test
    fun convertsTheInternationalDesignatorToTleForm() {
        assertEquals("98067A", elements.single { it.noradCatId == 25544 }.toOrbitalElements().internationalDesignator)
    }

    @Test
    fun readsTheEpochAsUtcWithoutAZoneMarker() {
        val iss = elements.single { it.noradCatId == 25544 }.toOrbitalElements()

        // 2026-08-01T19:07:25.749120Z is day 213 of 2026, 0.79682580 of the way through.
        assertEquals(26, iss.epochYear)
        assertTrue(
            kotlin.math.abs(iss.epochDayOfYear - 213.79682580) < 1e-7,
            "epoch day was ${iss.epochDayOfYear}",
        )
    }
}
