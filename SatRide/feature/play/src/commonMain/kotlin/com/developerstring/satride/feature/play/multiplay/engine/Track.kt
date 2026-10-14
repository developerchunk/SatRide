package com.developerstring.satride.feature.play.multiplay.engine

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A lap of the planet: a tilted great circle with gates strung along it, winding up and down
 * through the altitude band and side to side across the corridor.
 *
 * Built entirely from a [seed], so every player in a room derives the identical track from the
 * one number the host wrote — no geometry ever crosses the network. The same seed on a different
 * day gives the same track, which is what makes a daily challenge comparable between players.
 */
class Track(val seed: Long, val gateCount: Int = GATE_COUNT) {

    /** Orthonormal basis of the great-circle plane: the track runs e1 → e2, n is the normal. */
    private val e1 = DoubleArray(3)
    private val e2 = DoubleArray(3)
    private val n = DoubleArray(3)

    /** Gate centres as unit vectors, and their altitudes. */
    val gateDir: Array<DoubleArray> = Array(gateCount) { DoubleArray(3) }
    val gateAltKm: DoubleArray = DoubleArray(gateCount)

    /** Gate ring normals: the track direction through the gate, unit vectors. */
    val gateAxis: Array<DoubleArray> = Array(gateCount) { DoubleArray(3) }

    /** Gate ring radius, km. */
    val gateRadiusKm: Double = GATE_RADIUS_KM

    /** Boost orbs: unit direction + altitude, roughly midway between gates, off to one side. */
    val orbDir: Array<DoubleArray> = Array(gateCount) { DoubleArray(3) }
    val orbAltKm: DoubleArray = DoubleArray(gateCount)

    private val scratch = DoubleArray(3)
    private val scratch2 = DoubleArray(3)

    // Winding parameters, from the seed, so no two tracks feel the same.
    private val wobbleAmp: Double
    private val wobbleFreq: Int
    private val wobblePhase: Double
    private val altFreq: Int
    private val altPhase: Double

    init {
        val rng = SeededRandom(seed)
        // A random plane whose inclination stays away from the poles: a polar track spends most
        // of its lap over ocean and ice, and the point of racing the planet is the coastlines.
        val inclination = (28.0 + rng.nextDouble() * 34.0) * PI / 180.0
        val node = rng.nextDouble() * 2.0 * PI
        val ci = cos(inclination)
        val si = sin(inclination)
        // Normal of the orbital plane for these Keplerian-style angles.
        n[0] = si * sin(node)
        n[1] = -si * cos(node)
        n[2] = ci
        SphereMath.normalize(n)
        // e1: the ascending node direction; e2 = n × e1.
        e1[0] = cos(node)
        e1[1] = sin(node)
        e1[2] = 0.0
        SphereMath.tangentialize(e1, n)
        SphereMath.normalize(e1)
        SphereMath.cross(n, e1, e2)
        SphereMath.normalize(e2)

        wobbleAmp = (0.018 + rng.nextDouble() * 0.020) // radians of lateral swing (~115–240 km)
        wobbleFreq = 2 + rng.nextInt(3)
        wobblePhase = rng.nextDouble() * 2.0 * PI
        altFreq = 1 + rng.nextInt(3)
        altPhase = rng.nextDouble() * 2.0 * PI

        for (i in 0 until gateCount) {
            val t = 2.0 * PI * i / gateCount
            centreAt(t, gateDir[i])
            gateAltKm[i] = altitudeAt(t)
            // Axis: the direction of travel, from a small step along the track.
            centreAt(t + 0.004, scratch)
            val r0 = SphereMath.EARTH_RADIUS_KM + gateAltKm[i]
            val r1 = SphereMath.EARTH_RADIUS_KM + altitudeAt(t + 0.004)
            gateAxis[i][0] = scratch[0] * r1 - gateDir[i][0] * r0
            gateAxis[i][1] = scratch[1] * r1 - gateDir[i][1] * r0
            gateAxis[i][2] = scratch[2] * r1 - gateDir[i][2] * r0
            SphereMath.normalize(gateAxis[i])

            // Orb: halfway to the next gate, pushed sideways so it costs a small detour.
            val tm = t + PI / gateCount
            centreAt(tm, orbDir[i])
            val side = if (rng.nextBoolean()) 1.0 else -1.0
            SphereMath.cross(orbDir[i], n, scratch2)
            SphereMath.normalize(scratch2)
            // scratch2 is along-track here; the lateral is n projected to the tangent plane.
            SphereMath.copy(n, scratch)
            SphereMath.tangentialize(scratch, orbDir[i])
            SphereMath.normalize(scratch)
            val lateral = side * ORB_LATERAL_RAD
            orbDir[i][0] += scratch[0] * lateral
            orbDir[i][1] += scratch[1] * lateral
            orbDir[i][2] += scratch[2] * lateral
            SphereMath.normalize(orbDir[i])
            orbAltKm[i] = altitudeAt(tm) + (rng.nextDouble() - 0.5) * 120.0
        }
    }

