package com.developerstring.satride.feature.play.solar

import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.time.AstroTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Instant

/** What sort of thing a [Body] is; drives how it is drawn and what visiting it is worth. */
enum class BodyKind { STAR, PLANET, MOON, DWARF }

/**
 * The bodies of the solar system the game knows about, with the handful of physical facts the
 * renderer and the flight model need.
 *
 * Radii are real (km). Pole directions are the IAU north poles in equatorial J2000, which is
 * what puts Saturn's rings and Uranus's sideways spin at the right angle. Colours are the
 * flat-vector palette, not photometry: a planet here should read at a glance, from a dot to
 * a disc filling the screen.
 */
@Suppress("LongParameterList")
enum class Body(
    val id: String,
    val label: String,
    val glyph: String,
    val kind: BodyKind,
    val radiusKm: Double,
    val colorArgb: Long,
    val poleRaDeg: Double,
    val poleDecDeg: Double,
    /** Sidereal rotation period in hours; negative spins retrograde. */
    val rotationHours: Double,
    val atmosphereArgb: Long,
    val fact: String,
) {
    SUN(
        "sun", "Sun", "☉", BodyKind.STAR, Constants.SUN_RADIUS_KM, 0xFFFFD04D,
        286.13, 63.87, 609.12, 0xFFFFA630,
        "A million Earths would fit inside. Do not park too close.",
    ),
    MERCURY(
        "mercury", "Mercury", "☿", BodyKind.PLANET, 2439.7, 0xFFB9AFA3,
        281.01, 61.45, 1407.6, 0L,
        "A year here is 88 days, and a day is longer than a year.",
    ),
    VENUS(
        "venus", "Venus", "♀", BodyKind.PLANET, 6051.8, 0xFFF3D9A4,
        272.76, 67.16, -5832.5, 0xFFFFE6B8,
        "Spins backwards under clouds of sulphuric acid. 460 °C on the ground.",
    ),
    EARTH(
        "earth", "Earth", "🌍", BodyKind.PLANET, 6371.0, 0xFF2E77E0,
        0.0, 90.0, 23.9345, 0xFF8FD3FF,
        "Home. Every satellite you track lives in the thin shell around it.",
    ),
    MOON(
        "moon", "Moon", "☾", BodyKind.MOON, 1737.4, 0xFFC9CDD6,
        266.86, 65.64, 655.7, 0L,
        "1.3 light-seconds from Earth. The only other world with footprints.",
    ),
    MARS(
        "mars", "Mars", "♂", BodyKind.PLANET, 3389.5, 0xFFE0714A,
        317.68, 52.89, 24.6229, 0xFFFFB38A,
        "Half Earth's size, with the tallest volcano in the solar system.",
    ),
    JUPITER(
        "jupiter", "Jupiter", "♃", BodyKind.PLANET, 69911.0, 0xFFE3B98A,
        268.06, 64.50, 9.925, 0xFFFFDDB0,
        "Eleven Earths across. The red spot is a storm older than any nation.",
    ),
    SATURN(
        "saturn", "Saturn", "♄", BodyKind.PLANET, 58232.0, 0xFFF0D9A6,
        40.59, 83.54, 10.656, 0xFFFFEBC2,
        "The rings are mostly water ice, tens of metres thick, 280,000 km wide.",
    ),
    URANUS(
        "uranus", "Uranus", "⛢", BodyKind.PLANET, 25362.0, 0xFF9FE3EA,
        257.31, -15.18, -17.24, 0xFFC8F4F8,
        "Rolls around the Sun on its side, one pole in sunlight for 42 years.",
    ),
    NEPTUNE(
        "neptune", "Neptune", "♆", BodyKind.PLANET, 24622.0, 0xFF3F6AE0,
        299.36, 43.46, 16.11, 0xFF8FA8FF,
        "Four light-hours out. Winds here reach 2,000 km/h.",
    ),
    PLUTO(
        "pluto", "Pluto", "♇", BodyKind.DWARF, 1188.3, 0xFFD9B99B,
        132.99, -6.16, -153.29, 0L,
        "A dwarf planet with a heart-shaped glacier of nitrogen ice.",
    ),
    ;

    val isPlanet: Boolean get() = kind == BodyKind.PLANET

    /** A comfortable parking distance from the centre: the body fills about a third of the frame. */
    val parkRadiusKm: Double get() = radiusKm * PARK_FACTOR

    companion object {
        const val PARK_FACTOR: Double = 3.2

        fun fromId(id: String?): Body? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Approximate positions of the planets and the Moon.
 *
 * Planets use the JPL "Keplerian elements for approximate positions" table (Standish), valid
 * 1800–2050 to within arcminutes — hugely better than the game needs, since at these distances
 * a planet's dot is where it is to the pixel. The Moon uses the Astronomical Almanac's
 * low-precision series (about 0.3°). Everything is returned heliocentric, **equatorial J2000**,
 * in kilometres, which is the frame the game world lives in: the same axes as the globe's
 * inertial frame, so a GMST rotation is all it takes to draw the real Earth from it.
 *
 * Allocation-free: every result is written into a caller-supplied `DoubleArray(3)`.
 */
object Ephemeris {

    /** Mean obliquity of the ecliptic at J2000, the tilt between the ecliptic and equatorial frames. */
    const val OBLIQUITY_DEG: Double = 23.43928

    private const val DEG = PI / 180.0
    private val cosEps = cos(OBLIQUITY_DEG * DEG)
    private val sinEps = sin(OBLIQUITY_DEG * DEG)

    /**
     * Standish elements at J2000 and their rates per Julian century:
     * a (au), e, I (deg), L (deg), longitude of perihelion (deg), longitude of ascending node (deg).
     */
    private class Elements(
        val a0: Double,
        val e0: Double,
        val i0: Double,
        val l0: Double,
        val w0: Double,
        val o0: Double,
        val aDot: Double,
        val eDot: Double,
        val iDot: Double,
        val lDot: Double,
        val wDot: Double,
        val oDot: Double,
    )

    private val elementsFor: Map<Body, Elements> = mapOf(
        Body.MERCURY to Elements(
            0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593,
            0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081,
        ),
        Body.VENUS to Elements(
            0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255,
            0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418,
        ),
        // The Earth–Moon barycentre; the Earth itself is within 4,700 km of it, invisible here.
        Body.EARTH to Elements(
            1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0,
            0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0,
        ),
        Body.MARS to Elements(
            1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891,
            0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343,
        ),
        Body.JUPITER to Elements(
            5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909,
            -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106,
        ),
        Body.SATURN to Elements(
            9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448,
            -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794,
        ),
        Body.URANUS to Elements(
            19.18916464, 0.04725744, 0.77263783, 313.23810451, 170.95427630, 74.01692503,
            -0.00196176, -0.00004397, -0.00242939, 428.48202785, 0.40805281, 0.04240589,
        ),
        Body.NEPTUNE to Elements(
            30.06992276, 0.00859048, 1.77004347, -55.12002969, 44.96476227, 131.78422574,
            0.00026291, 0.00005105, 0.00035372, 218.45945325, -0.32241464, -0.00508664,
        ),
        Body.PLUTO to Elements(
            39.48211675, 0.24882730, 17.14001206, 238.92903833, 224.06891629, 110.30393684,
            -0.00031596, 0.00005170, 0.00004818, 145.20780515, -0.04062942, -0.01183482,
        ),
    )

    fun julianDate(time: Instant): Double = AstroTime.julianDate(time)

    /** Heliocentric equatorial J2000 position of [body] at Julian date [jd], km, into [out]. */
    fun position(body: Body, jd: Double, out: DoubleArray) {
        when (body) {
            Body.SUN -> {
                out[0] = 0.0
                out[1] = 0.0
                out[2] = 0.0
            }

            Body.MOON -> {
                position(Body.EARTH, jd, out)
                val ex = out[0]
                val ey = out[1]
                val ez = out[2]
                moonGeocentricEquatorial(jd, out)
                out[0] += ex
                out[1] += ey
                out[2] += ez
            }

            else -> {
                val t = (jd - Constants.JULIAN_DATE_J2000) / 36525.0
                val el = elementsFor.getValue(body)
                val a = el.a0 + el.aDot * t
                val e = el.e0 + el.eDot * t
                val inc = el.i0 + el.iDot * t
                val l = el.l0 + el.lDot * t
                val wBar = el.w0 + el.wDot * t
                val node = el.o0 + el.oDot * t
                val m = wrapDeg(l - wBar)
                positionFromElements(a, e, inc, wBar - node, node, solveKepler(m * DEG, e), out)
            }
        }
    }

    /**
     * A point on [body]'s orbit at eccentric anomaly [eccentricAnomalyRad], using the elements of
     * [jd]: the path the planet follows this year, for drawing the orbit as a line. The Moon's
     * path is sampled around the Earth's *current* position over one lunar month.
     */
    fun orbitPoint(body: Body, jd: Double, eccentricAnomalyRad: Double, out: DoubleArray) {
        when (body) {
            Body.SUN -> position(Body.SUN, jd, out)

            Body.MOON -> {
                position(Body.EARTH, jd, out)
                val ex = out[0]
                val ey = out[1]
                val ez = out[2]
                val phase = eccentricAnomalyRad / (2.0 * PI)
                moonGeocentricEquatorial(jd + phase * LUNAR_MONTH_DAYS, out)
                out[0] += ex
                out[1] += ey
                out[2] += ez
            }

            else -> {
                val t = (jd - Constants.JULIAN_DATE_J2000) / 36525.0
                val el = elementsFor.getValue(body)
                val a = el.a0 + el.aDot * t
                val e = el.e0 + el.eDot * t
                val inc = el.i0 + el.iDot * t
                val wBar = el.w0 + el.wDot * t
                val node = el.o0 + el.oDot * t
                positionFromElements(a, e, inc, wBar - node, node, eccentricAnomalyRad, out)
            }
        }
    }

    /** Unit north-pole direction of [body] in the equatorial frame, into [out]. */
    fun poleAxis(body: Body, out: DoubleArray) {
        val ra = body.poleRaDeg * DEG
        val dec = body.poleDecDeg * DEG
        out[0] = cos(dec) * cos(ra)
        out[1] = cos(dec) * sin(ra)
        out[2] = sin(dec)
    }

    /**
     * Rotation of [body] about its pole at [jd], radians. The zero point is arbitrary for every
     * body except the Earth, whose surface is drawn by the globe renderer from GMST instead.
     */
    fun spinAngle(body: Body, jd: Double): Double {
        val days = jd - Constants.JULIAN_DATE_J2000
        val turns = days * 24.0 / body.rotationHours
        return (turns - kotlin.math.floor(turns)) * 2.0 * PI
    }

    /** Heliocentric ecliptic longitude of an equatorial position, degrees in [0, 360). */
    fun eclipticLongitudeDeg(x: Double, y: Double, z: Double): Double {
        val yEcl = cosEps * y + sinEps * z
        return ((atan2(yEcl, x) / DEG) + 360.0) % 360.0
    }

    /** Distance of an equatorial position from the ecliptic plane, km, north positive. */
    fun eclipticZ(y: Double, z: Double): Double = -sinEps * y + cosEps * z

    // --- Internals -----------------------------------------------------------------------------

    private fun wrapDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /** Newton's method on Kepler's equation; converges in a handful of steps for e < 0.3. */
    private fun solveKepler(meanAnomalyRad: Double, e: Double): Double {
        var ea = meanAnomalyRad + e * sin(meanAnomalyRad)
        repeat(12) {
            val dm = meanAnomalyRad - (ea - e * sin(ea))
            val de = dm / (1.0 - e * cos(ea))
            ea += de
            if (abs(de) < 1e-10) return ea
        }
        return ea
    }

    @Suppress("LongParameterList")
    private fun positionFromElements(
        aAu: Double,
        e: Double,
        incDeg: Double,
        argPeriDeg: Double,
        nodeDeg: Double,
        eccentricAnomalyRad: Double,
        out: DoubleArray,
    ) {
        val xp = aAu * (cos(eccentricAnomalyRad) - e)
        val yp = aAu * sqrt((1.0 - e * e).coerceAtLeast(0.0)) * sin(eccentricAnomalyRad)
        val w = argPeriDeg * DEG
        val o = nodeDeg * DEG
        val i = incDeg * DEG
        val cw = cos(w)
        val sw = sin(w)
        val co = cos(o)
        val so = sin(o)
        val ci = cos(i)
        val si = sin(i)
        val xEcl = (cw * co - sw * so * ci) * xp + (-sw * co - cw * so * ci) * yp
        val yEcl = (cw * so + sw * co * ci) * xp + (-sw * so + cw * co * ci) * yp
        val zEcl = (sw * si) * xp + (cw * si) * yp
        eclipticToEquatorial(xEcl * Constants.AU_KM, yEcl * Constants.AU_KM, zEcl * Constants.AU_KM, out)
    }

    private fun eclipticToEquatorial(x: Double, y: Double, z: Double, out: DoubleArray) {
        out[0] = x
        out[1] = cosEps * y - sinEps * z
        out[2] = sinEps * y + cosEps * z
    }

    /** Astronomical Almanac low-precision Moon: geocentric equatorial km into [out]. */
    private fun moonGeocentricEquatorial(jd: Double, out: DoubleArray) {
        val t = (jd - Constants.JULIAN_DATE_J2000) / 36525.0
        val lambda = 218.32 + 481267.881 * t +
            6.29 * sinDeg(135.0 + 477198.87 * t) -
            1.27 * sinDeg(259.3 - 413335.36 * t) +
            0.66 * sinDeg(235.7 + 890534.22 * t) +
            0.21 * sinDeg(269.9 + 954397.74 * t) -
            0.19 * sinDeg(357.5 + 35999.05 * t) -
            0.11 * sinDeg(186.5 + 966404.03 * t)
        val beta = 5.13 * sinDeg(93.3 + 483202.02 * t) +
            0.28 * sinDeg(228.2 + 960400.89 * t) -
            0.28 * sinDeg(318.3 + 6003.15 * t) -
            0.17 * sinDeg(217.6 - 407332.21 * t)
        val parallax = 0.9508 +
            0.0518 * cosDeg(135.0 + 477198.87 * t) +
            0.0095 * cosDeg(259.3 - 413335.36 * t) +
            0.0078 * cosDeg(235.7 + 890534.22 * t) +
            0.0028 * cosDeg(269.9 + 954397.74 * t)
        val r = 6378.14 / sin(parallax * DEG)
        val l = lambda * DEG
        val b = beta * DEG
        val xEcl = r * cos(b) * cos(l)
        val yEcl = r * cos(b) * sin(l)
        val zEcl = r * sin(b)
        eclipticToEquatorial(xEcl, yEcl, zEcl, out)
    }

    private fun sinDeg(d: Double): Double = sin(d * DEG)

    private fun cosDeg(d: Double): Double = cos(d * DEG)

    private const val LUNAR_MONTH_DAYS = 27.321661
}

/**
 * A snapshot of where everything is, refreshed once per tick and read by physics and rendering.
 *
 * Positions are packed `x, y, z` per [Body] in `Body.entries` order, heliocentric equatorial km.
 */
class SolarWorld {
    val bodies: List<Body> = Body.entries
    val pos: DoubleArray = DoubleArray(bodies.size * 3)
    val pole: DoubleArray = DoubleArray(bodies.size * 3)
    val spin: DoubleArray = DoubleArray(bodies.size)
    var jd: Double = Constants.JULIAN_DATE_J2000
        private set

    private val scratch = DoubleArray(3)

    init {
        bodies.forEachIndexed { i, body ->
            Ephemeris.poleAxis(body, scratch)
            pole[i * 3] = scratch[0]
            pole[i * 3 + 1] = scratch[1]
            pole[i * 3 + 2] = scratch[2]
        }
    }

    fun update(time: Instant) = update(Ephemeris.julianDate(time))

    fun update(julianDate: Double) {
        jd = julianDate
        bodies.forEachIndexed { i, body ->
            Ephemeris.position(body, jd, scratch)
            pos[i * 3] = scratch[0]
            pos[i * 3 + 1] = scratch[1]
            pos[i * 3 + 2] = scratch[2]
            spin[i] = Ephemeris.spinAngle(body, jd)
        }
    }

    fun x(body: Body): Double = pos[body.ordinal * 3]

    fun y(body: Body): Double = pos[body.ordinal * 3 + 1]

    fun z(body: Body): Double = pos[body.ordinal * 3 + 2]

    fun positionInto(body: Body, out: DoubleArray) {
        out[0] = x(body)
        out[1] = y(body)
        out[2] = z(body)
    }

    /** Distance from a point to [body]'s centre, km. */
    fun distanceTo(body: Body, px: Double, py: Double, pz: Double): Double {
        val dx = px - x(body)
        val dy = py - y(body)
        val dz = pz - z(body)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** The body whose *surface* is closest to the point. */
    fun nearest(px: Double, py: Double, pz: Double): Body {
        var best = Body.SUN
        var bestAlt = Double.MAX_VALUE
        for (body in bodies) {
            val alt = distanceTo(body, px, py, pz) - body.radiusKm
            if (alt < bestAlt) {
                bestAlt = alt
                best = body
            }
        }
        return best
    }
}
