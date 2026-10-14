package com.developerstring.satride.core.orbit.sgp4

import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.time.AstroTime

/**
 * Parser for the two-line element format.
 *
 * The format is fixed-column, not delimited: fields are read by position because values can run
 * together (a negative exponent immediately follows its mantissa) and splitting on whitespace
 * silently mis-parses real element sets.
 */
public object TleParser {

    /** Parses a [Tle] into the elements the propagator consumes. */
    public fun parse(tle: Tle): OrbitalElements = parse(tle.name, tle.line1, tle.line2)

    /**
     * Parses raw TLE lines.
     *
     * [strict] controls checksum enforcement, and is off by default on purpose. Element sets
     * that are perfectly usable but carry a non-conforming checksum do exist — the published
     * AIAA verification set is full of them — and catalogs are fetched over HTTPS, so
     * transport corruption is already caught a layer down. Rejecting on checksum by default
     * would silently cost the user satellites to guard against a problem that has already been
     * handled. Turn it on for text a human typed or pasted, where a typo is the likely fault
     * and saying so is genuinely useful.
     *
     * @throws TleParseException if the lines are malformed or mismatched, or — when [strict] —
     *   fail their checksum.
     */
    public fun parse(name: String, line1: String, line2: String, strict: Boolean = false): OrbitalElements {
        val l1 = line1.trimEnd()
        val l2 = line2.trimEnd()

        if (l1.length < 64) throw TleParseException("Line 1 too short (${l1.length} chars)", l1)
        if (l2.length < 64) throw TleParseException("Line 2 too short (${l2.length} chars)", l2)
        if (l1[0] != '1') throw TleParseException("Line 1 does not start with '1'", l1)
        if (l2[0] != '2') throw TleParseException("Line 2 does not start with '2'", l2)

        if (strict) {
            verifyChecksum(l1)
            verifyChecksum(l2)
        }

        val satNum1 = field(l1, 2, 7).trim()
        val satNum2 = field(l2, 2, 7).trim()
        if (satNum1 != satNum2) {
            throw TleParseException("Line 1 and 2 are for different satellites ($satNum1 / $satNum2)")
        }
        val noradId = parseCatalogNumber(satNum1)

        val epochYear = field(l1, 18, 20).trim().toIntOrNull()
            ?: throw TleParseException("Bad epoch year", l1)
        val epochDay = field(l1, 20, 32).trim().toDoubleOrNull()
            ?: throw TleParseException("Bad epoch day", l1)

        val meanMotionDot = field(l1, 33, 43).trim().toDoubleOrNull()
            ?: throw TleParseException("Bad first derivative of mean motion", l1)
        val meanMotionDdot = parseAssumedDecimal(field(l1, 44, 52))
        val bstar = parseAssumedDecimal(field(l1, 53, 61))
        val elementNumber = field(l1, 64, 68).trim().toIntOrNull() ?: 0
        val classification = l1.getOrNull(7)?.takeUnless { it.isWhitespace() } ?: 'U'
        val internationalDesignator = field(l1, 9, 17).trim()
        val ephemerisType = l1.getOrNull(62)?.digitToIntOrNull() ?: 0

        val inclination = field(l2, 8, 16).trim().toDoubleOrNull()
            ?: throw TleParseException("Bad inclination", l2)
        val raan = field(l2, 17, 25).trim().toDoubleOrNull()
            ?: throw TleParseException("Bad right ascension of ascending node", l2)
        val eccentricity = ("0." + field(l2, 26, 33).trim()).toDoubleOrNull()
            ?: throw TleParseException("Bad eccentricity", l2)
        val argPerigee = field(l2, 34, 42).trim().toDoubleOrNull()
            ?: throw TleParseException("Bad argument of perigee", l2)
        val meanAnomaly = field(l2, 43, 51).trim().toDoubleOrNull()
            ?: throw TleParseException("Bad mean anomaly", l2)
        val meanMotionRevPerDay = field(l2, 52, 63).trim().toDoubleOrNull()
            ?: throw TleParseException("Bad mean motion", l2)
        val revolutionNumber = field(l2, 63, 68).trim().toIntOrNull() ?: 0

        if (eccentricity < 0.0 || eccentricity >= 1.0) {
            throw TleParseException("Eccentricity $eccentricity is not an elliptical orbit", l2)
        }
        if (meanMotionRevPerDay <= 0.0) {
            throw TleParseException("Mean motion $meanMotionRevPerDay is not positive", l2)
        }

        return OrbitalElements(
            noradId = noradId,
            name = name.trim(),
            epoch = AstroTime.fromTleEpoch(epochYear, epochDay),
            epochYear = epochYear,
            epochDayOfYear = epochDay,
            meanMotionDot = meanMotionDot,
            meanMotionDdot = meanMotionDdot,
            bstar = bstar,
            inclinationRad = inclination * Constants.DEG_TO_RAD,
            raanRad = raan * Constants.DEG_TO_RAD,
            eccentricity = eccentricity,
            argPerigeeRad = argPerigee * Constants.DEG_TO_RAD,
            meanAnomalyRad = meanAnomaly * Constants.DEG_TO_RAD,
            meanMotionRadPerMin = meanMotionRevPerDay * Constants.TWO_PI / Constants.MINUTES_PER_DAY,
            revolutionNumber = revolutionNumber,
            elementNumber = elementNumber,
            classification = classification,
            internationalDesignator = internationalDesignator,
            ephemerisType = ephemerisType,
        )
    }

