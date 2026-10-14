package com.developerstring.satride.core.designsystem.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * The app's motion vocabulary.
 *
 * Every animation in SatRide resolves to one of these so Android and iOS feel identical. Reach
 * for an ad-hoc `tween` only with a reason worth writing down.
 */
object MotionSpec {
    /** Immediate feedback: chip toggles, button presses. */
    const val FAST_MILLIS: Int = 120

    /** The default: sheets, fades, content swaps. */
    const val STANDARD_MILLIS: Int = 240

    /** Earned moments: orbit arc draw-in, signal lock, first-wow reveals. */
    const val CELEBRATORY_MILLIS: Int = 500

    /** Playful pop-ins: onboarding props, badges, page-turn content. Overshoots. */
    const val PLAYFUL_MILLIS: Int = 350

    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val DecelerateEasing: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** Overshoot-and-settle; the signature easing of the illustrated restyle. */
    val OvershootEasing: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    // FiniteAnimationSpec (not AnimationSpec) because Compose's enter/exit transitions require
    // a spec that is guaranteed to end.
    fun <T> fast(): FiniteAnimationSpec<T> = tween(FAST_MILLIS, easing = StandardEasing)

    fun <T> standard(): FiniteAnimationSpec<T> = tween(STANDARD_MILLIS, easing = StandardEasing)

    fun <T> celebratory(): FiniteAnimationSpec<T> = tween(CELEBRATORY_MILLIS, easing = DecelerateEasing)

    /** Springs for gesture-driven and physical-feeling motion. */
    fun <T> springSnappy(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> springBouncy(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /** Pop-in with visible overshoot; use for things that should feel like game UI. */
    fun <T> playful(): FiniteAnimationSpec<T> = tween(PLAYFUL_MILLIS, easing = OvershootEasing)

    /** A snappy, visibly bouncy spring for selection indicators and press scale. */
    fun <T> springPop(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}
