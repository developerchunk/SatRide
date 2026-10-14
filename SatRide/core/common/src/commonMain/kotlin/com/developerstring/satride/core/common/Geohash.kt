package com.developerstring.satride.core.common

/**
 * Geohash encoding, used to key anything cached "for where the user is".
 *
 * A geohash turns a continuous position into a discrete cell id, which is exactly what a cache
 * key needs: two nearby fixes collapse to the same string, so a GPS jitter of a few metres does
 * not invalidate an hour of computed passes. The failure mode is benign — a cell boundary can
 * split two adjacent points and cause one unnecessary recompute — and never the dangerous
 * direction of reusing a result from somewhere the user is not.
 */
object Geohash {

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /**
     * Precision used for the pass cache.
     *
     * Five characters is a cell of roughly 4.9 km by 4.9 km, so any two positions sharing a key
     * are at most about 7 km apart. Moving that far shifts a low-orbit pass by a couple of
     * seconds — inside the ten-second accuracy the pass predictor was verified to in P1 — so
     * reusing the cache within a cell is honest. Six characters (1.2 km) would recompute every
     * time the user crossed town for no accuracy the user could perceive.
     */
    const val PASS_CACHE_PRECISION: Int = 5

    /**
     * Encodes [latDeg]/[lonDeg] to a geohash of [precision] characters.
     *
     * Standard interleaved binary subdivision: each bit halves the longitude or latitude range
     * in turn, five bits per output character.
     */
    fun encode(latDeg: Double, lonDeg: Double, precision: Int = PASS_CACHE_PRECISION): String {
        require(precision in 1..12) { "Geohash precision must be 1..12, was $precision" }

        var latLow = -90.0
        var latHigh = 90.0
        var lonLow = -180.0
        var lonHigh = 180.0

        val result = StringBuilder(precision)
        var isLongitudeTurn = true
        var bit = 0
        var accumulator = 0

        // Clamped rather than rejected: a caller with a slightly out-of-range fix from a sensor
        // wants a cache key, not an exception.
        val lat = latDeg.coerceIn(-90.0, 90.0)
        val lon = ((lonDeg + 180.0).mod(360.0)) - 180.0

        while (result.length < precision) {
            if (isLongitudeTurn) {
                val mid = (lonLow + lonHigh) / 2.0
                if (lon >= mid) {
                    accumulator = (accumulator shl 1) or 1
                    lonLow = mid
                } else {
                    accumulator = accumulator shl 1
                    lonHigh = mid
                }
            } else {
                val mid = (latLow + latHigh) / 2.0
                if (lat >= mid) {
                    accumulator = (accumulator shl 1) or 1
                    latLow = mid
                } else {
                    accumulator = accumulator shl 1
                    latHigh = mid
                }
            }
            isLongitudeTurn = !isLongitudeTurn

            bit++
            if (bit == 5) {
                result.append(BASE32[accumulator])
                bit = 0
                accumulator = 0
            }
        }
        return result.toString()
    }
}
