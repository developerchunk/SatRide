package com.developerstring.satride.core.designsystem.component

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The polar plot's geometry, checked by arithmetic.
 *
 * Every one of these is a fact a person can verify on a paper plot: the zenith is the middle,
 * north is up, east is right. They exist because a sign error here is not a crash — it is a
 * mirrored sky that looks entirely plausible until someone compares it with the real thing.
 */
class PolarProjectionTest {

    @Test
    fun zenithProjectsToTheCentre() {
        val (east, north) = PolarProjection.project(azimuthDeg = 123f, elevationDeg = 90f)
        assertClose(0f, east)
        assertClose(0f, north)
    }

    @Test
    fun horizonProjectsToTheRim() {
        val (east, north) = PolarProjection.project(azimuthDeg = 0f, elevationDeg = 0f)
        assertClose(0f, east)
        assertClose(1f, north)
    }

    @Test
    fun northIsUpAndEastIsRight() {
        val (northEast, northNorth) = PolarProjection.project(0f, 0f)
        assertClose(0f, northEast)
        assertClose(1f, northNorth)

        val (eastEast, eastNorth) = PolarProjection.project(90f, 0f)
        assertClose(1f, eastEast)
        assertClose(0f, eastNorth)

        val (southEast, southNorth) = PolarProjection.project(180f, 0f)
        assertClose(0f, southEast)
        assertClose(-1f, southNorth)

        val (westEast, westNorth) = PolarProjection.project(270f, 0f)
        assertClose(-1f, westEast)
        assertClose(0f, westNorth)
    }

    @Test
    fun radiusIsLinearInElevation() {
        // Halfway up the sky is halfway between the rim and the centre. This is the choice the
        // projection makes, so it is the thing worth pinning.
        val (_, north) = PolarProjection.project(0f, 45f)
        assertClose(0.5f, north)
    }

    @Test
    fun belowHorizonClampsToTheRimInsteadOfEscapingTheDisc() {
        val (_, north) = PolarProjection.project(0f, -12f)
        assertClose(1f, north)
    }

    @Test
    fun screenCoordinatesFlipNorthToUp() {
        val center = Offset(100f, 100f)
        val north = PolarProjection.toOffset(0f, 0f, center, radiusPx = 50f)
        // Up the screen is a *smaller* y, which is the one conversion the whole drawing depends on.
        assertClose(100f, north.x)
        assertClose(50f, north.y)

        val east = PolarProjection.toOffset(90f, 0f, center, radiusPx = 50f)
        assertClose(150f, east.x)
        assertClose(100f, east.y)
    }

    @Test
    fun markersAreAosCulminationAndLos() {
        val arc = listOf(
            SkyPoint(10f, 0f),
            SkyPoint(40f, 22f),
            SkyPoint(90f, 61f),
            SkyPoint(140f, 19f),
            SkyPoint(170f, 0f),
        )
        val markers = PassMarkers.from(arc)

        assertEquals(arc.first(), markers.aos)
        assertEquals(arc.last(), markers.los)
        // Not the middle sample by position — the highest one. For a symmetric arc they coincide,
        // which is exactly why an asymmetric one is used here.
        assertEquals(61f, markers.tca?.elevationDeg)
    }

    @Test
    fun culminationIsNotAssumedToBeTheMiddleSample() {
        // A pass clipped by the end of the search window: the peak is the last sample.
        val arc = listOf(SkyPoint(10f, 0f), SkyPoint(30f, 14f), SkyPoint(60f, 48f))
        assertEquals(48f, PassMarkers.from(arc).tca?.elevationDeg)
    }

    @Test
    fun anEmptyArcHasNoMarkers() {
        val markers = PassMarkers.from(emptyList())
        assertNull(markers.aos)
        assertNull(markers.tca)
        assertNull(markers.los)
    }

    @Test
    fun everyProjectedPointStaysInsideTheDisc() {
        var azimuth = 0f
        while (azimuth < 360f) {
            var elevation = -5f
            while (elevation <= 95f) {
                val (east, north) = PolarProjection.project(azimuth, elevation)
                val radius = kotlin.math.sqrt(east * east + north * north)
                assertTrue(radius <= 1.0001f, "az=$azimuth el=$elevation escaped the disc at r=$radius")
                elevation += 7f
            }
            azimuth += 13f
        }
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 1e-4f, "expected $expected but was $actual")
    }
}
