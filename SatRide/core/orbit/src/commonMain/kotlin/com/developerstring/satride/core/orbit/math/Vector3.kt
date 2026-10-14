package com.developerstring.satride.core.orbit.math

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * A 3-component vector, used for positions (km) and velocities (km/s) throughout the engine.
 *
 * Immutable and small enough that the JIT and Kotlin/Native both handle it well. The hot
 * propagation path avoids allocating these at all and works on raw doubles; this type exists
 * for the readable code around it.
 */
public data class Vector3(public val x: Double, public val y: Double, public val z: Double) {
    public val magnitude: Double get() = sqrt(x * x + y * y + z * z)

    public val magnitudeSquared: Double get() = x * x + y * y + z * z

    public operator fun plus(other: Vector3): Vector3 = Vector3(x + other.x, y + other.y, z + other.z)

    public operator fun minus(other: Vector3): Vector3 = Vector3(x - other.x, y - other.y, z - other.z)

    public operator fun times(scalar: Double): Vector3 = Vector3(x * scalar, y * scalar, z * scalar)

    public operator fun div(scalar: Double): Vector3 = Vector3(x / scalar, y / scalar, z / scalar)

    public operator fun unaryMinus(): Vector3 = Vector3(-x, -y, -z)

    public infix fun dot(other: Vector3): Double = x * other.x + y * other.y + z * other.z

    public infix fun cross(other: Vector3): Vector3 = Vector3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    /** Unit vector in the same direction; returns [ZERO] for a zero-length vector. */
    public fun normalized(): Vector3 {
        val m = magnitude
        return if (m == 0.0) ZERO else Vector3(x / m, y / m, z / m)
    }

    /** Angle to [other] in radians, clamped against floating-point drift outside acos's domain. */
    public fun angleTo(other: Vector3): Double {
        val denom = magnitude * other.magnitude
        if (denom == 0.0) return 0.0
        return acos((this dot other) / denom.coerceAtLeast(1e-300))
    }

    /** Rotation about the Z axis by [radians], the workhorse of the TEME→ECEF transform. */
    public fun rotateZ(radians: Double): Vector3 {
        val c = kotlin.math.cos(radians)
        val s = kotlin.math.sin(radians)
        return Vector3(c * x + s * y, -s * x + c * y, z)
    }

    public companion object {
        public val ZERO: Vector3 = Vector3(0.0, 0.0, 0.0)
    }
}