    /**
     * Splits a multi-satellite TLE file.
     *
     * Handles both the 3-line form (name, line 1, line 2) and the bare 2-line form, since the
     * fallback sources in P2 serve a mixture. Unparsable entries are skipped rather than
     * failing the whole file — one bad element set should not cost the user their catalog.
     */
    public fun parseCatalog(text: String, strict: Boolean = false): List<OrbitalElements> {
        val lines = text.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()

        val result = mutableListOf<OrbitalElements>()
        var i = 0
        var pendingName = ""
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("1 ") && i + 1 < lines.size && lines[i + 1].startsWith("2 ") -> {
                    runCatching { parse(pendingName, line, lines[i + 1], strict) }
                        .onSuccess { result += it }
                    pendingName = ""
                    i += 2
                }

                // A "0 NAME" prefix line is the 3le convention; a bare name line is the 3-line one.
                else -> {
                    pendingName = line.removePrefix("0 ").trim()
                    i += 1
                }
            }
        }
        return result
    }

    /**
     * Catalog numbers above 99999 are encoded in "Alpha-5": a letter replaces the first digit,
     * skipping I and O because they read as 1 and 0.
     */
    private fun parseCatalogNumber(raw: String): Int {
        raw.toIntOrNull()?.let { return it }
        val first = raw.firstOrNull()
            ?: throw TleParseException("Empty catalog number")
        if (!first.isLetter()) throw TleParseException("Bad catalog number '$raw'")
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val index = alphabet.indexOf(first.uppercaseChar())
        if (index < 0) throw TleParseException("Bad Alpha-5 catalog number '$raw'")
        val rest = raw.drop(1).trim().toIntOrNull()
            ?: throw TleParseException("Bad Alpha-5 catalog number '$raw'")
        return (index + 10) * 10000 + rest
    }

    /**
     * Reads the exponent-notation fields, where the leading "0." is implied.
     *
     * `" 16079-3"` means 0.16079e-3 and `"-11606-4"` means -0.11606e-4.
     */
    private fun parseAssumedDecimal(raw: String): Double {
        val s = raw.trim()
        if (s.isEmpty()) return 0.0
        val sign = if (s.startsWith("-")) -1.0 else 1.0
        val body = s.removePrefix("-").removePrefix("+")
        if (body.isEmpty()) return 0.0

        // The exponent's sign is the last +/- in the field, not the first character.
        val expIndex = body.indexOfLast { it == '+' || it == '-' }
        val mantissaText: String
        val exponent: Int
        if (expIndex > 0) {
            mantissaText = body.substring(0, expIndex)
            val expText = body.substring(expIndex)
            exponent = expText.toIntOrNull() ?: return 0.0
        } else {
            mantissaText = body
            exponent = 0
        }
        val mantissa = ("0." + mantissaText.trim()).toDoubleOrNull() ?: return 0.0
        var value = mantissa
        repeat(kotlin.math.abs(exponent)) { value = if (exponent < 0) value / 10.0 else value * 10.0 }
        return sign * value
    }

    private fun field(line: String, startInclusive: Int, endExclusive: Int): String =
        if (line.length <= startInclusive) {
            ""
        } else {
            line.substring(startInclusive, minOf(endExclusive, line.length))
        }

    /**
     * The last digit is the sum of all digits in the line modulo 10, with every minus sign
     * counting as 1. Lines that fail this are corrupt.
     */
    private fun verifyChecksum(line: String) {
        if (line.length < 69) return
        val expected = line[68].digitToIntOrNull() ?: return
        var sum = 0
        for (i in 0 until 68) {
            val c = line[i]
            when {
                c.isDigit() -> sum += c.digitToInt()
                c == '-' -> sum += 1
            }
        }
        val actual = sum % 10
        if (actual != expected) {
            throw TleParseException("Checksum mismatch: expected $expected, computed $actual", line)
        }
    }
}
