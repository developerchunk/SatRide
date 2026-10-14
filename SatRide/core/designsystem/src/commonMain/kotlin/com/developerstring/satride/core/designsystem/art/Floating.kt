package com.developerstring.satride.core.designsystem.art

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The slow sine bob every floating prop in the illustrated style shares.
 *
 * [phase] staggers multiple props in one scene (0f..1f) so a fleet of floating objects does not
 * bounce in lockstep like a marching band.
 */
@Composable
fun Modifier.floating(amplitude: Dp = 6.dp, periodMillis: Int = 3200, phase: Float = 0f): Modifier {
    val transition = rememberInfiniteTransition(label = "floating")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "floatingCycle",
    )
    return offset {
        val bob = sin((t + phase) * 2f * PI.toFloat()) * amplitude.toPx()
        IntOffset(0, bob.roundToInt())
    }
}

/** A repeating 0→1 cycle for driving custom scene animation (waves, blinks, flames). */
@Composable
fun rememberSceneCycle(periodMillis: Int = 2400, label: String = "sceneCycle"): Float {
    val transition = rememberInfiniteTransition(label = label)
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "${label}Value",
    )
    return t
}
