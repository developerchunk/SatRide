package com.developerstring.satride.core.orbit

import kotlin.math.PI

/**
 * Physical and geodetic constants.
 *
 * The WGS-72 values are not a mistake or an oversight: SGP4 is *defined* against WGS-72, and the
 * TLEs it consumes were fitted with that model. Substituting WGS-84 here would silently degrade
 * propagation accuracy. WGS-84 is used only where it belongs — converting an observer's
 * latitude/longitude to Earth-fixed coordinates.
 */
public object Constants {
    // --- Angles ---
    public const val DEG_TO_RAD: Double = PI / 180.0
    public const val RAD_TO_DEG: Double = 180.0 / PI
    public const val TWO_PI: Double = 2.0 * PI

    // --- WGS-72: the model SGP4 is defined against ---

    /** Earth equatorial radius, km. */
    public const val WGS72_RADIUS_KM: Double = 6378.135

    /** sqrt(GM) in earth-radii^1.5 per minute. */
    public const val WGS72_XKE: Double = 7.43669161331734132e-2

    /** Earth flattening reciprocal. */
    public const val WGS72_FLATTENING: Double = 1.0 / 298.26
    public const val WGS72_J2: Double = 0.001082616
    public const val WGS72_J3: Double = -0.00000253881
    public const val WGS72_J4: Double = -0.00000165597
    public const val WGS72_J3OJ2: Double = WGS72_J3 / WGS72_J2

    // --- WGS-84: the model the observer's position is expressed in ---
    public const val WGS84_SEMI_MAJOR_KM: Double = 6378.137
    public const val WGS84_FLATTENING: Double = 1.0 / 298.257223563
    public const val WGS84_ECCENTRICITY_SQ: Double =
        WGS84_FLATTENING * (2.0 - WGS84_FLATTENING)

    // --- Time ---
    public const val MINUTES_PER_DAY: Double = 1440.0
    public const val SECONDS_PER_DAY: Double = 86400.0
    public const val JULIAN_DATE_J2000: Double = 2451545.0

    /** Earth rotation rate, radians per second (sidereal). */
    public const val EARTH_ROTATION_RAD_S: Double = 7.292115e-5

    // --- Physics ---

    /** Speed of light, km/s — the constant Doppler correction turns on. */
    public const val SPEED_OF_LIGHT_KM_S: Double = 299792.458

    /** Mean solar radius, km, for the eclipse test. */
    public const val SUN_RADIUS_KM: Double = 696000.0

    /** Astronomical unit, km. */
    public const val AU_KM: Double = 149597870.7
}
