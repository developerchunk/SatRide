package com.developerstring.satride.feature.play.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint

/**
 * Installs an image shader on [paint] that samples smoothly under heavy magnification.
 *
 * Compose's common `ImageShader` gives no control over sampling, and the two backends disagree
 * about the default: Android's `BitmapShader` honours the paint's filter flag, while Skia's
 * `makeShader` defaults to nearest-neighbour — which turns ride mode's magnified ground into
 * visible texel blocks on desktop and iOS. The Skiko actuals request cubic sampling explicitly.
 */
internal expect fun setSmoothImageShader(paint: Paint, image: ImageBitmap)
