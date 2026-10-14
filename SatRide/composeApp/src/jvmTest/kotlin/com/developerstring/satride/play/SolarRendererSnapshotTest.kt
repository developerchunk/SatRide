package com.developerstring.satride.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.use
import com.developerstring.satride.core.common.DefaultAppDispatchers
import com.developerstring.satride.feature.play.EarthTextureSource
import com.developerstring.satride.feature.play.GlobeAssets
import com.developerstring.satride.feature.play.multiplay.render.LookState
import com.developerstring.satride.feature.play.multiplay.solar.PilotMode
import com.developerstring.satride.feature.play.multiplay.solar.SolarChaseCamera
import com.developerstring.satride.feature.play.multiplay.solar.SolarRenderer
import com.developerstring.satride.feature.play.multiplay.solar.SolarSession
import com.developerstring.satride.feature.play.render.CameraPose
import com.developerstring.satride.feature.play.render.GlobeRenderer
import com.developerstring.satride.feature.play.solar.Body
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Renders the Solar System arena offscreen to `build/reports/play/solar_*.png`: the starship in
 * Earth orbit (the real vector Earth beneath it), parked at Saturn (rings), and skimming the
 * Sun. Not golden images — proof that every layer projects, and pictures for a human.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SolarRendererSnapshotTest {

    @Test
    fun rendersEarthOrbitSaturnAndTheSun() {
        val resources = resourcesDir() ?: return
        val assets = GlobeAssets(
            textureSource = object : EarthTextureSource {
                override suspend fun read(fileName: String): ByteArray? =
                    File(resources, fileName).takeIf { it.isFile }?.readBytes()
            },
            dispatchers = DefaultAppDispatchers(),
        )
        assets.ensureLoaded()
        runBlocking {
            var waited = 0
            while (assets.vectorEarth == null && waited < 200) {
                kotlinx.coroutines.delay(50)
                waited++
            }
        }
        assertTrue(assets.vectorEarth != null, "vector surface must load")

        val t0 = 1_788_000_000_000L
        val simTime = Instant.fromEpochMilliseconds(t0)
        val session = SolarSession(
            seed = 1L,
            localUid = "me",
            localName = "Me",
            localColorArgb = 0xFF3FE0FF,
            botCount = 3,
            botSeed = 2L,
            localSlot = 0,
        )
        session.schedule(startMs = t0, endMs = t0 + 3_600_000L, nowMs = t0)
        val outDir = File("build/reports/play").apply { mkdirs() }

        // Frame 1: in orbit around the Earth, a second in.
        var now = t0
        repeat(60) {
            now += 16L
            session.tick(now)
        }
        val earth = renderFrame(session, assets, simTime)
        File(outDir, "solar_earth_orbit.png").writeBytes(earth.png)
        assertTrue(earth.litFraction > 0.08, "Earth orbit frame painted ${earth.litFraction}")

        // Frame 2: parked at Saturn, looking down over the rings.
        session.setTarget(Body.SATURN)
        session.setPilotMode(PilotMode.AUTO)
        session.setWarp(1000.0)
        repeat(60 * 60) {
            now += 16L
            session.tick(now)
        }
        assertTrue(
            session.world.distanceTo(Body.SATURN, session.local.pos[0], session.local.pos[1], session.local.pos[2]) <
                Body.SATURN.parkRadiusKm * 1.1,
            "should have arrived at Saturn",
        )
        val saturn = renderFrame(session, assets, simTime, LookState().apply { pitchDeg = -20.0 })
        File(outDir, "solar_saturn.png").writeBytes(saturn.png)
        assertTrue(saturn.litFraction > 0.06, "Saturn frame painted ${saturn.litFraction}")

        // Frame 3: the Sun from its parking orbit.
        session.setTarget(Body.SUN)
        session.setPilotMode(PilotMode.AUTO)
        session.setWarp(1000.0)
        repeat(60 * 60) {
            now += 16L
            session.tick(now)
        }
        val sun = renderFrame(session, assets, simTime, LookState().apply { pitchDeg = -25.0 })
        File(outDir, "solar_sun.png").writeBytes(sun.png)
        assertTrue(sun.litFraction > 0.15, "Sun frame painted ${sun.litFraction}")
        assertTrue(
            session.localProgress.visited[Body.SATURN.ordinal] && session.localProgress.visited[Body.SUN.ordinal],
        )
    }

    private class Shot(val png: ByteArray, val litFraction: Double)

    private fun renderFrame(
        session: SolarSession,
        assets: GlobeAssets,
        simTime: Instant,
        look: LookState = LookState(),
    ): Shot {
        val renderer = SolarRenderer(GlobeRenderer())
        val camera = SolarChaseCamera()
        val pose = CameraPose()
        camera.snapTo(session.local)
        camera.update(session.local, look, 5.0, pose, orbiting = true)

        val image = ImageComposeScene(width = 1280, height = 600) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(0xFF060B1E))
                renderer.render(
                    scope = this,
                    session = session,
                    pose = pose,
                    assets = assets,
                    simTime = simTime,
                    frameNanos = 1_000_000_000L,
                    nowMs = 0L,
                    localColor = Color(0xFF3FE0FF),
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
}
