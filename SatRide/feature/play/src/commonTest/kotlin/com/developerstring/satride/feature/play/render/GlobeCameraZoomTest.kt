package com.developerstring.satride.feature.play.render

import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How far out the globe camera can pull.
 *
 * The old limit was nine Earth radii — about 57,000 km — which is *less* than the diameter of the
 * geostationary ring, so anything above low orbit could not be framed at all and there was no way
 * to pull back far enough to see it. These pin the new range against the orbits that actually exist
 * in the catalog, so shrinking it again fails here rather than in someone's hands.
 */
class GlobeCameraZoomTest {

    /** Camera distance needed to fit a circle of [radiusKm] in a 45° vertical field of view. */
    private fun distanceToFrame(radiusKm: Double): Double = radiusKm / tan(45.0 / 2.0 * kotlin.math.PI / 180.0)

    @Test
    fun theCameraCanPullBackFarEnoughToFrameGeostationaryOrbit() {
        // The GEO belt sits 42,164 km from the Earth's centre.
        val needed = distanceToFrame(GEO_RADIUS_KM)
        assertTrue(
            GlobeCamera.MAX_ORBIT_KM >= needed,
            "cannot frame GEO: need ${needed.toInt()} km, limit is ${GlobeCamera.MAX_ORBIT_KM.toInt()} km",
        )
    }

    @Test
    fun theCameraCanFrameAMolniyaApogee() {
        // Highly elliptical orbits reach about 46,000 km at apogee — higher than GEO.
        assertTrue(GlobeCamera.MAX_ORBIT_KM >= distanceToFrame(MOLNIYA_APOGEE_KM))
    }

    @Test
    fun theOldLimitWouldNotHaveFramedGeo() {
        // Guards the reasoning, not just the number: if someone later "tidies" the limit back to
        // single digits of Earth radii, this says why that was wrong.
        val oldLimit = EARTH_RADIUS_KM * 9.0
        assertTrue(oldLimit < distanceToFrame(GEO_RADIUS_KM), "the old limit was fine after all — recheck the maths")
    }

    @Test
    fun zoomingOutStopsAtTheLimitRatherThanRunningAway() {
        val camera = GlobeCamera()
        repeat(200) { camera.zoom(0.5f, riding = false) }
        // `zoom` writes a target that `settle` eases toward, so drive it to rest.
        repeat(400) { camera.settle(0.05f) }

        assertTrue(camera.distanceKm <= GlobeCamera.MAX_ORBIT_KM + 1.0, "escaped the limit: ${camera.distanceKm}")
        assertTrue(camera.distanceKm > GlobeCamera.MAX_ORBIT_KM * 0.98, "did not reach the limit: ${camera.distanceKm}")
    }

    @Test
    fun zoomingInStopsAboveTheSurface() {
        val camera = GlobeCamera()
        repeat(200) { camera.zoom(2f, riding = false) }
        repeat(400) { camera.settle(0.05f) }

        // Never inside the planet, and never so close the near plane clips the horizon.
        assertTrue(camera.distanceKm >= EARTH_RADIUS_KM, "camera went underground: ${camera.distanceKm}")
        assertTrue(camera.distanceKm >= GlobeCamera.MIN_ORBIT_KM * 0.98)
    }

    @Test
    fun rideDistanceIsUnaffectedByTheOrbitLimits() {
        // Ride mode has its own, much tighter range — the camera is metres from a satellite, not
        // hundreds of thousands of kilometres from a planet.
        val camera = GlobeCamera()
        repeat(200) { camera.zoom(0.5f, riding = true) }
        repeat(400) { camera.settle(0.05f) }

        assertTrue(camera.rideDistanceKm <= GlobeCamera.MAX_RIDE_KM + 1.0)
        assertTrue(camera.distanceKm < GlobeCamera.MAX_ORBIT_KM, "riding must not move the orbit camera")
    }

    private companion object {
        const val GEO_RADIUS_KM = 42_164.0
        const val MOLNIYA_APOGEE_KM = 46_300.0
    }
}
