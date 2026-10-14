package com.developerstring.satride.core.orbit.sgp4

import com.developerstring.satride.core.orbit.Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TleParserTest {

    private val issName = "ISS (ZARYA)"
    private val issLine1 = "1 25544U 98067A   26213.47431246  .00008515  00000-0  16079-3 0  9990"
    private val issLine2 = "2 25544  51.6315  75.9770 0007211   0.6648 359.4349 15.49301455578771"

    @Test
    fun parsesEveryFieldOfARealElementSet() {
        val elements = TleParser.parse(issName, issLine1, issLine2)

        assertEquals(25544, elements.noradId)
        assertEquals(issName, elements.name)
        assertEquals(26, elements.epochYear)
        assertEquals(213.47431246, elements.epochDayOfYear, 1e-8)
        assertEquals(51.6315, elements.inclinationRad * Constants.RAD_TO_DEG, 1e-6)
        assertEquals(75.9770, elements.raanRad * Constants.RAD_TO_DEG, 1e-6)
        assertEquals(0.0007211, elements.eccentricity, 1e-9)
        assertEquals(0.6648, elements.argPerigeeRad * Constants.RAD_TO_DEG, 1e-6)
        assertEquals(359.4349, elements.meanAnomalyRad * Constants.RAD_TO_DEG, 1e-6)
        assertEquals(
            15.49301455,
            elements.meanMotionRadPerMin * Constants.MINUTES_PER_DAY / Constants.TWO_PI,
            1e-7,
        )
        assertEquals(57877, elements.revolutionNumber)
    }

    /**
     * The exponent fields have an implied leading "0." and their exponent sign runs straight
     * into the mantissa. Splitting on whitespace instead of columns mis-reads these, which is
     * why the parser is column-based.
     */
    @Test
    fun readsTheImpliedDecimalExponentFields() {
        val elements = TleParser.parse(issName, issLine1, issLine2)

        assertEquals(0.16079e-3, elements.bstar, 1e-12)
        assertEquals(0.0, elements.meanMotionDdot, 1e-12)
        assertEquals(0.00008515, elements.meanMotionDot, 1e-12)
    }

    @Test
    fun readsNegativeDragTerms() {
        val line1 = "1 04632U 70093B   04031.91070959 -.00000133  00000-0 -11606-4 0  9967"
        val line2 = "2 04632  11.4628 273.1101 1450506 207.6000 143.9350  1.20231981 44145"

        val elements = TleParser.parse("test", line1, line2)

        assertEquals(-0.11606e-4, elements.bstar, 1e-12)
        assertEquals(-0.00000133, elements.meanMotionDot, 1e-12)
    }

    @Test
    fun rejectsStructurallyBrokenInput() {
        assertFailsWith<TleParseException> { TleParser.parse("x", "not a tle", issLine2) }
        assertFailsWith<TleParseException> { TleParser.parse("x", issLine1, "also not a tle") }
        assertFailsWith<TleParseException> { TleParser.parse("x", issLine2, issLine1) }
    }

    @Test
    fun rejectsLinesFromDifferentSatellites() {
        val otherLine2 = "2 04632  11.4628 273.1101 1450506 207.6000 143.9350  1.20231981 44145"

        val error = assertFailsWith<TleParseException> {
            TleParser.parse("x", issLine1, otherLine2)
        }

        assertTrue(error.message!!.contains("different satellites"))
    }

    /**
     * The eccentricity field carries an implied leading "0.", so the format cannot express a
     * hyperbolic orbit at all: 9999999 means 0.9999999, which is extreme but still elliptical
     * and must parse. The propagator's own range check covers the values SGP4 can produce
     * internally, which is where an eccentricity of 1 or more actually shows up.
     */
    @Test
    fun acceptsExtremeButStillEllipticalEccentricity() {
        val nearParabolic = "2 25544  51.6315  75.9770 9999999   0.6648 359.4349 15.49301455578771"

        val elements = TleParser.parse("x", issLine1, nearParabolic)

        assertEquals(0.9999999, elements.eccentricity, 1e-12)
    }

    /**
     * Checksums are enforced only on request. Real catalogs contain usable element sets whose
     * checksum does not conform, and dropping them by default would silently cost the user
     * satellites — see [TleParser.parse].
     */
    @Test
    fun checksumIsIgnoredByDefaultAndEnforcedOnRequest() {
        // Flip the checksum digit to something it certainly is not.
        val originalChecksum = issLine1.last()
        val corrupted = issLine1.dropLast(1) + if (originalChecksum == '1') '2' else '1'

        TleParser.parse(issName, corrupted, issLine2) // lenient: no exception

        val error = assertFailsWith<TleParseException> {
            TleParser.parse(issName, corrupted, issLine2, strict = true)
        }
        assertTrue(error.message!!.contains("Checksum"))
    }

    @Test
    fun parsesAlpha5CatalogNumbers() {
        // "B1234" encodes 111234: B is the second letter, so the leading digit pair is 11.
        val line1 = "1 B1234U 98067A   26213.47431246  .00008515  00000-0  16079-3 0  9990"
        val line2 = "2 B1234  51.6315  75.9770 0007211   0.6648 359.4349 15.49301455578771"

        val elements = TleParser.parse("alpha5", line1, line2)

        assertEquals(111234, elements.noradId)
    }

    @Test
    fun parsesThreeLineCatalogs() {
        val catalog = """
            $issName
            $issLine1
            $issLine2
            NOAA 19
            1 33591U 09005A   26213.50000000  .00000100  00000-0  10000-3 0  9990
            2 33591  99.1000 200.0000 0014000 100.0000 260.0000 14.12500000123456
        """.trimIndent()

        val parsed = TleParser.parseCatalog(catalog)

        assertEquals(2, parsed.size)
        assertEquals(issName, parsed[0].name)
        assertEquals("NOAA 19", parsed[1].name)
    }

    @Test
    fun parsesTheThreeLineFormWithLeadingZeroMarker() {
        val catalog = """
            0 $issName
            $issLine1
            $issLine2
        """.trimIndent()

        val parsed = TleParser.parseCatalog(catalog)

        assertEquals(1, parsed.size)
        assertEquals(issName, parsed[0].name)
    }

    /** One bad element set must not cost the user the rest of the catalog. */
    @Test
    fun skipsUnparsableEntriesInACatalog() {
        val catalog = """
            BROKEN SAT
            1 99999U 00000A   26213.00000000  .00000000  00000-0  00000-0 0  9999
            2 99999  BAD DATA HERE THAT CANNOT POSSIBLY PARSE AS NUMBERS 0000000000
            $issName
            $issLine1
            $issLine2
        """.trimIndent()

        val parsed = TleParser.parseCatalog(catalog)

        assertEquals(1, parsed.size)
        assertEquals(25544, parsed[0].noradId)
    }
}
