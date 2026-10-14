package com.developerstring.satride.feature.play.multiplay.engine

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Allocation-free vector helpers on `DoubleArray(3)`, for the game loop.
 *
 * The engine keeps every ship on a sphere: a position is a unit vector, a heading is a unit
 * tangent, and moving is rotating the position toward the tangent. These are the handful of
 * operations that needs, written to be called thousands of times a frame without boxing.
 */
internal object SphereMath {

    const val EARTH_RADIUS_KM: Double = 6371.0
    const val DEG_TO_RAD: Double = PI / 180.0
    const val RAD_TO_DEG: Double = 180.0 / PI

    fun set(out: DoubleArray, x: Double, y: Double, z: Double) {
        out[0] = x
        out[1] = y
        out[2] = z
    }

    fun copy(from: DoubleArray, to: DoubleArray) {
        to[0] = from[0]
        to[1] = from[1]
        to[2] = from[2]
    }

    fun dot(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun length(a: DoubleArray): Double = sqrt(dot(a, a))

    fun cross(a: DoubleArray, b: DoubleArray, out: DoubleArray) {
        val x = a[1] * b[2] - a[2] * b[1]
        val y = a[2] * b[0] - a[0] * b[2]
        val z = a[0] * b[1] - a[1] * b[0]
        out[0] = x
        out[1] = y
        out[2] = z
    }

    /** Normalises in place; leaves a zero vector alone rather than producing NaN. */
    fun normalize(v: DoubleArray): Boolean {
        val m = length(v)
        if (m < 1e-12) return false
        v[0] /= m
        v[1] /= m
        v[2] /= m
        return true
    }

    /** Removes the component of [v] along unit [n], in place — projects onto the tangent plane. */
    fun tangentialize(v: DoubleArray, n: DoubleArray) {
        val d = dot(v, n)
        v[0] -= n[0] * d
        v[1] -= n[1] * d
        v[2] -= n[2] * d
    }

    /** Rotates [v] about unit [axis] by [radians] (Rodrigues), in place. */
    fun rotateAbout(v: DoubleArray, axis: DoubleArray, radians: Double) {
        val c = cos(radians)
        val s = sin(radians)
        val ax = axis[0]
        val ay = axis[1]
        val az = axis[2]
        val vx = v[0]
        val vy = v[1]
        val vz = v[2]
        val d = ax * vx + ay * vy + az * vz
        val cx = ay * vz - az * vy
        val cy = az * vx - ax * vz
        val cz = ax * vy - ay * vx
        v[0] = vx * c + cx * s + ax * d * (1 - c)
        v[1] = vy * c + cy * s + ay * d * (1 - c)
        v[2] = vz * c + cz * s + az * d * (1 - c)
    }

    /**
     * Moves unit position [p] along unit tangent [t] by [radians] of arc, writing the new position
     * into [p] and parallel-transporting [t] so it stays tangent at the new point.
     */
    fun step(p: DoubleArray, t: DoubleArray, radians: Double) {
        val c = cos(radians)
        val s = sin(radians)
        val nx = p[0] * c + t[0] * s
        val ny = p[1] * c + t[1] * s
        val nz = p[2] * c + t[2] * s
        // The transported tangent is the derivative: -p sin + t cos.
        val tx = -p[0] * s + t[0] * c
        val ty = -p[1] * s + t[1] * c
        val tz = -p[2] * s + t[2] * c
        p[0] = nx
        p[1] = ny
        p[2] = nz
        t[0] = tx
        t[1] = ty
        t[2] = tz
        normalize(p)
        tangentialize(t, p)
        normalize(t)
    }

    /** Great-circle angle between two unit vectors, radians. */
    fun angleBetween(a: DoubleArray, b: DoubleArray): Double = acos(dot(a, b).coerceIn(-1.0, 1.0))

    /**
     * Signed angle from tangent [from] to tangent [to] about the surface normal [n]: positive
     * turns left (counter-clockwise seen from above the surface).
     */
    fun signedTangentAngle(from: DoubleArray, to: DoubleArray, n: DoubleArray): Double {
        val cx = from[1] * to[2] - from[2] * to[1]
        val cy = from[2] * to[0] - from[0] * to[2]
        val cz = from[0] * to[1] - from[1] * to[0]
        val sinA = cx * n[0] + cy * n[1] + cz * n[2]
        val cosA = dot(from, to)
        return atan2(sinA, cosA)
    }

    /** The unit tangent at [p] pointing toward [target] (both unit vectors), into [out]. */
    fun directionToward(p: DoubleArray, target: DoubleArray, out: DoubleArray): Boolean {
        out[0] = target[0]
        out[1] = target[1]
        out[2] = target[2]
        tangentialize(out, p)
        return normalize(out)
    }

    /** Geodetic-ish latitude/longitude of a unit vector, degrees (spherical, which the game is). */
    fun latitudeDeg(p: DoubleArray): Double = atan2(p[2], sqrt(p[0] * p[0] + p[1] * p[1])) * RAD_TO_DEG

    fun longitudeDeg(p: DoubleArray): Double = atan2(p[1], p[0]) * RAD_TO_DEG

    fun fromLatLon(latDeg: Double, lonDeg: Double, out: DoubleArray) {
        val lat = latDeg * DEG_TO_RAD
        val lon = lonDeg * DEG_TO_RAD
        val c = cos(lat)
        out[0] = c * cos(lon)
        out[1] = c * sin(lon)
        out[2] = sin(lat)
    }

    /** Compass heading (degrees, 0 = north, 90 = east) of unit tangent [t] at unit position [p]. */
    fun headingDeg(p: DoubleArray, t: DoubleArray): Double {
        // East = z × p, north = p × east.
        val ex = -p[1]
        val ey = p[0]
        val em = sqrt(ex * ex + ey * ey)
        if (em < 1e-9) return 0.0
        val eX = ex / em
        val eY = ey / em
        // North = p × east, with east's z being zero.
        val nX = -p[2] * eY
        val nY = p[2] * eX
        val nZ = p[0] * eY - p[1] * eX
        val east = t[0] * eX + t[1] * eY
        val north = t[0] * nX + t[1] * nY + t[2] * nZ
        return ((atan2(east, north) * RAD_TO_DEG) + 360.0) % 360.0
    }

    /** The unit tangent at unit [p] for a compass [headingDeg], into [out]. */
    fun tangentFromHeading(p: DoubleArray, headingDeg: Double, out: DoubleArray) {
        val ex = -p[1]
        val ey = p[0]
        val em = sqrt(ex * ex + ey * ey)
        if (em < 1e-9) {
            set(out, 1.0, 0.0, 0.0)
            tangentialize(out, p)
            normalize(out)
            return
        }
        val eX = ex / em
        val eY = ey / em
        val nX = -p[2] * eY
        val nY = p[2] * eX
        val nZ = p[0] * eY - p[1] * eX
        val h = headingDeg * DEG_TO_RAD
        val s = sin(h)
        val c = cos(h)
        out[0] = eX * s + nX * c
        out[1] = eY * s + nY * c
        out[2] = nZ * c
        normalize(out)
    }

    /** Distance in km between two world points given as unit vectors and altitudes. */
    fun distanceKm(p: DoubleArray, altP: Double, q: DoubleArray, altQ: Double): Double {
        val rp = EARTH_RADIUS_KM + altP
        val rq = EARTH_RADIUS_KM + altQ
        val dx = p[0] * rp - q[0] * rq
        val dy = p[1] * rp - q[1] * rq
        val dz = p[2] * rp - q[2] * rq
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Shortest distance from point [x] to the segment [a]–[b] (all world km, packed x,y,z).
     * Used for swept collision: a ship covering 15 km per frame must not tunnel through debris.
     */
    fun pointSegmentDistance(
        px: Double,
        py: Double,
        pz: Double,
        ax: Double,
        ay: Double,
        az: Double,
        bx: Double,
        by: Double,
        bz: Double,
    ): Double {
        val abx = bx - ax
        val aby = by - ay
        val abz = bz - az
        val ab2 = abx * abx + aby * aby + abz * abz
        var t = if (ab2 < 1e-12) 0.0 else ((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / ab2
        t = t.coerceIn(0.0, 1.0)
        val cx = ax + abx * t - px
        val cy = ay + aby * t - py
        val cz = az + abz * t - pz
        return sqrt(cx * cx + cy * cy + cz * cz)
    }
}
