package com.developerstring.satride.core.orbit.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A unit quaternion representing a rotation.
 *
 * The AR view (P5) uses these for device attitude, where the important property is that
 * interpolating between two orientations is smooth and free of gimbal lock — which is exactly
 * what [slerp] provides and Euler angles do not.
 *
 * Stored as (w, x, y, z) with w the scalar part.
 */
public data class Quaternion(public val w: Double, public val x: Double, public val y: Double, public val z: Double) {
    public val magnitude: Double get() = sqrt(w * w + x * x + y * y + z * z)

    public fun normalized(): Quaternion {
        val m = magnitude
        return if (m == 0.0) IDENTITY else Quaternion(w / m, x / m, y / m, z / m)
    }

    /** Inverse rotation (valid as the inverse only while this quaternion is unit length). */
    public fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    /** Composition: `this * other` applies [other] first, then this. */
    public operator fun times(other: Quaternion): Quaternion = Quaternion(
        w = w * other.w - x * other.x - y * other.y - z * other.z,
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w,
    )

    /**
     * Rotates [v] by this quaternion.
     *
     * `v' = 2(u·v)u + (s² − u·u)v + 2s(u×v)`, where u is the vector part and s the scalar. The
     * middle term already carries v — adding another copy silently doubles every vector, which
     * looks plausible right up until nothing lines up.
     */
    public fun rotate(v: Vector3): Vector3 {
        val u = Vector3(x, y, z)
        val s = w
        val uv = u cross v
        return (u * (2.0 * (u dot v))) + (v * (s * s - (u dot u))) + (uv * (2.0 * s))
    }

    public infix fun dot(other: Quaternion): Double = w * other.w + x * other.x + y * other.y + z * other.z

    /**
     * Spherical linear interpolation towards [target] by [t] in 0..1.
     *
     * Takes the shorter arc: q and -q are the same rotation, so without the sign fix an
     * interpolation can spin the long way round — which in the AR view looks like the sky
     * lurching.
     */
    public fun slerp(target: Quaternion, t: Double): Quaternion {
        var cosHalf = this dot target
        var end = target
        if (cosHalf < 0.0) {
            end = Quaternion(-target.w, -target.x, -target.y, -target.z)
            cosHalf = -cosHalf
        }
        if (cosHalf > 0.9995) {
            // Nearly identical: lerp and renormalise, which stays numerically stable where
            // the sin() below would divide by almost zero.
            return Quaternion(
                w + (end.w - w) * t,
                x + (end.x - x) * t,
                y + (end.y - y) * t,
                z + (end.z - z) * t,
            ).normalized()
        }
        val halfAngle = acos(cosHalf.coerceIn(-1.0, 1.0))
        val sinHalf = sin(halfAngle)
        val a = sin((1.0 - t) * halfAngle) / sinHalf
        val b = sin(t * halfAngle) / sinHalf
        return Quaternion(
            w * a + end.w * b,
            x * a + end.x * b,
            y * a + end.y * b,
            z * a + end.z * b,
        ).normalized()
    }

    /** Rotation angle in radians. */
    public val angleRadians: Double get() = 2.0 * acos(abs(w).coerceIn(-1.0, 1.0))

    /**
     * The smallest rotation angle between this and [other], in radians.
     *
     * Takes the shorter arc: q and -q are the same rotation, so the sign of the dot product has
     * to be removed or half of all comparisons come back as "nearly 360 degrees apart" — which
     * would make an attitude filter lurch every time the sign flipped.
     */
    public fun angleTo(other: Quaternion): Double = 2.0 * acos(abs(this dot other).coerceIn(-1.0, 1.0))

    public companion object {
        public val IDENTITY: Quaternion = Quaternion(1.0, 0.0, 0.0, 0.0)

        public fun fromAxisAngle(axis: Vector3, radians: Double): Quaternion {
            val n = axis.normalized()
            val half = radians / 2.0
            val s = sin(half)
            return Quaternion(cos(half), n.x * s, n.y * s, n.z * s)
        }

        /**
         * Builds a rotation from a row-major 3×3 matrix, `m[row * 3 + col]`.
         *
         * Android's sensor stack hands out rotation *matrices*, so this is the bridge into the
         * quaternion the rest of the app works in. Shepperd's method: pick whichever of the four
         * components is largest and derive the rest from it. The naive single-branch formula
         * divides by a quantity that approaches zero for rotations near 180 degrees, which is
         * exactly the orientation of a phone held up to look at the sky.
         */
        public fun fromRotationMatrix(m: DoubleArray): Quaternion {
            require(m.size == 9) { "Expected a 3x3 row-major matrix, got ${m.size} values" }
            val m00 = m[0]
            val m01 = m[1]
            val m02 = m[2]
            val m10 = m[3]
            val m11 = m[4]
            val m12 = m[5]
            val m20 = m[6]
            val m21 = m[7]
            val m22 = m[8]

            val trace = m00 + m11 + m22
            return when {
                trace > 0.0 -> {
                    val s = sqrt(trace + 1.0) * 2.0
                    Quaternion(0.25 * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
                }

                m00 > m11 && m00 > m22 -> {
                    val s = sqrt(1.0 + m00 - m11 - m22) * 2.0
                    Quaternion((m21 - m12) / s, 0.25 * s, (m01 + m10) / s, (m02 + m20) / s)
                }

                m11 > m22 -> {
                    val s = sqrt(1.0 + m11 - m00 - m22) * 2.0
                    Quaternion((m02 - m20) / s, (m01 + m10) / s, 0.25 * s, (m12 + m21) / s)
                }

                else -> {
                    val s = sqrt(1.0 + m22 - m00 - m11) * 2.0
                    Quaternion((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25 * s)
                }
            }.normalized()
        }
    }
}
