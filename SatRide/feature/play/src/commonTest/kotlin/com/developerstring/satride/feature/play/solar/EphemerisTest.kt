package com.developerstring.satride.feature.play.solar

import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.frames.Frames
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The ephemeris against things that are known: orbital distances, and the Sun's direction as
 * seen from Earth, which the rest of the app already computes independently for eclipses.
 */
class EphemerisTest {

    private val time = Instant.fromEpochMilliseconds(1_788_000_000_000L) // 2026-09-04
    private val jd = Ephemeris.julianDate(time)

    private fun distanceAu(body: Body): Double {
        val p = DoubleArray(3)
        Ephemeris.position(body, jd, p)
        return sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]) / Constants.AU_KM
    }

    @Test
    fun planetsSitAtTheirOrbitalDistances() {
        assertTrue(distanceAu(Body.MERCURY) in 0.30..0.47, "Mercury ${distanceAu(Body.MERCURY)}")
        assertTrue(distanceAu(Body.VENUS) in 0.71..0.73, "Venus ${distanceAu(Body.VENUS)}")
        assertTrue(distanceAu(Body.EARTH) in 0.98..1.02, "Earth ${distanceAu(Body.EARTH)}")
        assertTrue(distanceAu(Body.MARS) in 1.38..1.67, "Mars ${distanceAu(Body.MARS)}")
        assertTrue(distanceAu(Body.JUPITER) in 4.9..5.5, "Jupiter ${distanceAu(Body.JUPITER)}")
        assertTrue(distanceAu(Body.SATURN) in 9.0..10.1, "Saturn ${distanceAu(Body.SATURN)}")
        assertTrue(distanceAu(Body.URANUS) in 18.2..20.1, "Uranus ${distanceAu(Body.URANUS)}")
        assertTrue(distanceAu(Body.NEPTUNE) in 29.8..30.4, "Neptune ${distanceAu(Body.NEPTUNE)}")
        assertTrue(distanceAu(Body.PLUTO) in 29.6..49.4, "Pluto ${distanceAu(Body.PLUTO)}")
    }

    @Test
    fun moonIsAboutALightSecondAndAThirdFromEarth() {
        val earth = DoubleArray(3)
        val moon = DoubleArray(3)
        Ephemeris.position(Body.EARTH, jd, earth)
        Ephemeris.position(Body.MOON, jd, moon)
        val d = sqrt(
            (moon[0] - earth[0]) * (moon[0] - earth[0]) +
                (moon[1] - earth[1]) * (moon[1] - earth[1]) +
                (moon[2] - earth[2]) * (moon[2] - earth[2]),
        )
        assertTrue(d in 356_000.0..407_000.0, "Moon distance $d km")
    }

    @Test
    fun sunDirectionFromEarthMatchesTheOrbitLibrary() {
        // The game's frame is equatorial J2000; the library's is TEME of date. Precession over
        // a quarter century is about a third of a degree, so the two must agree to a degree.
        val earth = DoubleArray(3)
        Ephemeris.position(Body.EARTH, jd, earth)
        val m = sqrt(earth[0] * earth[0] + earth[1] * earth[1] + earth[2] * earth[2])
        val gx = -earth[0] / m
        val gy = -earth[1] / m
        val gz = -earth[2] / m
        val sun = Frames.sunPositionTeme(time).normalized()
        val angleDeg = acos((gx * sun.x + gy * sun.y + gz * sun.z).coerceIn(-1.0, 1.0)) * 180.0 / kotlin.math.PI
        assertTrue(angleDeg < 1.0, "Sun direction differs by $angleDeg°")
    }

    @Test
    fun orbitSamplesPassThroughTheCurrentPosition() {
        // The planet's own position must lie on the sampled orbit path within a fraction of a
        // percent of the orbit's size — the line the renderer draws goes through the dot.
        for (body in listOf(Body.MARS, Body.JUPITER, Body.NEPTUNE)) {
            val now = DoubleArray(3)
            Ephemeris.position(body, jd, now)
            var best = Double.MAX_VALUE
            val pt = DoubleArray(3)
            for (k in 0 until 720) {
                Ephemeris.orbitPoint(body, jd, 2.0 * kotlin.math.PI * k / 720, pt)
                val d = sqrt(
                    (pt[0] - now[0]) * (pt[0] - now[0]) + (pt[1] - now[1]) * (pt[1] - now[1]) +
                        (pt[2] - now[2]) * (pt[2] - now[2]),
                )
                if (d < best) best = d
            }
            val scale = distanceAu(body) * Constants.AU_KM
            assertTrue(best / scale < 0.01, "${body.label} orbit misses its planet by ${best / scale}")
        }
    }

    @Test
    fun polesAreUnitAndSaturnIsTilted() {
        val p = DoubleArray(3)
        for (body in Body.entries) {
            Ephemeris.poleAxis(body, p)
            val m = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
            assertTrue(kotlin.math.abs(m - 1.0) < 1e-9, "${body.label} pole not unit")
        }
        Ephemeris.poleAxis(Body.SATURN, p)
        // Saturn's pole is ~27° from the ecliptic pole; Uranus lies nearly in the plane.
        val eclPoleZ = p[2] * kotlin.math.cos(Ephemeris.OBLIQUITY_DEG * kotlin.math.PI / 180.0) -
            p[1] * kotlin.math.sin(Ephemeris.OBLIQUITY_DEG * kotlin.math.PI / 180.0)
        val tilt = acos(eclPoleZ.coerceIn(-1.0, 1.0)) * 180.0 / kotlin.math.PI
        assertTrue(tilt in 24.0..30.0, "Saturn tilt $tilt")
    }
}
