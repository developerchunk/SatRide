package com.developerstring.satride.core.designsystem.art

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.developerstring.satride.core.designsystem.theme.SatRideTheme

/**
 * The five planets baked once into bitmaps.
 *
 * For scenes that draw many planets every frame (the sign-in bubble stream), re-tessellating
 * dozens of vector paths per planet per frame is the difference between 60 fps and jank on a
 * phone — a baked bitmap turns each planet into a single `drawImage`. Bake size is generous
 * enough that bubbles never magnify past 1:1.
 */
@Composable
fun rememberPlanetBitmaps(sizePx: Int = 512): List<ImageBitmap> {
    val a = SatRideTheme.accents
    val moonLit = Color(0xFFB9C4E8)
    return remember(a, sizePx) {
        val painters: List<DrawScope.() -> Unit> = listOf(
            { drawKurzEarth(a.skyBlue, a.mint, a.tracking, a.shadow) },
            { drawKurzMoon(moonLit, a.shadow) },
            { drawKurzPlanet(a.lavender, a.sunshine, a.shadow) },
            { drawKurzGasGiant(a.sunshine, a.coral, a.shadow) },
            { drawKurzPlanet(a.skyBlue, a.mint, a.shadow) },
        )
        painters.map { paint ->
            val bitmap = ImageBitmap(sizePx, sizePx)
            CanvasDrawScope().draw(
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                canvas = Canvas(bitmap),
                size = Size(sizePx.toFloat(), sizePx.toFloat()),
            ) {
                paint()
            }
            bitmap
        }
    }
}
