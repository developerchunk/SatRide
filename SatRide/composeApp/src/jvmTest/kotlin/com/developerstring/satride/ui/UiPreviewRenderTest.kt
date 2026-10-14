package com.developerstring.satride.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.developerstring.satride.core.designsystem.art.KurzEarth
import com.developerstring.satride.core.designsystem.art.KurzGroundStation
import com.developerstring.satride.core.designsystem.art.KurzMoon
import com.developerstring.satride.core.designsystem.art.KurzPlanet
import com.developerstring.satride.core.designsystem.art.KurzRocket
import com.developerstring.satride.core.designsystem.art.KurzSatellite
import com.developerstring.satride.core.designsystem.art.SpaceBackground
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.PhasePlaceholder
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.lens
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the illustrated design system offscreen and writes PNGs to `build/reports/uipreview/`.
 *
 * Same philosophy as [com.developerstring.satride.play.GlobeRendererSnapshotTest]: not a golden
 * test, but a loud failure if a scene paints nothing, plus images a human can look at after
 * touching the theme or the art library.
 */
@OptIn(ExperimentalComposeUiApi::class)
class UiPreviewRenderTest {

    @Test
    fun rendersIllustratedSurfaces() {
        render("signin-intro", 420, 900) {
            com.developerstring.satride.feature.auth.SignInContent(
                signingIn = false,
                signInAvailable = true,
                error = null,
                onGoogle = {},
                onSkip = {},
            )
        }

        // The lens proof: a large glass circle over a stripe grid. If the stripes do not bend
        // and colour-split at the circle's rim, the refraction pipeline is broken — a small
        // moving bubble can hide that; this frame cannot.
        render("glass-lab", 420, 420) { GlassLab() }

        render("signin-ready", 420, 900) {
            com.developerstring.satride.feature.auth.SignInContent(
                signingIn = false,
                signInAvailable = true,
                error = null,
                onGoogle = {},
                onSkip = {},
                initialPhase = com.developerstring.satride.feature.auth.SignInPhase.Ready,
            )
        }

        render("placeholder", 420, 800) {
            PhasePlaceholder(
                title = "Radio",
                phase = "P8",
                description = "Live RF from an RTL-SDR, tuned by the pass timeline.",
            )
        }

        render("art-gallery", 420, 800) {
            SpaceBackground(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        KurzEarth(Modifier.size(96.dp))
                        KurzMoon(Modifier.size(96.dp))
                        KurzPlanet(Modifier.size(96.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        KurzSatellite(blink = 0.6f, modifier = Modifier.size(96.dp))
                        KurzRocket(flame = 0.7f, modifier = Modifier.size(96.dp))
                        KurzGroundStation(waves = 0.4f, modifier = Modifier.size(96.dp))
                    }
                    GlassPanel {
                        Text("A toy panel", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                        Text(
                            "Solid fill, chunky border, shadow edge.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        AccentChip(label = "UP NOW", accent = SatRideTheme.accents.success)
                        AccentChip(label = "NEW", accent = SatRideTheme.accents.sunshine)
                        AccentChip(label = "RADIO", accent = SatRideTheme.accents.radio)
                    }
                    SatRideButton(text = "Primary action", onClick = {})
                    SatRideButton(text = "Secondary action", onClick = {}, style = SatRideButtonStyle.Secondary)
                }
            }
        }
    }

    @Composable
    private fun GlassLab() {
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize()) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                val stripe = 24f
                var i = 0
                var x = 0f
                while (x < size.width) {
                    drawRect(
                        color = if (i % 2 == 0) Color(0xFF262E68) else Color(0xFFFFC94D),
                        topLeft = Offset(x, 0f),
                        size = androidx.compose.ui.geometry.Size(stripe, size.height),
                    )
                    x += stripe
                    i++
                }
            }
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(240.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { CircleShape },
                        effects = {
                            lens(60.dp.toPx(), 120.dp.toPx(), chromaticAberration = true)
                        },
                    ),
            )
        }
    }

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val image = ImageComposeScene(width = width, height = height) {
            SatRideTheme { content() }
        }.use { scene ->
            // Entrance animations gate on real-time `delay`s and then animate on the frame
            // clock, so settle both: sleep a little wall-clock time between frames while
            // advancing the scene clock ~2.4s, and capture the last frame.
            var frame = scene.render(nanoTime = 0L)
            repeat(12) { step ->
                Thread.sleep(60)
                frame = scene.render(nanoTime = (step + 1) * 200_000_000L)
            }
            frame
        }

        assertTrue(hasInk(image.toComposeImageBitmap()), "$name painted nothing")

        val out = File("build/reports/uipreview").apply { mkdirs() }.resolve("$name.png")
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("wrote ${out.absolutePath}")
    }

    /** True if any sampled pixel differs from the first — i.e. the scene is not a flat fill. */
    private fun hasInk(bitmap: ImageBitmap): Boolean {
        val pixels = bitmap.toPixelMap()
        val first = pixels[0, 0]
        for (y in 0 until pixels.height step 16) {
            for (x in 0 until pixels.width step 16) {
                if (pixels[x, y] != first) return true
            }
        }
        return false
    }
}
