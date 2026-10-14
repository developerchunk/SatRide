package com.developerstring.satride.core.orbit.sgp4

import kotlin.time.Instant

/**
 * Mean orbital elements as they appear in a two-line element set.
 *
 * These are *mean* elements in the SGP4 sense — they are only meaningful when fed to the same
 * model that produced them. Reading them as osculating Keplerian elements gives wrong answers.
 *
 * Angles are radians and [meanMotionRadPerMin] is radians per minute, matching what the
 * propagator consumes, so the conversion happens once at parse time.
 */
public data class OrbitalElements(
    public val noradId: Int,
    public val name: String,
    public val epoch: Instant,
    public val epochYear: Int,
    public val epochDayOfYear: Double,
    /** First derivative of mean motion divided by two, rev/day². */
    public val meanMotionDot: Double,
    /** Second derivative of mean motion divided by six, rev/day³. */
    public val meanMotionDdot: Double,
    /** Radiation-pressure/drag coefficient, 1/earth-radii. */
    public val bstar: Double,
    public val inclinationRad: Double,
    public val raanRad: Double,
    public val eccentricity: Double,
    public val argPerigeeRad: Double,
    public val meanAnomalyRad: Double,
    public val meanMotionRadPerMin: Double,
    public val revolutionNumber: Int = 0,
    public val elementNumber: Int = 0,
    /**
     * Security classification, 'U' for the unclassified sets we work with.
     *
     * This and the two fields below carry no orbital information and the propagator ignores
     * them. They are kept because [TleFormatter] has to reproduce the line exactly, and because
     * the international designator is the only field that says when the object was launched.
     */
    public val classification: Char = 'U',
    /** Launch designator, TLE form: two-digit year, launch number, piece — e.g. `98067A`. */
    public val internationalDesignator: String = "",
    /** Always 0 in public element sets; other values indicate a different orbital model. */
    public val ephemerisType: Int = 0,
)

/** Raised when a TLE cannot be parsed or describes an orbit the propagator cannot handle. */
public class TleParseException(message: String, public val line: String? = null) : IllegalArgumentException(message)

/**
 * Raised when propagation fails.
 *
 * [code] follows the SGP4 reference implementation's error numbering so failures can be
 * matched against the published behaviour:
 * 1 mean elements out of range, 2 mean motion below zero, 3 eccentricity out of range,
 * 4 semi-latus rectum below zero, 6 satellite has decayed.
 */
public class PropagationException(message: String, public val code: Int, public val noradId: Int) :
    IllegalStateException(message)
