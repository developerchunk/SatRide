package com.developerstring.satride.core.common

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GeohashTest {

    /**
     * Published geohashes, so the encoder is checked against the specification rather than
     * against itself.
     */
    @Test
    fun matchesPublishedGeohashes() {
        assertEquals("ezs42", Geohash.encode(42.6, -5.6, 5))
        assertEquals("u4pruydqqvj", Geohash.encode(57.64911, 10.40744, 11))
        assertEquals("s0000000000", Geohash.encode(0.0, 0.0, 11))
    }

    @Test
    fun nearbyPositionsShareACacheKey() {
        // Two fixes 200 m apart — ordinary GPS wander, must not invalidate a cached pass list.
        val a = Geohash.encode(51.5074, -0.1278)
        val b = Geohash.encode(51.5092, -0.1278)

        assertEquals(a, b)
    }

    @Test
    fun distantPositionsDoNotShareACacheKey() {
        val london = Geohash.encode(51.5074, -0.1278)
        val paris = Geohash.encode(48.8566, 2.3522)

        assertNotEquals(london, paris)
    }

    /**
     * The property the pass cache actually depends on: everything sharing a key is close enough
     * that a pass computed for one point is valid for the other. P1 verified pass times to ten
     * seconds, and ten kilometres of observer displacement is worth about two seconds of that.
     */
    @Test
    fun everyPositionInACellIsWithinTheToleranceThePassCacheAssumes() {
        // Corner to corner of a precision-5 cell, taken at a latitude where cells are widest.
        val cellLatDeg = 180.0 / (1 shl 12) // 12 latitude bits at precision 5
        val cellLonDeg = 360.0 / (1 shl 13) // 13 longitude bits at precision 5
        val diagonalKm = greatCircleKm(0.0, 0.0, cellLatDeg, cellLonDeg)

        assertTrue(
            diagonalKm < 10.0,
            "precision-${Geohash.PASS_CACHE_PRECISION} cells span $diagonalKm km, " +
                "which exceeds the tolerance the pass cache is documented to assume",
        )
    }

    @Test
    fun wrapsLongitudeInsteadOfFailing() {
        assertEquals(Geohash.encode(10.0, 190.0), Geohash.encode(10.0, -170.0))
    }

    private fun greatCircleKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val toRad = PI / 180.0
        val dLat = (lat2 - lat1) * toRad
        val dLon = (lon2 - lon1) * toRad
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * toRad) * cos(lat2 * toRad) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