    /** The unit-vector track centre at parameter [t] (radians around the lap), into [out]. */
    fun centreAt(t: Double, out: DoubleArray) {
        val ct = cos(t)
        val st = sin(t)
        val lateral = wobbleAmp * sin(wobbleFreq * t + wobblePhase)
        val cl = cos(lateral)
        val sl = sin(lateral)
        out[0] = (e1[0] * ct + e2[0] * st) * cl + n[0] * sl
        out[1] = (e1[1] * ct + e2[1] * st) * cl + n[1] * sl
        out[2] = (e1[2] * ct + e2[2] * st) * cl + n[2] * sl
        SphereMath.normalize(out)
    }

    /** Track altitude at parameter [t], km. */
    fun altitudeAt(t: Double): Double = ALT_MID_KM + ALT_SWING_KM * sin(altFreq * t + altPhase)

    /** Lateral (cross-track) unit direction at [t], into [out]. */
    fun lateralAt(t: Double, out: DoubleArray) {
        centreAt(t, scratch)
        SphereMath.copy(n, out)
        SphereMath.tangentialize(out, scratch)
        SphereMath.normalize(out)
    }

    /**
     * Where along the lap a unit position is, in radians 0..2π: the angle of its projection onto
     * the track plane. Monotonic along the track, which is what ranking needs.
     */
    fun parameterOf(pos: DoubleArray): Double {
        val a = SphereMath.dot(pos, e1)
        val b = SphereMath.dot(pos, e2)
        var t = atan2(b, a)
        if (t < 0.0) t += 2.0 * PI
        // A hair below zero rounds up to exactly 2π; the lap is [0, 2π), so fold it back.
        if (t >= 2.0 * PI) t -= 2.0 * PI
        return t
    }

    /** Fraction of a lap (0..1) a unit position is around the track. */
    fun lapFractionOf(pos: DoubleArray): Double = parameterOf(pos) / (2.0 * PI)

    /** World position (km) of gate [i], into [out]. */
    fun gateWorld(i: Int, out: DoubleArray) {
        val r = SphereMath.EARTH_RADIUS_KM + gateAltKm[i]
        out[0] = gateDir[i][0] * r
        out[1] = gateDir[i][1] * r
        out[2] = gateDir[i][2] * r
    }

    fun orbWorld(i: Int, out: DoubleArray) {
        val r = SphereMath.EARTH_RADIUS_KM + orbAltKm[i]
        out[0] = orbDir[i][0] * r
        out[1] = orbDir[i][1] * r
        out[2] = orbDir[i][2] * r
    }

    /** A start position a little before gate 0, spread across [slot] of [slots] lanes. */
    fun startPosition(slot: Int, slots: Int, outDir: DoubleArray, outTangent: DoubleArray): Double {
        val t = -0.035 - slot * 0.006
        centreAt(t, outDir)
        // Lane offset: alternate sides of the corridor.
        lateralAt(t, scratch)
        val lane = ((slot % 2) * 2 - 1) * ((slot / 2) + 1) * 0.0035
        outDir[0] += scratch[0] * lane
        outDir[1] += scratch[1] * lane
        outDir[2] += scratch[2] * lane
        SphereMath.normalize(outDir)
        centreAt(t + 0.01, scratch)
        SphereMath.directionToward(outDir, scratch, outTangent)
        return altitudeAt(t)
    }

    /** The track's plane normal, for callers laying things out across the corridor. */
    fun normalInto(out: DoubleArray) = SphereMath.copy(n, out)

    companion object {
        const val GATE_COUNT: Int = 24
        const val GATE_RADIUS_KM: Double = 46.0
        const val ALT_MID_KM: Double = 700.0
        const val ALT_SWING_KM: Double = 260.0
        private const val ORB_LATERAL_RAD: Double = 0.012

        /** Today's shared track for the daily leaderboard: one seed per UTC day. */
        fun dailySeed(epochDay: Long): Long = 0x5A7E11L * 1_000_003L + epochDay * 7_919L
    }
}

/**
 * SplitMix64, so a room's track is bit-identical on every platform.
 *
 * `kotlin.random.Random(seed)` is also deterministic across platforms, but its algorithm is not
 * something the language promises to keep, and a track that differs by one bit between two phones
 * puts two players in the same room on different courses.
 */
internal class SeededRandom(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()

    fun nextInt(bound: Int): Int = (nextDouble() * bound).toInt().coerceIn(0, bound - 1)

    fun nextBoolean(): Boolean = nextLong() < 0
}
