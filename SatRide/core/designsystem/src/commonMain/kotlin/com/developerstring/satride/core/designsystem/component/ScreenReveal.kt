package com.developerstring.satride.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.developerstring.satride.core.designsystem.theme.MotionSpec
import com.developerstring.satride.core.designsystem.theme.Spacing

/**
 * Paints a screen's shell on the navigation frame and its real content on the next one.
 *
 * ## The problem this exists for
 *
 * Arriving on a destination costs one very expensive frame: the incoming screen's entire first
 * composition — every row, every measure, every layer — lands on the same frame the navigation
 * itself is trying to draw. Nothing about that work is slow in isolation; it is slow because it is
 * all due at once, and the one frame it is due on is the one the user is watching. The screen
 * appears to hang, then snap in, and no amount of animation tuning fixes it because the animation
 * is not what is late.
 *
 * ## What it does instead
 *
 * The heavy [content] is not composed until the composition has survived one frame. So the
 * navigation frame draws only [skeleton] — a few shimmer rectangles, cheap enough to be free — and
 * the real work happens on the following frame, behind a skeleton that is already on screen. The
 * skeleton then fades out over the content.
 *
 * Only the skeleton is ever put in an alpha layer. Fading the *content* would defeat the purpose:
 * alpha < 1 forces the whole screen into an offscreen buffer for the duration of the fade, which
 * is exactly the cost being avoided.
 *
 * This is deliberately independent of whether the data is ready. Cached data is not free — laying
 * out four hundred rows costs the same whether the rows came from a network or from memory — so
 * the reveal is about *composition* cost, and it runs even when the state is already populated.
 * A screen with genuinely pending data shows its own loading state inside [content], as before.
 */
@Composable
fun ScreenReveal(
    modifier: Modifier = Modifier,
    skeleton: @Composable BoxScope.() -> Unit = {
        ShimmerList(Modifier.fillMaxSize().padding(Spacing.lg), rows = 7)
    },
    content: @Composable BoxScope.() -> Unit,
) {
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Waits for one frame to be *produced*, not for a duration. `withFrameNanos` resumes on
        // the frame callback, so this is one vsync however fast or slow the device is — a fixed
        // delay would be too long on a good device and too short on a bad one.
        withFrameNanos { }
        ready = true
    }

    val skeletonAlpha by animateFloatAsState(
        targetValue = if (ready) 0f else 1f,
        animationSpec = MotionSpec.fast(),
        label = "screenRevealSkeleton",
    )

    Box(modifier) {
        if (ready) content()

        if (skeletonAlpha > 0.01f) {
            Box(
                Modifier
                    .matchParentSize()
                    // Opaque: the skeleton is covering content that is already drawn beneath it,
                    // so a translucent one would show both at once.
                    .background(MaterialTheme.colorScheme.background)
                    .graphicsLayer { alpha = skeletonAlpha },
                content = skeleton,
            )
        }
    }
}
