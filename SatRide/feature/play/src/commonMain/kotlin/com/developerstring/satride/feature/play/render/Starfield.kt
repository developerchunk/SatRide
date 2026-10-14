package com.developerstring.satride.feature.play.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A fixed backdrop of stars, generated once from a seeded generator.
 *
 * Directions live in the inertial frame — the renderer rotates them by GMST so the sky stays
 * still while the Earth turns, which is the detail that makes fast-forward feel like the planet
 * spinning rather than the universe revolving around it.
 */
internal class Starfield(val count: Int = 420, seed: Long = 0x5EED5EEDL) {

    val dx: DoubleArray = DoubleArray(count)
    val dy: DoubleArray = DoubleArray(count)
    val dz: DoubleArray = DoubleArray(count)

    /** Screen radius in dp and base alpha, both varied so the field reads as depth, not noise. */
    val radiusDp: FloatArray = FloatArray(count)
    val alpha: FloatArray = FloatArray(count)

    init {
        var state = seed
        fun next(): Double {
            // SplitMix64: tiny, deterministic, and more than random enough for scenery.
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            return (z ushr 11).toDouble() / (1L shl 53).toDouble()
        }

        for (i in 0 until count) {
            val z = next() * 2.0 - 1.0
            val phi = next() * 2.0 * PI
            val r = sqrt((1.0 - z * z).coerceAtLeast(0.0))
            dx[i] = r * cos(phi)
            dy[i] = r * sin(phi)
            dz[i] = z
            val brightness = next()
            radiusDp[i] = (0.4f + brightness.toFloat() * 0.9f)
            alpha[i] = 0.25f + brightness.toFloat() * 0.6f
        }
    }
}
