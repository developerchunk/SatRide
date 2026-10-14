package com.developerstring.satride.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.use
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Satellite
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.orbit.Orbit
import com.developerstring.satride.feature.play.PlayGroup
import com.developerstring.satride.feature.play.engine.SatelliteField
import com.developerstring.satride.feature.play.render.CityLights
import com.developerstring.satride.feature.play.render.GlobeCamera
import com.developerstring.satride.feature.play.render.GlobePalette
import com.developerstring.satride.feature.play.render.GlobeRenderer
import com.developerstring.satride.feature.play.render.OrbitRingData
import com.developerstring.satride.feature.play.render.VectorEarth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Renders the Play globe offscreen and writes PNGs to `build/reports/play/`.
 *
 * Not a pixel-perfect golden test — it asserts the scene actually painted something (the
 * Earth disc is not black) so a broken projection or a dead `drawVertices` fails loudly, and
 * it leaves images behind for a human to look at after touching the renderer.
 */
@OptIn(ExperimentalComposeUiApi::class)
class GlobeRendererSnapshotTest {

    @Test
    fun rendersOrbitAndRideViews() {
        val resources = resourcesDir() ?: return // Files not found: environment without sources.
        val dayBytes = File(resources, "earth_day.jpg").readBytes()
        val nightBytes = File(resources, "earth_night.jpg").readBytes()
        val snapshotText = File(resources, "catalog_snapshot.json").readText()

        val tleText = Json.parseToJsonElement(snapshotText)
            .jsonObject.getValue("tle_text").jsonPrimitive.content
        val lines = tleText.lines().filter { it.isNotBlank() }
        val tles = ArrayList<Tle>()
        val satellites = ArrayList<Satellite>()
        var i = 0
        while (i + 2 < lines.size) {
            val name = lines[i].trim()
            val line1 = lines[i + 1]
            val line2 = lines[i + 2]
            runCatching {
                val parsed = Orbit.satellite(name, line1, line2)
                tles.add(Tle(name, parsed.noradId, line1, line2, parsed.epoch))
                satellites.add(
                    Satellite(
                        noradId = parsed.noradId,
                        name = name,
                        classType = if (parsed.noradId == ISS) SatClass.CREWED else SatClass.OTHER,
                    ),
                )
            }
            i += 3
        }
        assertTrue(tles.size > 50, "expected a real catalog, got ${tles.size} element sets")

        val field = SatelliteField()
        field.load(satellites, tles.associateBy { it.noradId })

        // Render at the catalog's own epoch so two-year-old element sets are not extrapolated.
        val simTime = tles.first { it.noradId == ISS }.epoch
        field.tick(simTime - 2.seconds)
        field.tick(simTime)

        val day = dayBytes.decodeToImageBitmap()
        val night = nightBytes.decodeToImageBitmap()
        val vectorEarth = VectorEarth.parse(
            listOf("earth_vec_lod0.bin", "earth_vec_lod1.bin", "earth_vec_lod2.bin")
                .map { File(resources, it).readBytes() },
        )
        assertTrue(vectorEarth != null, "vector surface files must parse")

        val cityLights = CityLights.parse(File(resources, "earth_lights.bin").readBytes())
        assertTrue(cityLights != null, "city lights must parse")

        val outDir = File("build/reports/play").apply { mkdirs() }
        val orbitShot = renderScene(field, day, night, vectorEarth, cityLights, simTime, riding = false)
        File(outDir, "globe_orbit.png").writeBytes(orbitShot.png)
        val rideShot = renderScene(field, day, night, vectorEarth, cityLights, simTime, riding = true)
        File(outDir, "globe_ride.png").writeBytes(rideShot.png)

        assertTrue(orbitShot.litFraction > 0.05, "orbit view painted ${orbitShot.litFraction}")
        assertTrue(rideShot.litFraction > 0.05, "ride view painted ${rideShot.litFraction}")
    }

    private class Shot(val png: ByteArray, val litFraction: Double)

    private fun renderScene(
        field: SatelliteField,
        day: ImageBitmap,
        night: ImageBitmap,
        vectorEarth: VectorEarth?,
        cityLights: CityLights?,
        simTime: Instant,
        riding: Boolean,
    ): Shot {
        val renderer = GlobeRenderer()
        renderer.setTextures(day, night)
        renderer.vectorEarth = vectorEarth
        renderer.cityLights = cityLights
        val camera = GlobeCamera()
        val palette = GlobePalette(
            station = Color(0xFF3DDCFF),
            weather = Color(0xFFFFB43D),
            amateur = Color(0xFF3DFFA8),
            navigation = Color(0xFFFF5FD2),
            cubesat = Color(0xFF9BB0D4),
            other = Color(0xFF75849E),
            selection = Color.White,
        )
        val propagator = field.propagatorFor(ISS)
        if (propagator != null) {
            val periodMin = 2.0 * PI / propagator.elements.meanMotionRadPerMin
            val points = 240
            val teme = DoubleArray(points * 3)
            for (k in 0 until points) {
                val state = propagator.stateAt(simTime + (periodMin * k / points).minutes)
                teme[k * 3] = state.position.x
                teme[k * 3 + 1] = state.position.y
                teme[k * 3 + 2] = state.position.z
            }
            renderer.orbitRing = OrbitRingData(ISS, teme, simTime.toEpochMilliseconds().toDouble())
        }

        val image = ImageComposeScene(width = 420, height = 900) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(0xFF02040A))
                renderer.render(
                    scope = this,
                    field = field,
                    simTime = simTime,
                    group = PlayGroup.ALL,
                    selectedNoradId = ISS,
                    riding = riding,
                    camera = camera,
                    palette = palette,
                )
            }
        }.use { it.render(0L) }

        val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
        val pixels = image.peekPixels()
        var lit = 0
        var total = 0
        if (pixels != null) {
            for (y in 0 until image.height step 4) {
                for (x in 0 until image.width step 4) {
                    total++
                    val color = pixels.getColor(x, y)
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    if (r + g + b > 60) lit++
                }
            }
        }
        return Shot(png, if (total == 0) 0.0 else lit.toDouble() / total)
    }

    private fun resourcesDir(): File? {
        var dir: File? = File("").absoluteFile
        repeat(4) {
            val candidate = File(dir, "composeApp/src/commonMain/composeResources/files")
            if (candidate.isDirectory) return candidate
            val local = File(dir, "src/commonMain/composeResources/files")
            if (local.isDirectory) return local
            dir = dir?.parentFile ?: return null
        }
        return null
    }

    private companion object {
        const val ISS = 25544
    }
}
