package com.developerstring.satride.passes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.developerstring.satride.core.designsystem.component.PolarPassPlot
import com.developerstring.satride.core.designsystem.component.PolarPlotStyle
import com.developerstring.satride.core.designsystem.component.SkyPoint
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the polar plot offscreen and writes PNGs to `build/reports/passes/`.
 *
 * Not a pixel-golden test — a checked-in reference image fails on every font-rendering change and
 * teaches everyone to regenerate it without looking. It asserts the things that would actually be
 * broken if the drawing were wrong: that something was painted at all, that the arc reaches the
 * middle of the disc for a pass that goes near the zenith, and that both sizes survive. The images
 * are left behind for a human to look at after touching the renderer — the same arrangement the
 * Play globe's snapshot test uses.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PolarPassPlotSnapshotTest {

    private val reports = File("build/reports/passes").apply { mkdirs() }

    /** Rises NE, culminates at 68° near the zenith, sets SW — the shape of a good ISS pass. */
    private val arc = listOf(
        SkyPoint(38f, 0f),
        SkyPoint(45f, 13f),
        SkyPoint(60f, 33f),
        SkyPoint(101f, 60f),
        SkyPoint(142f, 68f),
        SkyPoint(180f, 60f),
        SkyPoint(220f, 33f),
        SkyPoint(235f, 13f),
        SkyPoint(242f, 0f),
    )

    @Test
    fun theFullPlotDrawsItsRingsArcAndMarkers() {
        val shot = render(size = 240, name = "polar-full.png") {
            PolarPassPlot(arc = arc, style = PolarPlotStyle.FULL, live = arc[4], size = 240.dp)
        }

        assertTrue(shot.paintedFraction > 0.02, "the full plot painted almost nothing (${shot.paintedFraction})")
        // A broken projection's failure mode is a plot that is all rim and no arc, so the middle
        // of a near-zenith pass is the thing worth checking.
        assertTrue(shot.paintedNearCentre, "nothing drawn near the zenith")
    }

    @Test
    fun theThumbnailSurvivesFiftySixDp() {
        val shot = render(size = 56, name = "polar-thumbnail.png") {
            PolarPassPlot(arc = arc, style = PolarPlotStyle.THUMBNAIL, size = 56.dp)
        }

        // The thumbnail is the timeline's whole visual argument. If the arc vanishes at this size
        // every pass card is just a grey circle.
        assertTrue(shot.paintedFraction > 0.05, "the thumbnail painted almost nothing (${shot.paintedFraction})")
    }

    @Test
    fun anEmptyArcStillDrawsAReadablePlot() {
        val shot = render(size = 240, name = "polar-empty.png") {
            PolarPassPlot(arc = emptyList(), style = PolarPlotStyle.FULL, size = 240.dp)
        }

        // A satellite whose arc was not cached must leave rings behind, not a blank rectangle.
        assertTrue(shot.paintedFraction > 0.005, "the empty plot drew nothing at all")
    }

    private class Shot(val paintedFraction: Double, val paintedNearCentre: Boolean)

    private fun render(size: Int, name: String, content: @androidx.compose.runtime.Composable () -> Unit): Shot {
        val image: Image = ImageComposeScene(width = size, height = size) {
            SatRideTheme {
                Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
            }
        }.use { it.render(0L) }

        File(reports, name).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)

        val pixels = image.peekPixels()
        var painted = 0
        var total = 0
        var centre = false
        val centreRadius = size * 0.12
        if (pixels != null) {
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    total++
                    // Alpha, not brightness: the scene has no background, so anything the plot drew
                    // is the only thing that is opaque at all.
                    val opaque = (pixels.getColor(x, y) ushr 24) and 0xFF > 8
                    if (!opaque) continue
                    painted++
                    val dx = x - size / 2.0
                    val dy = y - size / 2.0
                    if (dx * dx + dy * dy < centreRadius * centreRadius) centre = true
                }
            }
        }
        return Shot(
            paintedFraction = if (total == 0) 0.0 else painted.toDouble() / total,
            paintedNearCentre = centre,
        )
    }
}
