package com.developerstring.satride.core.orbit.sgp4

import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.orbit.Constants
import kotlin.math.abs
import kotlin.math.round

/**
 * Writes [OrbitalElements] back out as two-line element text — the inverse of [TleParser].
 *
 * This exists because CelesTrak's GP JSON endpoint serves *mean elements*, not TLE lines, while
 * every other source (ARISS, AMSAT, SatNOGS 3le, and anything a user pastes) serves text. Rather
 * than carry two internal representations and branch everywhere downstream, JSON sources are
 * rendered into the same canonical text and the app has exactly one thing to store, diff, export
 * and re-parse.
 *
 * The format is fixed-column, so everything here is width- and precision-exact. Correctness is
 * pinned by round-tripping real element sets through parse → format and requiring the bytes back
 * (see `TleFormatterTest`), which is a stronger check than asserting individual fields.
 */
public object TleFormatter {

    /** Renders [elements] as a [Tle], with checksums computed rather than copied. */
    public fun format(elements: OrbitalElements): Tle = Tle(
        name = elements.name,
        noradId = elements.noradId,
        line1 = line1(elements),
        line2 = line2(elements),
        epoch = elements.epoch,
    )

    public fun line1(e: OrbitalElements): String {
        val body = buildString {
            append("1 ")
            append(catalogNumber(e.noradId))
            append(e.classification)
            append(' ')
            append(e.internationalDesignator.padEnd(8).take(8))
            append(' ')
            append(epochField(e.epochYear, e.epochDayOfYear))
            append(' ')
            append(signedDecimalFraction(e.meanMotionDot, 8))
            append(' ')
            append(assumedDecimal(e.meanMotionDdot))
            append(' ')
            append(assumedDecimal(e.bstar))
            append(' ')
            append(e.ephemerisType.toString().take(1))
            append(' ')
            append(e.elementNumber.coerceIn(0, 9999).toString().padStart(4))
        }
        return body + checksumDigit(body)
    }

    public fun line2(e: OrbitalElements): String {
        val meanMotionRevPerDay = e.meanMotionRadPerMin * Constants.MINUTES_PER_DAY / Constants.TWO_PI
        val body = buildString {
            append("2 ")
            append(catalogNumber(e.noradId))
            append(' ')
            append(fixed(degrees(e.inclinationRad), 4).padStart(8))
            append(' ')
            append(fixed(wrapDegrees(degrees(e.raanRad)), 4).padStart(8))
            append(' ')
            append(eccentricityField(e.eccentricity))
            append(' ')
            append(fixed(wrapDegrees(degrees(e.argPerigeeRad)), 4).padStart(8))
            append(' ')
            append(fixed(wrapDegrees(degrees(e.meanAnomalyRad)), 4).padStart(8))
            append(' ')
            append(fixed(meanMotionRevPerDay, 8).padStart(11))
            append(e.revolutionNumber.coerceIn(0, 99999).toString().padStart(5))
        }
        return body + checksumDigit(body)
    }

    /**
     * The last character of each line: digits summed modulo ten, with a minus sign counting as
     * one. Recomputed rather than carried over, because every other field may have been rounded
     * on the way through and a stale checksum is worse than none.
     */
    public fun checksumDigit(lineWithoutChecksum: String): Char {
        var sum = 0
        for (c in lineWithoutChecksum) {
            when {
                c.isDigit() -> sum += c.digitToInt()
                c == '-' -> sum += 1
            }
        }
        return '0' + (sum % 10)
    }

    /** Catalog numbers past 99999 use Alpha-5: a letter stands in for the leading digit. */
    private fun catalogNumber(noradId: Int): String {
        if (noradId in 0..99999) return noradId.toString().padStart(5, '0')
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val index = noradId / 10000 - 10
        if (index !in alphabet.indices) return noradId.toString().takeLast(5)
        return alphabet[index] + (noradId % 10000).toString().padStart(4, '0')
    }

    /** `YYDDD.DDDDDDDD` — two-digit year then day of year with eight decimals. */
    private fun epochField(epochYear: Int, epochDayOfYear: Double): String {
        val year = ((epochYear % 100) + 100) % 100
        val scaled = round(epochDayOfYear * 1e8).toLong()
        val whole = scaled / 100_000_000L
        val fraction = scaled % 100_000_000L
        return year.toString().padStart(2, '0') +
            whole.toString().padStart(3, '0') +
            "." +
            fraction.toString().padStart(8, '0')
    }

    /**
     * The first-derivative field: a sign, then a bare decimal point, then digits — the leading
     * zero is dropped, so 0.00007322 is written ` .00007322`.
     */
    private fun signedDecimalFraction(value: Double, decimals: Int): String {
        var scale = 1L
        repeat(decimals) { scale *= 10L }
        val magnitude = round(abs(value) * scale).toLong().coerceAtMost(scale - 1)
        val sign = if (value < 0.0) '-' else ' '
        return "$sign.${magnitude.toString().padStart(decimals, '0')}"
    }

    /**
     * The exponent-notation fields (B*, second derivative), where the mantissa's leading `0.`
     * is implied: 0.00013940611 is written ` 13941-3`, meaning 0.13941 × 10⁻³.
     *
     * A zero exponent has no meaningful sign and real catalogs spell it both ways — the AIAA
     * verification file contains `00000+0` and `00000-0` in the same element set, and `13519-0`
     * for a value of 0.13519. We always write `-0`, which is what CelesTrak emits.
     */
    private fun assumedDecimal(value: Double): String {
        val sign = if (value < 0.0) '-' else ' '
        var magnitude = abs(value)
        if (magnitude == 0.0 || !magnitude.isFinite()) return "${sign}00000-0"

        var exponent = 0
        while (magnitude >= 1.0) {
            magnitude /= 10.0
            exponent++
        }
        while (magnitude < 0.1) {
            magnitude *= 10.0
            exponent--
        }
        var mantissa = round(magnitude * 100_000.0).toLong()
        // Rounding 0.99999… up carries into a sixth digit; renormalise rather than emit it.
        if (mantissa >= 100_000L) {
            mantissa /= 10L
            exponent++
        }
        val exponentSign = if (exponent <= 0) '-' else '+'
        return "$sign${mantissa.toString().padStart(5, '0')}$exponentSign${abs(exponent)}"
    }

    /** Seven digits with the leading `0.` implied: 0.00071404 becomes `0007140`. */
    private fun eccentricityField(eccentricity: Double): String {
        val digits = round(eccentricity.coerceIn(0.0, 0.9999999) * 1e7).toLong()
        return digits.toString().padStart(7, '0')
    }

    private fun degrees(radians: Double): Double = radians / Constants.DEG_TO_RAD

    private fun wrapDegrees(deg: Double): Double = deg.mod(360.0)

    /** Fixed-point rendering; Kotlin common has no `String.format`. */
    private fun fixed(value: Double, decimals: Int): String {
        var scale = 1L
        repeat(decimals) { scale *= 10L }
        val negative = value < 0.0
        val scaled = round(abs(value) * scale).toLong()
        val whole = scaled / scale
        val fraction = scaled % scale
        val sign = if (negative && scaled != 0L) "-" else ""
        return "$sign$whole.${fraction.toString().padStart(decimals, '0')}"
    }
}
