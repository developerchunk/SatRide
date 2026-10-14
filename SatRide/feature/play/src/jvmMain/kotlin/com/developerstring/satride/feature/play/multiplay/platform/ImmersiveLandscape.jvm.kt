package com.developerstring.satride.feature.play.multiplay.platform

import androidx.compose.runtime.Composable

/** A desktop window is already wide and never sleeps for the app's sake: nothing to do. */
@Composable
actual fun ImmersiveLandscape() = Unit

/** No attitude sensor on desktop; the arena offers mouse/keyboard look instead. */
actual val motionControlsAvailable: Boolean = false
