package com.developerstring.satride.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.use
import com.developerstring.satride.core.common.DefaultAppDispatchers
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.feature.play.EarthTextureSource
import com.developerstring.satride.feature.play.GlobeAssets
import com.developerstring.satride.feature.play.multiplay.engine.GameMode
import com.developerstring.satride.feature.play.multiplay.engine.GameSession
import com.developerstring.satride.feature.play.multiplay.render.ArenaRenderer
import com.developerstring.satride.feature.play.multiplay.render.ChaseCamera
import com.developerstring.satride.feature.play.multiplay.render.LookState
import com.developerstring.satride.feature.play.render.CameraPose
import com.developerstring.satride.feature.play.render.GlobeRenderer
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Renders the Multi-Play arena offscreen and writes PNGs to `build/reports/play/arena_*.png`.
 *
 * Same idea as [GlobeRendererSnapshotTest]: not a golden image, a proof that the chase camera,
 * the gates, the debris models and the craft all project onto the frame — and a picture for a
 * human to look at after touching any of them.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ArenaRendererSnapshotTest {

    @Test
    fun rendersAGridStartAndAMidRaceFrame() {
        val resources = resourcesDir() ?: return
        val assets = GlobeAssets(
            textureSource = object : EarthTextureSource {
                override suspend fun read(fileName: String): ByteArray? =
                    File(resources, fileName).takeIf { it.isFile }?.readBytes()
            },
            dispatchers = DefaultAppDispatchers(),
        )
        assets.ensureLoaded()
        // The loader is asynchronous; wait for the vector surface, which is what the frame needs.
        runBlocking {
            var waited = 0
            while (assets.vectorEarth == null && waited < 200) {
                kotlinx.coroutines.delay(50)
                waited++
            }
        }
        assertTrue(assets.vectorEarth != null, "vector surface must load for the arena")

        val session = GameSession(
            mode = GameMode.RACE,
            seed = 20260904L,
            localUid = "me",
            localName = "Me",
            localColorArgb = 0xFF3FE0FF,
            localSkin = SatClass.CREWED,
            botCount = 4,
            botSeed = 77L,
            localSlot = 0,
        )
        val outDir = File("build/reports/play").apply { mkdirs() }
        val simTime = Instant.fromEpochMilliseconds(1_756_000_000_000L)

        // Frame 1: on the grid, lights out in a second.
        session.schedule(startMs = 1000L, endMs = 600_000L, nowMs = 0L)
        session.tick(16L)
        val grid = renderFrame(session, assets, simTime)
        File(outDir, "arena_grid.png").writeBytes(grid.png)

        // Frame 2: forty seconds into the race, full throttle, mid-turn.
        session.localInput.throttle = 1.0
        session.localInput.steer = 0.35
        var now = 1000L
        repeat(40 * 60) {
            now += 16L
            session.tick(now)
        }
        val race = renderFrame(session, assets, simTime)
        File(outDir, "arena_race.png").writeBytes(race.png)

        // Frame 3: the same moment looking steeply down at the ground — the view that used to
        // show black boxes where the sphere mesh met the near plane.
        val down = renderFrame(session, assets, simTime, LookState().apply { pitchDeg = -55.0 })
        File(outDir, "arena_down.png").writeBytes(down.png)
        assertTrue(down.litFraction > 0.4, "looking down should be mostly planet, painted ${down.litFraction}")

        assertTrue(grid.litFraction > 0.05, "grid frame painted ${grid.litFraction}")
        assertTrue(race.litFraction > 0.05, "race frame painted ${race.litFraction}")
        assertTrue(session.local.speedKmS > 100.0, "the craft should be flying: ${session.local.speedKmS}")
    }

    private class Shot(val png: ByteArray, val litFraction: Double)

    private fun renderFrame(
        session: GameSession,
        assets: GlobeAssets,
        simTime: Instant,
        look: LookState = LookState(),
    ): Shot {
        val renderer = ArenaRenderer(GlobeRenderer())
        val camera = ChaseCamera()
        val pose = CameraPose()
        camera.snapTo(session.local)
        // A long step so the eased camera fully settles on the requested look.
        camera.update(session.local, look, 5.0, pose, boosting = false, spinning = false)

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
