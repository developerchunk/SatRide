package com.developerstring.satride.feature.play.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode

/** Android's BitmapShader filters bilinearly whenever the paint's filter quality is not None. */
internal actual fun setSmoothImageShader(paint: Paint, image: ImageBitmap) {
    paint.shader = ImageShader(image, TileMode.Clamp, TileMode.Clamp)
}
