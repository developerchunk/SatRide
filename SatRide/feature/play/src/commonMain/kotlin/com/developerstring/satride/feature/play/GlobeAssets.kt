package com.developerstring.satride.feature.play

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.feature.play.render.CityLights
import com.developerstring.satride.feature.play.render.VectorEarth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * The globe's surface data, decoded once for the whole process.
 *
 * The Play tab and the Multi-Play arena draw the same planet. Each used to be able to decode its
 * own copy — three megabytes of vector rings and two textures, twice — so the arena reads them
 * from here and the Play view model keeps its own path for now; both are cheap to point at this
 * later. Application-scoped: the assets do not change and a tab switch must not re-parse them.
 */
class GlobeAssets(private val textureSource: EarthTextureSource, private val dispatchers: AppDispatchers) {
    private val logger = Logger.withTag("GlobeAssets")
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    var vectorEarth: VectorEarth? by mutableStateOf(null)
        private set
    var cityLights: CityLights? by mutableStateOf(null)
        private set
    var dayTexture: ImageBitmap? by mutableStateOf(null)
        private set
    var nightTexture: ImageBitmap? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /** Starts decoding if it has not started; safe to call from any screen, any number of times. */
    fun ensureLoaded() {
        if (job != null) return
        job = scope.launch {
            val files = EARTH_VECTOR_FILES.map { name ->
                textureSource.read(name) ?: run {
                    logger.w { "missing vector surface file $name" }
                    return@launch
                }
            }
            vectorEarth = VectorEarth.parse(files)
            cityLights = textureSource.read(EARTH_LIGHTS_FILE)?.let { CityLights.parse(it) }
        }
        scope.launch {
            // The bitmap fallback, only worth decoding when the vector surface is missing — but
            // the check is racy against the launch above, so decode the day texture regardless;
            // it is the fallback for a parse failure too.
            dayTexture = decode(EARTH_DAY_TEXTURE)
            nightTexture = decode(EARTH_NIGHT_TEXTURE)
        }
    }

    private suspend fun decode(fileName: String): ImageBitmap? = withContext(dispatchers.default) {
        runCatching { textureSource.read(fileName)?.decodeToImageBitmap() }
            .onFailure { logger.w(it) { "cannot decode $fileName" } }
            .getOrNull()
    }
}
