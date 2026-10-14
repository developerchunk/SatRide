package com.developerstring.satride.feature.play.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The night side's city lights, as points rather than pixels.
 *
 * The rest of the globe became geometry when the vector surface landed; the lights did not. They
 * stayed a 2048×1024 raster stretched across a 56×80 sphere mesh, which meant that the moment the
 * camera came in they smeared into orange fog beside coastlines that stayed perfectly sharp. The
 * mismatch is what read as broken — a uniformly soft globe would simply have looked stylised.
 *
 * A point has no resolution. Baked once from the same NASA Black Marble image by
 * `tools/citylights_earth.py`, twelve thousand of them cover every conurbation on Earth in 60 KB,
 * and they are as crisp from 200 km as from 200,000.
 *
 * Positions are packed unit vectors in the Earth-fixed frame, in the same layout
 * [VectorEarth.Ring] uses, so the renderer's existing rotate-and-project loop applies unchanged.
 */
class CityLights private constructor(
    /** Packed xyz unit vectors, three floats per light. */
    val xyz: FloatArray,
    /** Relative brightness, 0..1, one per light. */
    val intensity: FloatArray,
) {
    val count: Int get() = intensity.size

    companion object {
        private const val MAGIC = 0x534C4954 // 'SLIT'
        private const val DEG_TO_RAD = PI / 180.0

        /**
         * South of this there are no conurbations to light. The Black Marble source image the
         * bake reads has the aurora australis and moonlit sea ice over Antarctica, and the
         * extractor faithfully turned those glows into rings of phantom "cities" around the
         * pole. Antarctica's real lights — a handful of research stations — are dimmer than
         * anything else in the set, so cutting the latitude is honest rather than lossy.
         */
        private const val SOUTHERN_LIMIT_DEG = -60.0

        /** Parses `earth_lights.bin`; null on any mismatch, so a bad file degrades to no lights. */
        fun parse(data: ByteArray): CityLights? {
            val reader = BigEndianReader(data)
            if (reader.remaining < 8 || reader.readInt() != MAGIC) return null
            val count = reader.readInt()
            if (count < 0 || count > 200_000) return null
            // Five bytes each: two int16 centidegrees and one byte of brightness.
            if (reader.remaining < count * 5) return null

            val xyz = FloatArray(count * 3)
            val intensity = FloatArray(count)
            var kept = 0
            for (i in 0 until count) {
                val latDeg = reader.readShort() / 100.0
                val lonDeg = reader.readShort() / 100.0
                val peak = reader.readUByte()

                if (latDeg < SOUTHERN_LIMIT_DEG) continue

                val latRad = latDeg * DEG_TO_RAD
                val lonRad = lonDeg * DEG_TO_RAD
                val cosLat = cos(latRad)
                xyz[kept * 3] = (cosLat * cos(lonRad)).toFloat()
                xyz[kept * 3 + 1] = (cosLat * sin(lonRad)).toFloat()
                xyz[kept * 3 + 2] = sin(latRad).toFloat()

                // Square-rooted rather than linear. Brightness in the source spans four orders of
                // magnitude — Tokyo against a Highland village — and mapped linearly everything
                // but a dozen megacities renders as invisible. The curve is the same one the
                // original image's own tone mapping applies, undone.
                intensity[kept] = kotlin.math.sqrt(peak / 255f)
                kept++
            }
            return CityLights(xyz.copyOf(kept * 3), intensity.copyOf(kept))
        }
    }
}
