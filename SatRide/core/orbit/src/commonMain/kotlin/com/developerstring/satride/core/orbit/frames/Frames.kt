package com.developerstring.satride.core.orbit.frames

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.Topocentric
import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.math.Vector3
import com.developerstring.satride.core.orbit.sgp4.TemeState
import com.developerstring.satride.core.orbit.time.AstroTime
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Instant

/**
 * The chain that turns "where the satellite is" into "where the user should look".
 *
 * ```
 * SGP4 -> TEME --(GMST rotation)--> ECEF --(observer offset)--> ENU -> azimuth / elevation
 * ```
 *
 * Polar motion is deliberately ignored in the TEME→ECEF step. It amounts to a few tenths of an
 * arcsecond — roughly 1e-4 degrees — against a compass that is doing well to be within 3
 * degrees, and tracking it would mean shipping Earth-orientation parameters that go stale.
 */
public object Frames {

    /**
     * Rotates an inertial TEME vector into the Earth-fixed frame.
     *
     * [gmstRad] is the angle the Earth has turned through; this single rotation is what makes a
     * satellite appear over a particular place rather than a particular direction in space.
     */
    public fun temeToEcef(teme: Vector3, gmstRad: Double): Vector3 = teme.rotateZ(gmstRad)

    /**
     * Rotates a TEME velocity into the Earth-fixed frame.
     *
     * The extra term is the Earth's own rotation: a satellite that is stationary in inertial
     * space is still moving over the ground.
     */
    public fun temeVelocityToEcef(temePosition: Vector3, temeVelocity: Vector3, gmstRad: Double): Vector3 {
        val rotated = temeVelocity.rotateZ(gmstRad)
        val posEcef = temePosition.rotateZ(gmstRad)
        val omega = Constants.EARTH_ROTATION_RAD_S
        return Vector3(
            rotated.x + omega * posEcef.y,
            rotated.y - omega * posEcef.x,
            rotated.z,
        )
    }

    /** Geodetic position (WGS-84) to Earth-fixed cartesian, km. */
    public fun geodeticToEcef(pos: GeoPos): Vector3 {
        val lat = pos.latDeg * Constants.DEG_TO_RAD
        val lon = pos.lonDeg * Constants.DEG_TO_RAD
        val altKm = pos.altM / 1000.0
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        // Radius of curvature in the prime vertical.
        val n = Constants.WGS84_SEMI_MAJOR_KM /
            sqrt(1.0 - Constants.WGS84_ECCENTRICITY_SQ * sinLat * sinLat)
        return Vector3(
            (n + altKm) * cosLat * cos(lon),
            (n + altKm) * cosLat * sin(lon),
            (n * (1.0 - Constants.WGS84_ECCENTRICITY_SQ) + altKm) * sinLat,
        )
    }

    /**
     * Earth-fixed cartesian back to geodetic, km in and degrees/metres out.
     *
     * Uses the standard iterative latitude solution; it converges in a handful of passes for
     * any altitude a satellite occupies.
     */
    public fun ecefToGeodetic(ecef: Vector3): GeoPos {
        val a = Constants.WGS84_SEMI_MAJOR_KM
        val e2 = Constants.WGS84_ECCENTRICITY_SQ
        val lon = atan2(ecef.y, ecef.x)
        val p = sqrt(ecef.x * ecef.x + ecef.y * ecef.y)

        var lat = atan2(ecef.z, p * (1.0 - e2))
        var n: Double
        var altKm: Double
        var iterations = 0
        do {
            val sinLat = sin(lat)
            n = a / sqrt(1.0 - e2 * sinLat * sinLat)
            altKm = p / cos(lat) - n
            val newLat = atan2(ecef.z, p * (1.0 - e2 * n / (n + altKm)))
            val converged = abs(newLat - lat) < 1e-12
            lat = newLat
            iterations++
        } while (!converged && iterations < 10)

        return GeoPos(
            latDeg = lat * Constants.RAD_TO_DEG,
            lonDeg = normalizeLongitude(lon * Constants.RAD_TO_DEG),
            altM = altKm * 1000.0,
        )
    }

