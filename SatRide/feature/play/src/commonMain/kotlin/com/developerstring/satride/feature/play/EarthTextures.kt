package com.developerstring.satride.feature.play

/**
 * Supplies the bundled Earth textures, per platform.
 *
 * Mirrors `CatalogSnapshotSource`: the files live once in composeApp's resources and each
 * platform reads them its own way, because Compose Resources does not package `commonMain`
 * files into Android assets under the AGP KMP plugin. composeApp registers the implementation.
 */
interface EarthTextureSource {
    /** Raw encoded bytes of a bundled texture, or null when it cannot be read. */
    suspend fun read(fileName: String): ByteArray?
}

/** NASA Blue Marble, equirectangular, 2048×1024. Public domain (NASA Earth Observatory). */
const val EARTH_DAY_TEXTURE: String = "earth_day.jpg"

/** NASA Black Marble night lights, equirectangular. Public domain. */
const val EARTH_NIGHT_TEXTURE: String = "earth_night.jpg"

/**
 * The vector surface LOD tiers, coarse → fine, produced by `tools/vectorize_earth.py` from
 * Natural Earth data (public domain). Order matters: the renderer indexes them by detail.
 */
val EARTH_VECTOR_FILES: List<String> = listOf(
    "earth_vec_lod0.bin",
    "earth_vec_lod1.bin",
    "earth_vec_lod2.bin",
)

/**
 * City lights as points, produced by `tools/citylights_earth.py` from the same NASA Black Marble
 * image the night texture came from. Replaces that texture on the vector path — a point stays
 * sharp at any magnification, a 2048×1024 raster stretched over a globe does not.
 */
const val EARTH_LIGHTS_FILE: String = "earth_lights.bin"
