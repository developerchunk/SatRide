package com.developerstring.satride.feature.play.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The globe's surface as geometry, not pixels.
 *
 * Continents, deserts, glaciers and lakes are polygon rings on the unit sphere, preprocessed
 * from Natural Earth by `tools/vectorize_earth.py` into a few LOD tiers. Rendering them as
 * filled vector paths is what keeps ride-mode close-ups perfectly crisp — a bitmap can only
 * blur under that much magnification, geometry cannot.
 *
 * Each ring carries a bounding cone (axis + cos of angular radius) so the renderer can reject
 * everything outside the visible spherical cap before touching a single vertex.
 */
class VectorEarth private constructor(val lods: List<Lod>) {

    /** One detail tier. [layers] is indexed by [LAYER_LAND] … [LAYER_LAKE]. */
    class Lod(val layers: List<List<Ring>>, val maxRingPoints: Int)

    /**
     * A closed polygon on the unit sphere: packed xyz unit vectors plus its bounding cone.
     */
    class Ring(
        val xyz: FloatArray,
        val axisX: Float,
        val axisY: Float,
        val axisZ: Float,
        /** cos of the cone's angular radius; 1 = a point, smaller = wider. */
        val cosRadius: Float,
    ) {
        val pointCount: Int get() = xyz.size / 3

        /** The cone's angular radius in radians, precomputed for the per-frame cull. */
        val radiusAngle: Float = kotlin.math.acos(cosRadius.coerceIn(-1f, 1f))
    }

    companion object {
        const val LAYER_LAND = 0
        const val LAYER_DESERT = 1
        const val LAYER_GLACIER = 2
        const val LAYER_LAKE = 3

        private const val MAGIC = 0x53564543 // 'SVEC'
        private const val DEG_TO_RAD = PI / 180.0

        /** Parses the LOD files produced by tools/vectorize_earth.py; null on any mismatch. */
        fun parse(files: List<ByteArray>): VectorEarth? {
            val lods = files.map { parseLod(it) ?: return null }
            return VectorEarth(lods)
        }

        private fun parseLod(data: ByteArray): Lod? {
            val reader = BigEndianReader(data)
            if (reader.remaining < 8 || reader.readInt() != MAGIC) return null
            val layerCount = reader.readInt()
            if (layerCount !in 1..8) return null
            var maxPoints = 0
            val layers = ArrayList<List<Ring>>(layerCount)
            repeat(layerCount) {
                val ringCount = reader.readInt()
                if (ringCount < 0 || ringCount > 1_000_000) return null
                val rings = ArrayList<Ring>(ringCount)
                repeat(ringCount) {
                    val pointCount = reader.readInt()
                    if (pointCount < 3 || reader.remaining < pointCount * 8) return null
                    val xyz = FloatArray(pointCount * 3)
                    var sumX = 0.0
                    var sumY = 0.0
                    var sumZ = 0.0
                    for (p in 0 until pointCount) {
                        val lat = reader.readFloat() * DEG_TO_RAD
                        val lon = reader.readFloat() * DEG_TO_RAD
                        val cosLat = cos(lat)
                        val x = (cosLat * cos(lon)).toFloat()
                        val y = (cosLat * sin(lon)).toFloat()
                        val z = sin(lat).toFloat()
                        xyz[p * 3] = x
                        xyz[p * 3 + 1] = y
                        xyz[p * 3 + 2] = z
                        sumX += x
                        sumY += y
                        sumZ += z
                    }
                    val mag = sqrt(sumX * sumX + sumY * sumY + sumZ * sumZ).coerceAtLeast(1e-9)
                    val ax = (sumX / mag).toFloat()
                    val ay = (sumY / mag).toFloat()
                    val az = (sumZ / mag).toFloat()
                    var minDot = 1f
                    for (p in 0 until pointCount) {
                        val d = ax * xyz[p * 3] + ay * xyz[p * 3 + 1] + az * xyz[p * 3 + 2]
                        if (d < minDot) minDot = d
                    }
                    if (pointCount > maxPoints) maxPoints = pointCount
                    rings.add(Ring(xyz, ax, ay, az, minDot))
                }
                layers.add(rings)
            }
            return Lod(layers, maxPoints)
        }
    }
}

/**
 * Big-endian cursor over a bundled binary.
 *
 * Shared by the surface rings and the city lights, which are produced by two scripts writing the
 * same byte order — a second, subtly different reader is exactly how one of them would end up
 * parsing garbage without failing.
 */
internal class BigEndianReader(private val data: ByteArray) {
    private var offset = 0

    val remaining: Int get() = data.size - offset

    fun readInt(): Int {
        val v = ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
        offset += 4
        return v
    }

    fun readFloat(): Float = Float.fromBits(readInt())

    /** Signed 16-bit. Used for centidegree latitudes and longitudes. */
    fun readShort(): Int {
        val v = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        // Sign-extend: Kotlin has no int16, so the high bit has to be carried by hand.
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    fun readUByte(): Int = data[offset++].toInt() and 0xFF
}
