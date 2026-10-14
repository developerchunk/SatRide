package com.developerstring.satride.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.use
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.feature.play.multiplay.ui.VirtualStick
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The arena's steering stick, rendered offscreen to `build/reports/multiplay/`.
 *
 * Rendered twice, over black *and* over a bright fill, and that second frame is the whole point:
 * the control used to be a six-per-cent-white pad, which is invisible over the lit limb of the
 * Earth — exactly where the arena puts it. The game's primary control could not be found, and no
 * test in the project could have noticed, because "did it paint anything" is satisfied by the
 * background.
 *
 * So the assertion is about *contrast* rather than ink. The rules card's wording is covered by
 * `HowToPlayTest` in `feature:play`, where it is visible.
 */
@OptIn(ExperimentalComposeUiApi::class)
class MultiPlayUiRenderTest {

    @Test
    fun theSteeringStickIsVisibleOverAnyBackground() {
        val overSpace = frame(220, 220) {
            Box(Modifier.fillMaxSize().background(Color(0xFF060B1E))) {
                VirtualStick(onChange = { _, _ -> }, idleLabel = "STEER")
            }
        }
        val overDaylight = frame(220, 220) {
            // The lit Earth, near enough: the brightest thing the arena ever puts behind the pad.
            Box(Modifier.fillMaxSize().background(Color(0xFF4FA3F7))) {
                VirtualStick(onChange = { _, _ -> }, idleLabel = "STEER")
            }
        }

        write("stick-over-space", overSpace)
        write("stick-over-daylight", overDaylight)

        // Contrast, not ink: over a bright background a translucent pad still "has ink" from the
        // background itself, so the assertion has to be that the stick is *darker* than what is
        // behind it — which is only true if it paints its own opaque disc.
        assertTrue(
            centreIsDarkerThanCorner(overDaylight),
            "the stick vanishes over a bright background; it must paint an opaque pad",
        )
        assertTrue(hasInk(overSpace.toComposeImageBitmap()), "the stick painted nothing over space")
    }

    private fun centreIsDarkerThanCorner(image: org.jetbrains.skia.Image): Boolean {
        val pixels = image.toComposeImageBitmap().toPixelMap()
        val centre = pixels[pixels.width / 2, pixels.height / 2]
        val corner = pixels[2, 2]
        fun luma(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        return luma(centre) < luma(corner) - 0.15f
    }

    private fun frame(width: Int, height: Int, content: @Composable () -> Unit): org.jetbrains.skia.Image =
        ImageComposeScene(width = width, height = height) {
            SatRideTheme { content() }
        }.use { scene ->
            var frame = scene.render(nanoTime = 0L)
            repeat(6) { step ->
                Thread.sleep(30)
                frame = scene.render(nanoTime = (step + 1) * 200_000_000L)
            }
            frame
        }

    private fun write(name: String, image: org.jetbrains.skia.Image) {
        val out = File("build/reports/multiplay").apply { mkdirs() }.resolve("$name.png")
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("wrote ${out.absolutePath}")
    }

    /** True if any sampled pixel differs from the first — i.e. the scene is not a flat fill. */
    private fun hasInk(bitmap: ImageBitmap): Boolean {
        val pixels = bitmap.toPixelMap()
        val first = pixels[0, 0]
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                if (pixels[x, y] != first) return true
            }
        }
        return false
    }
}
