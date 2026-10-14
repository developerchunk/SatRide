package com.developerstring.satride.core.orbit.frames

import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.time.AstroTime
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Where the Sun is directly overhead.
 *
 * The Play globe's Real mode claims the terminator is where it actually is. That claim rests on two
 * things — the solar ephemeris and the GMST rotation — and it is exactly the kind of claim that can
 * be plausibly wrong forever: a globe lit from *roughly* the right direction looks completely
 * convincing, and a sign error or a half-day offset in GMST would still produce a picture with a
 * day side and a night side.
 *
 * So these check the subsolar point against facts anyone can look up: its latitude tracks the
 * seasons between the tropics, and its longitude is wherever local solar noon currently is.
 */
class SubsolarPointTest {

    /**
     * The subsolar point, in degrees.
     *
     * The same transformation the renderer does: the Sun in TEME, rotated into the Earth-fixed
     * frame by GMST, then read as a latitude and longitude.
     */
    private fun subsolar(time: Instant): Pair<Double, Double> {
        val sun = Frames.sunPositionTeme(time).normalized()
        val gmst = AstroTime.gmstRadians(time)
        val x = cos(gmst) * sun.x + sin(gmst) * sun.y
        val y = -sin(gmst) * sun.x + cos(gmst) * sun.y
        val z = sun.z
        val latDeg = asin(z) * Constants.RAD_TO_DEG
        val lonDeg = atan2(y, x) * Constants.RAD_TO_DEG
        return latDeg to lonDeg
    }

    @Test
    fun atTheMarchEquinoxTheSunIsOverTheEquator() {
        // 2026-03-20 14:46 UTC is the equinox. The subsolar latitude passes through zero there.
        val (lat, _) = subsolar(Instant.parse("2026-03-20T14:46:00Z"))
        assertTrue(abs(lat) < 0.5, "equinox subsolar latitude should be ~0°, was $lat")
    }

    @Test
    fun atTheJuneSolsticeTheSunIsOverTheTropicOfCancer() {
        // 2026-06-21 08:24 UTC. The Sun reaches its northern limit, the obliquity: +23.44°.
        val (lat, _) = subsolar(Instant.parse("2026-06-21T08:24:00Z"))
        assertTrue(abs(lat - 23.44) < 0.3, "June solstice subsolar latitude should be ~+23.44°, was $lat")
    }

    @Test
    fun atTheDecemberSolsticeTheSunIsOverTheTropicOfCapricorn() {
        val (lat, _) = subsolar(Instant.parse("2026-12-21T20:50:00Z"))
        assertTrue(abs(lat + 23.44) < 0.3, "December solstice subsolar latitude should be ~-23.44°, was $lat")
    }

    @Test
    fun theSubsolarLatitudeNeverLeavesTheTropics() {
        // Sampled across a whole year: the Sun is never overhead outside ±23.44°, and a sign or
        // obliquity error would show up here even if a single date happened to look right.
        var day = 0
        while (day < 365) {
            val (lat, _) = subsolar(Instant.parse("2026-01-01T00:00:00Z") + kotlin.time.Duration.parse("${day}d"))
            assertTrue(abs(lat) <= 23.5, "day $day put the Sun overhead at $lat°, outside the tropics")
            day += 1
        }
    }

    @Test
    fun theSubsolarLongitudeIsWhereLocalNoonIs() {
        // At 12:00 UTC the Sun is roughly over the prime meridian — offset only by the equation of
        // time, which never exceeds about 16 minutes, or 4° of longitude.
        val (_, lon) = subsolar(Instant.parse("2026-06-15T12:00:00Z"))
        assertTrue(abs(lon) < 4.5, "at 12:00 UTC the Sun should be near 0° longitude, was $lon")
    }

    @Test
    fun theSubsolarPointTravelsWestAtFifteenDegreesAnHour() {
        // The Earth turns once a day, so the subsolar point moves 15° of longitude per hour. This
        // is the check that catches a GMST rate error — the thing that would make the globe spin
        // at the wrong speed while still looking entirely plausible.
        val start = Instant.parse("2026-05-05T03:00:00Z")
        val (_, lon0) = subsolar(start)
        val (_, lon1) = subsolar(start + kotlin.time.Duration.parse("1h"))

        var delta = lon0 - lon1
        if (delta < -180.0) delta += 360.0
        if (delta > 180.0) delta -= 360.0

        assertTrue(abs(delta - 15.0) < 0.3, "expected ~15° of westward travel per hour, got $delta")
    }

    @Test
    fun aFullDayBringsTheSubsolarPointBackAround() {
        val start = Instant.parse("2026-09-09T06:00:00Z")
        val (_, lon0) = subsolar(start)
        val (_, lon1) = subsolar(start + kotlin.time.Duration.parse("1d"))

        var delta = abs(lon0 - lon1)
        if (delta > 180.0) delta = 360.0 - delta
        // Not exactly zero: the Sun also moves ~1°/day along the ecliptic, which is the difference
        // between a solar and a sidereal day.
        assertTrue(delta < 1.5, "a day should return the Sun to nearly the same longitude, off by $delta°")
    }
}
