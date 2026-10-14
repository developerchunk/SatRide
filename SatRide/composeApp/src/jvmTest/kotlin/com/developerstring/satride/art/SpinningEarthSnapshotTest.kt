package com.developerstring.satride.art

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.developerstring.satride.core.designsystem.art.SpinningKurzEarth
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the splash screen's Earth at several rotations, to `build/reports/art/`.
 *
 * Not a pixel-golden test, for the same reason as the polar plot's: a checked-in reference image
 * fails on every font and rasteriser change and teaches everyone to regenerate it without looking.
 * It asserts the two things that would actually be broken if the drawing were wrong — that there
 * is land on the ocean at all, and that the land is in a *different place* a third of a turn later
 * — and leaves the frames behind for a human to look at.
 *
 * The second assertion is the one worth having. The obvious way to animate this planet is to slide
 * or spin a sprite, which looks wrong in a way no still frame reveals; and the obvious way to get
 * it wrong is to project the coastlines and then forget to feed the rotation in at all, which
 * produces a perfectly good-looking Earth that never turns.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SpinningEarthSnapshotTest {

    private val reports = File("build/reports/art").apply { mkdirs() }

    @Test
    fun theEarthHasContinentsAndTurns() {
        val frames = listOf(0f, 0.15f, 0.33f, 0.5f, 0.75f).map { turns ->
            turns to render(SIZE, "earth-${(turns * 100).toInt()}.png", turns)
        }

        frames.forEach { (turns, frame) ->
            assertTrue(
                frame.landFraction > 0.02,
                "no continents on the frame at $turns turns (${frame.landFraction})",
            )
        }

        // A third of a turn is 120° of longitude: nothing that is genuinely projected onto a
        // sphere can look the same before and after.
        val first = frames.first { it.first == 0f }.second
        val third = frames.first { it.first == 0.33f }.second
        assertTrue(
            first.differsFrom(third) > 0.03,
            "the Earth looks the same at 0 and 0.33 turns — it is not actually rotating",
        )
    }

    private class Frame(val landFraction: Double, val land: BooleanArray) {
        /** Fraction of pixels that are land in exactly one of the two frames. */
        fun differsFrom(other: Frame): Double {
            var changed = 0
            for (index in land.indices) if (land[index] != other.land[index]) changed++
            return changed.toDouble() / land.size
        }
    }

    private fun render(size: Int, name: String, turns: Float): Frame {
        val image: Image = ImageComposeScene(width = size, height = size) {
            SatRideTheme {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    SpinningKurzEarth(turns = { turns }, modifier = Modifier.size(size.dp))
                }
            }
        }.use { it.render(0L) }

        File(reports, name).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)

        // Land is the mint accent over the blue ocean, so "greener than it is blue" separates the
        // two without pinning the test to exact palette values.
        val pixels = image.peekPixels()
        val land = BooleanArray(size * size)
        var count = 0
        if (pixels != null) {
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val colour = pixels.getColor(x, y)
                    if ((colour ushr 24) and 0xFF < 8) continue
                    val green = (colour ushr 8) and 0xFF
                    val blue = colour and 0xFF
                    if (green > blue) {
                        land[y * size + x] = true
                        count++
                    }
                }
            }
        }
        return Frame(landFraction = count.toDouble() / (size * size), land = land)
    }

    private companion object {
        const val SIZE = 240
    }
}
