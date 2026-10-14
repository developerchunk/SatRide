package com.developerstring.satride.feature.play.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Mean Earth radius, km — the sphere the globe renders. Oblateness is 0.3% and invisible here. */
internal const val EARTH_RADIUS_KM: Double = 6371.0

/**
 * A UV sphere in Earth-fixed coordinates, precomputed once.
 *
 * Laid out as parallel arrays rather than vertex objects so the per-frame transform loop is a
 * straight run over doubles with no pointer chasing. The seam column is duplicated (u=0 and u=1)
 * so texture interpolation never wraps backwards across the antimeridian, and the mapping puts
 * u=0.5 at Greenwich — matching an equirectangular Earth texture laid over ECEF axes.
 */
internal class SphereMesh(
    // Dense enough that ride mode's low-altitude views do not dissolve into visible facets:
    // Skia's drawVertices interpolates textures affinely per triangle, so triangle size is the
    // quality knob. ~4.6k vertices transform in well under a millisecond.
    val rings: Int = 56,
    val segments: Int = 80,
    radiusKm: Double = EARTH_RADIUS_KM,
) {
    val vertexCount: Int = (rings + 1) * (segments + 1)
    val faceCount: Int = rings * segments * 2

    /** Vertex positions, km, ECEF. */
    val px: DoubleArray = DoubleArray(vertexCount)
    val py: DoubleArray = DoubleArray(vertexCount)
    val pz: DoubleArray = DoubleArray(vertexCount)

    /** Unit normals — for a sphere, position over radius. */
    val nx: DoubleArray = DoubleArray(vertexCount)
    val ny: DoubleArray = DoubleArray(vertexCount)
    val nz: DoubleArray = DoubleArray(vertexCount)

    /** Texture coordinates in [0,1]; v=0 is the north pole. */
    val u: FloatArray = FloatArray(vertexCount)
    val v: FloatArray = FloatArray(vertexCount)

    /** Triangle vertex indices. */
    val fa: IntArray = IntArray(faceCount)
    val fb: IntArray = IntArray(faceCount)
    val fc: IntArray = IntArray(faceCount)

    /** Face centroids, km — the input to the backface test. */
    val cx: DoubleArray = DoubleArray(faceCount)
    val cy: DoubleArray = DoubleArray(faceCount)
    val cz: DoubleArray = DoubleArray(faceCount)

    /** Squared centroid length, precomputed for the backface test's inequality. */
    val c2: DoubleArray = DoubleArray(faceCount)

    init {
        var vi = 0
        for (ring in 0..rings) {
            val tv = ring.toDouble() / rings
            val lat = PI / 2.0 - PI * tv
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            for (seg in 0..segments) {
                val tu = seg.toDouble() / segments
                // u=0 at longitude -180 so the texture's left edge is the antimeridian.
                val lon = 2.0 * PI * tu - PI
                nx[vi] = cosLat * cos(lon)
                ny[vi] = cosLat * sin(lon)
                nz[vi] = sinLat
                px[vi] = nx[vi] * radiusKm
                py[vi] = ny[vi] * radiusKm
                pz[vi] = nz[vi] * radiusKm
                u[vi] = tu.toFloat()
                v[vi] = tv.toFloat()
                vi++
            }
        }

        var fi = 0
        val stride = segments + 1
        for (ring in 0 until rings) {
            for (seg in 0 until segments) {
                val a = ring * stride + seg
                val b = a + stride
                val c = b + 1
                val d = a + 1
                fa[fi] = a
                fb[fi] = b
                fc[fi] = c
                centroid(fi)
                fi++
                fa[fi] = a
                fb[fi] = c
                fc[fi] = d
                centroid(fi)
                fi++
            }
        }
    }

    private fun centroid(face: Int) {
        val a = fa[face]
        val b = fb[face]
        val c = fc[face]
        cx[face] = (px[a] + px[b] + px[c]) / 3.0
        cy[face] = (py[a] + py[b] + py[c]) / 3.0
        cz[face] = (pz[a] + pz[b] + pz[c]) / 3.0
        c2[face] = cx[face] * cx[face] + cy[face] * cy[face] + cz[face] * cz[face]
    }
}
