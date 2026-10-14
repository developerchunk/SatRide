package com.developerstring.satride.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing

/**
 * A placeholder for something that is on its way.
 *
 * A shimmer, deliberately, and not a spinner. The two say different things: a spinner says *wait*,
 * and is right when the user cannot proceed; a shimmer says *this is coming*, and is right when the
 * screen is already usable and one part of it is still filling in. After the eight-second startup
 * cap, every screen is in the second situation by definition — the app has handed over deliberately
 * while work continues behind it.
 *
 * The rule that goes with it: a shimmer must never stand in for **empty**. "No passes tonight" is an
 * answer, and dressing it as a wait makes the user sit watching a screen that has already told them
 * everything it knows.
 */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Slow, and linear. A fast or eased shimmer reads as urgency, which is the opposite of
            // what this is for — the app is working, and the user is not being asked to hurry.
            animation = tween(durationMillis = SWEEP_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )

    val base = SatRideTheme.accents.hairline
    val highlight = SatRideTheme.accents.tracking.copy(alpha = 0.10f)

    // A diagonal sweep whose start and end are pushed well outside the box, so the band travels all
    // the way across instead of appearing and vanishing at the edges.
    val start = -SWEEP_WIDTH + progress * (1f + 2f * SWEEP_WIDTH) * SWEEP_SCALE
    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(start, start),
        end = Offset(start + SWEEP_WIDTH * SWEEP_SCALE, start + SWEEP_WIDTH * SWEEP_SCALE),
    )

    androidx.compose.foundation.layout.Box(modifier.background(brush, shape))
}

/**
 * A stand-in for a list that has not arrived.
 *
 * Sized like the rows it replaces, because a placeholder of the wrong height makes the content jump
 * when it lands — which is more distracting than the wait it was meant to soften.
 */
@Composable
fun ShimmerList(modifier: Modifier = Modifier, rows: Int = 5, rowHeight: androidx.compose.ui.unit.Dp = 84.dp) {
    Column(modifier.fillMaxWidth()) {
        repeat(rows) { index ->
            ShimmerBox(
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .padding(bottom = if (index == rows - 1) 0.dp else Spacing.sm),
                shape = RoundedCornerShape(14.dp),
            )
        }
    }
}

/** How long one sweep takes, in milliseconds. */
private const val SWEEP_MILLIS = 1_400

private const val SWEEP_WIDTH = 0.35f

/**
 * Gradient offsets are in pixels, and this component has no measured size to work from, so the
 * sweep is expressed against a nominal span wide enough to cover any row the app draws.
 */
private const val SWEEP_SCALE = 1_200f
