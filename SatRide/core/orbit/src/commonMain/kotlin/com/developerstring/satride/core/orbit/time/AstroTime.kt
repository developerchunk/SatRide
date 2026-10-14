package com.developerstring.satride.core.orbit.time

import com.developerstring.satride.core.orbit.Constants
import kotlin.math.floor
import kotlin.time.Instant

/**
 * Time conversions between civil time and the astronomical scales the orbit model works in.
 *
 * SatRide treats UTC and UT1 as the same thing. They differ by at most 0.9 seconds, which moves
 * a LEO satellite's apparent position by well under the compass error the AR view already
 * lives with, and tracking leap seconds would mean shipping a table that expires.
 */
public object AstroTime {

    /** Julian date of [instant]. */
    public fun julianDate(instant: Instant): Double = 2440587.5 + instant.toEpochMilliseconds() / 86400000.0

    /** Julian centuries since J2000.0. */
    public fun julianCenturies(julianDate: Double): Double = (julianDate - Constants.JULIAN_DATE_J2000) / 36525.0

    /** Fractional year (e.g. 2026.5), the input the magnetic field model expects. */
    public fun decimalYear(instant: Instant): Double {
        val jd = julianDate(instant)
        return 2000.0 + (jd - Constants.JULIAN_DATE_J2000) / 365.25
    }

    /**
     * Greenwich Mean Sidereal Time in radians, IAU-82.
     *
     * This is the angle the Earth has turned through, and therefore the single rotation that
     * converts an inertial satellite position into an Earth-fixed one. Getting it wrong shifts
     * every satellite in longitude, so it is checked against published values in the tests.
     */
    public fun gmstRadians(julianDate: Double): Double {
        val tUt1 = julianCenturies(julianDate)
        var temp = -6.2e-6 * tUt1 * tUt1 * tUt1 +
            0.093104 * tUt1 * tUt1 +
            (876600.0 * 3600.0 + 8640184.812866) * tUt1 +
            67310.54841
        // Seconds of time -> radians, then wrapped into [0, 2pi).
        temp = (temp * Constants.DEG_TO_RAD / 240.0) % Constants.TWO_PI
        if (temp < 0.0) temp += Constants.TWO_PI
        return temp
    }

    /** Convenience overload taking an [Instant]. */
    public fun gmstRadians(instant: Instant): Double = gmstRadians(julianDate(instant))

    /**
     * Converts a TLE epoch — two-digit year plus fractional day of year — to an [Instant].
     *
     * The two-digit year is resolved the way the catalog defines it: 57–99 mean 1957–1999
     * (Sputnik onwards), 00–56 mean 2000–2056.
     */
    public fun fromTleEpoch(epochYearTwoDigit: Int, epochDayOfYear: Double): Instant {
        val year = if (epochYearTwoDigit < 57) 2000 + epochYearTwoDigit else 1900 + epochYearTwoDigit
        val jdYearStart = julianDateOfYearStart(year)
        val jd = jdYearStart + epochDayOfYear - 1.0
        return fromJulianDate(jd)
    }

    /**
     * Julian date of January 1.0 of [year].
     *
     * The trailing constant places the result at midnight rather than noon. Getting it wrong by
     * half a day is not a rounding error: it shifts every TLE epoch by twelve hours, which
     * rotates the Earth underneath the satellite by 180 degrees. Checked against the standard
     * `jday` formula in [AstroTimeTest].
     */
    public fun julianDateOfYearStart(year: Int): Double {
        val y = year - 1
        return floor(365.25 * y) + floor(y / 400.0) - floor(y / 100.0) + 1721425.5
    }

    public fun fromJulianDate(julianDate: Double): Instant {
        val millis = ((julianDate - 2440587.5) * 86400000.0)
        return Instant.fromEpochMilliseconds(millis.toLong())
    }
}
