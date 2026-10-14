package com.developerstring.satride.feature.play.multiplay.platform

import androidx.compose.runtime.Composable

/**
 * While composed: the screen is held in landscape, the system bars are hidden, and the display
 * does not sleep. Leaving the composition puts everything back.
 *
 * The arena is the one place in SatRide that wants this — everything else rotates freely, which
 * is a listing requirement — so it is a scoped effect rather than an app setting. Desktop needs
 * none of it and its actual is empty.
 */
@Composable
expect fun ImmersiveLandscape()

/** Whether this platform has a motion sensor the arena can steer a camera with. */
expect val motionControlsAvailable: Boolean
