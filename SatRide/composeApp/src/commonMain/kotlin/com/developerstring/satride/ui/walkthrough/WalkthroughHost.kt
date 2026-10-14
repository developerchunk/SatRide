package com.developerstring.satride.ui.walkthrough

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.haptics.LocalSatRideHaptics
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import kotlin.math.roundToInt

/**
 * Wraps the app shell and draws the walkthrough over it.
 *
 * This has to be the composition root, or as near as makes no difference: coach targets report
 * their bounds with `boundsInRoot`, so an overlay drawn anywhere that is itself offset — inside
 * the scaffold's content slot, say, which starts below the top bar — would place every spotlight
 * off by the height of that offset.
 */
@Composable
fun WalkthroughHost(state: WalkthroughState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val targets = remember { CoachTargets() }

    CompositionLocalProvider(LocalCoachTargets provides targets) {
        Box(modifier.fillMaxSize()) {
            content()

            val step = state.step
            if (step != null) {
                WalkthroughOverlay(
                    step = step,
                    stepNumber = state.index + 1,
                    total = state.total,
                    isLast = state.isLast,
                    target = targets[step.targetKey],
                    onNext = state::next,
                    onRestart = state::restart,
                    onSkip = state::finish,
                )
            }
        }
    }
}

/** How far the spotlight is cut outside the target's own bounds. */
private val SpotlightPad = 8.dp

/** Gap between the spotlight and the card explaining it. */
private val CardGap = 16.dp

@Composable
private fun WalkthroughOverlay(
    step: WalkthroughStep,
    stepNumber: Int,
    total: Int,
    isLast: Boolean,
    target: Rect?,
    onNext: () -> Unit,
    onRestart: () -> Unit,
    onSkip: () -> Unit,
) {
    val haptics = LocalSatRideHaptics.current
    val density = LocalDensity.current
    val accent = SatRideTheme.accents.tracking

    val pulse by rememberInfiniteTransition(label = "coachPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "coachPulseRing",
    )

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Swallows every touch: while the tour is up, the only controls are its own. Tapping
            // the scrim advances, which is the gesture people try first.
            .pointerInput(step) {
                detectTapGestures {
                    haptics.tap()
                    onNext()
                }
            },
    ) {
        val padPx = with(density) { SpotlightPad.toPx() }
        val hole = target?.inflate(padPx)

        // Scrim with a hole punched in it. `CompositingStrategy.Offscreen` is what makes the
        // clear blend mode work: without its own layer the clear would erase everything already
        // drawn beneath, which is the entire screen.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawRect(Color.Black.copy(alpha = 0.72f))
            if (hole != null) {
                val radius = CornerRadius(hole.height / 2f)
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = hole.topLeft,
                    size = hole.size,
                    cornerRadius = radius,
                    blendMode = BlendMode.Clear,
                )
                drawRoundRect(
                    color = accent,
                    topLeft = hole.topLeft,
                    size = hole.size,
                    cornerRadius = radius,
                    style = Stroke(width = 2.dp.toPx()),
                )
                // A ring expanding out of the spotlight and fading, so the eye is pulled to the
                // hole rather than to the card.
                val grow = 1f + pulse * 0.5f
                val ringSize = Size(hole.width * grow, hole.height * grow)
                drawRoundRect(
                    color = accent.copy(alpha = (1f - pulse) * 0.6f),
                    topLeft = Offset(
                        hole.center.x - ringSize.width / 2f,
                        hole.center.y - ringSize.height / 2f,
                    ),
                    size = ringSize,
                    cornerRadius = CornerRadius(ringSize.height / 2f),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // The card goes on whichever side of the spotlight has room. With no target at all — the
        // native iOS tab bar has no Compose bounds to report — it simply centres, which loses the
        // pointing but keeps every word of the tour.
        var cardHeight by remember(step) { mutableIntStateOf(0) }
        val gapPx = with(density) { CardGap.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val cardY = when {
            hole == null -> (heightPx - cardHeight) / 2f
            hole.center.y > heightPx / 2f -> (hole.top - gapPx - cardHeight).coerceAtLeast(gapPx)
            else -> (hole.bottom + gapPx).coerceAtMost(heightPx - cardHeight - gapPx)
        }

        Column(
            modifier = Modifier
                .offset { IntOffset(0, cardY.roundToInt()) }
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .onSizeChanged { cardHeight = it.height }
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .border(1.dp, SatRideTheme.accents.hairline, RoundedCornerShape(20.dp))
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(step.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = step.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = if (stepNumber == 1) onSkip else onRestart) {
                    Text(
                        text = if (stepNumber == 1) "Skip" else "Restart",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "$stepNumber of $total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(
                    onClick = {
                        haptics.press()
                        onNext()
                    },
                ) {
                    Text(
                        text = if (isLast) "Done" else "Next",
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                    )
                }
            }
        }
    }
}

/** Grows a rectangle by [amount] on every side, clamped so it can never invert. */
private fun Rect.inflate(amount: Float): Rect = Rect(
    left = left - amount,
    top = top - amount,
    right = right + amount,
    bottom = bottom + amount,
)
