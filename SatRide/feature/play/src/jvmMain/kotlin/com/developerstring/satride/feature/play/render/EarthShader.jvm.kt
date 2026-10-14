package com.developerstring.satride.feature.play.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.SamplingMode

/** Skia defaults to nearest-neighbour in shaders; ask for cubic resampling explicitly. */
internal actual fun setSmoothImageShader(paint: Paint, image: ImageBitmap) {
    paint.asFrameworkPaint().shader = Image.makeFromBitmap(image.asSkiaBitmap())
        .makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.MITCHELL)
}
