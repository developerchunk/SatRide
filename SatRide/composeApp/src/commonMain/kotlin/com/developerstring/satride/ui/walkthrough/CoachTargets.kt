package com.developerstring.satride.ui.walkthrough

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Where the things the walkthrough talks about actually are on screen.
 *
 * The alternative — hard-coding the spotlight's position — breaks the first time a device has a
 * different bar height, a display cutout, or a font scale the icons grow with, and breaks
 * silently: the hole simply lands next to the button instead of over it. Letting each target
 * report its own measured bounds means the spotlight is correct by construction.
 *
 * Bounds are in the coordinate space of the composition root, which is why the overlay has to be
 * a child of that same root — see [WalkthroughHost].
 */
@Stable
class CoachTargets {
    private val bounds = mutableStateMapOf<String, Rect>()

    internal fun put(key: String, rect: Rect) {
        bounds[key] = rect
    }

    internal fun remove(key: String) {
        bounds.remove(key)
    }

    operator fun get(key: String): Rect? = bounds[key]
}

val LocalCoachTargets = staticCompositionLocalOf { CoachTargets() }

/**
 * Marks this composable as something the walkthrough can point at.
 *
 * The registration is removed on dispose, so a step whose target has left the composition finds
 * nothing rather than a stale rectangle — which is what stops the spotlight from hanging over
 * empty space when a screen changes underneath it.
 */
@Composable
fun Modifier.coachTarget(key: String): Modifier {
    val targets = LocalCoachTargets.current
    DisposableEffect(targets, key) {
        onDispose { targets.remove(key) }
    }
    return onGloballyPositioned { targets.put(key, it.boundsInRoot()) }
}