    /**
     * Where a satellite appears from an observer: azimuth, elevation, range and range rate.
     *
     * The range rate is analytic — the projection of the relative velocity onto the line of
     * sight — rather than a finite difference of two ranges. That matters because Doppler
     * correction consumes it directly, and differencing would inject numerical noise straight
     * into the tuned frequency.
     */
    public fun ecefToTopocentric(satEcef: Vector3, satVelEcef: Vector3, observer: GeoPos): Topocentric {
        val obsEcef = geodeticToEcef(observer)
        val range = satEcef - obsEcef

        val lat = observer.latDeg * Constants.DEG_TO_RAD
        val lon = observer.lonDeg * Constants.DEG_TO_RAD
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val sinLon = sin(lon)
        val cosLon = cos(lon)

        // Rotate the range vector into the observer's east/north/up frame.
        val east = -sinLon * range.x + cosLon * range.y
        val north = -sinLat * cosLon * range.x - sinLat * sinLon * range.y + cosLat * range.z
        val up = cosLat * cosLon * range.x + cosLat * sinLon * range.y + sinLat * range.z

        val rangeKm = range.magnitude
        var azimuth = atan2(east, north) * Constants.RAD_TO_DEG
        if (azimuth < 0.0) azimuth += 360.0
        val elevation = asin((up / rangeKm).coerceIn(-1.0, 1.0)) * Constants.RAD_TO_DEG

        // The observer is fixed in this frame, so the satellite's ECEF velocity is already the
        // relative velocity.
        val rangeRate = if (rangeKm == 0.0) 0.0 else (range dot satVelEcef) / rangeKm

        return Topocentric(
            azimuthDeg = azimuth,
            elevationDeg = elevation,
            rangeKm = rangeKm,
            rangeRateKmS = rangeRate,
        )
    }

    /** One-shot TEME → topocentric for a propagated state. */
    public fun topocentric(state: TemeState, observer: GeoPos): Topocentric {
        val gmst = AstroTime.gmstRadians(state.time)
        val ecef = temeToEcef(state.position, gmst)
        val velEcef = temeVelocityToEcef(state.position, state.velocity, gmst)
        return ecefToTopocentric(ecef, velEcef, observer)
    }

    /** The point on the Earth directly beneath the satellite, with its height. */
    public fun subPoint(state: TemeState): GeoPos {
        val gmst = AstroTime.gmstRadians(state.time)
        return ecefToGeodetic(temeToEcef(state.position, gmst))
    }

    /**
     * Radius of the circle on the ground that has the satellite above its horizon, km.
     *
     * This is the "footprint" drawn on a map: everyone inside it could, in principle, hear the
     * satellite.
     */
    public fun footprintRadiusKm(satelliteAltitudeKm: Double): Double {
        val re = Constants.WGS84_SEMI_MAJOR_KM
        val ratio = (re / (re + satelliteAltitudeKm)).coerceIn(-1.0, 1.0)
        return re * kotlin.math.acos(ratio)
    }

    /**
     * Whether the satellite is in sunlight, and therefore possibly visible to the naked eye.
     *
     * A cylindrical shadow model: it ignores the penumbra and the Earth's oblateness, which
     * shifts the terminator crossing by a few seconds. That is immaterial for a badge that
     * tells a user whether it is worth looking up.
     */
    public fun isSunlit(satTeme: Vector3, time: Instant): Boolean {
        val sun = sunPositionTeme(time)
        val sunUnit = sun.normalized()
        val alongSun = satTeme dot sunUnit
        if (alongSun > 0.0) return true // Sun-facing side of the Earth: always lit.
        val perpendicular = (satTeme - sunUnit * alongSun).magnitude
        return perpendicular > Constants.WGS84_SEMI_MAJOR_KM
    }

    /**
     * Low-precision solar position in TEME, km.
     *
     * Accurate to roughly 0.01 degrees, which is far more than the eclipse test needs and far
     * less than a full ephemeris would cost.
     */
    public fun sunPositionTeme(time: Instant): Vector3 {
        val jd = AstroTime.julianDate(time)
        val n = jd - Constants.JULIAN_DATE_J2000
        // Mean longitude and mean anomaly of the Sun, degrees.
        val meanLongitude = (280.460 + 0.9856474 * n).mod(360.0)
        val meanAnomaly = ((357.528 + 0.9856003 * n).mod(360.0)) * Constants.DEG_TO_RAD
        val eclipticLongitude = (
            meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2.0 * meanAnomaly)
            ) * Constants.DEG_TO_RAD
        val obliquity = (23.439 - 0.0000004 * n) * Constants.DEG_TO_RAD
        val distanceKm = (
            1.00014 - 0.01671 * cos(meanAnomaly) - 0.00014 * cos(2.0 * meanAnomaly)
            ) * Constants.AU_KM
        return Vector3(
            distanceKm * cos(eclipticLongitude),
            distanceKm * cos(obliquity) * sin(eclipticLongitude),
            distanceKm * sin(obliquity) * sin(eclipticLongitude),
        )
    }

    /** Wraps a longitude into (-180, 180]. */
    public fun normalizeLongitude(degrees: Double): Double {
        var d = degrees % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }
}
