package com.developerstring.satride.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.developerstring.satride.core.designsystem.art.SpinningKurzEarth
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the spinning Earth at eight points around one rotation.
 *
 * Two jobs. The images land in `build/reports/earth/` so a human can check that the continents
 * look like continents and that no face of the planet is blank — the sort of thing no assertion
 * catches.
 *
 * The assertion catches the failure that *is* mechanical: every frame must contain land. Vertices
 * that pass behind the limb are clamped onto it, and an outline spanning more than a hemisphere
 * collapses into a slab when that happens. Sampling a single rotation would miss it, because the
 * bug only shows at the angles where that particular continent is halfway round the back — which
 * is exactly why the continents are cut into sub-hemisphere pieces.
 */
@OptIn(ExperimentalComposeUiApi::class)
class EarthRotationRenderTest {

    @Test
    fun everyRotationShowsLand() {
        val outputDir = File("build/reports/earth").apply { mkdirs() }
        val frames = 8

        repeat(frames) { frame ->
            val turns = frame / frames.toFloat()

            val image = ImageComposeScene(width = 320, height = 320) {
                SatRideTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SpinningKurzEarth(turns = { turns }, modifier = Modifier.size(300.dp))
                    }
                }
            }.use { it.render() }

            val degrees = (turns * 360).toInt()
            outputDir.resolve("earth-${degrees.toString().padStart(3, '0')}.png")
                .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)

            // Land is the mint accent over a blue ocean, so "clearly greener than blue" separates
            // coastline from sea and from the transparent background without hard-coding either.
            val pixels = image.toComposeImageBitmap().toPixelMap()
            var land = 0
            for (y in 0 until pixels.height step 2) {
                for (x in 0 until pixels.width step 2) {
                    if (pixels[x, y].isLandish()) land++
                }
            }

            assertTrue(
                land > 150,
                "at $degrees° the globe drew almost no land ($land samples) — an outline is " +
                    "probably collapsing onto the limb",
            )
        }
        println("wrote ${outputDir.absolutePath}")
    }
}

private fun Color.isLandish(): Boolean = alpha > 0.5f && green > 0.45f && green > blue * 1.05f
