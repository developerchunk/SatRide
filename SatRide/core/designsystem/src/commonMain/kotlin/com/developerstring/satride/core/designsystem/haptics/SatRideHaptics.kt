package com.developerstring.satride.core.designsystem.haptics

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Tactile feedback for the UI layer.
 *
 * The design system cannot depend on `core:sensors` (hardware lives below the UI), so this is
 * a tiny seam: components fire semantic events through [LocalSatRideHaptics], and the app shell
 * provides an adapter over the real `Haptics` peripheral (Android `VibrationEffect`, iOS
 * `UIImpactFeedbackGenerator`, desktop no-op). Default is silent so previews and tests need no
 * setup.
 */
interface SatRideHaptics {

    /** A light tap: tab switches, chips, page turns. */
    fun tap()

    /** A firm click: button presses, confirmations. */
    fun press()

    /** The earned-moment click: onboarding complete, signal lock. */
    fun success()
}

/** Silent implementation for previews, tests and platforms without a motor. */
object NoSatRideHaptics : SatRideHaptics {
    override fun tap(): Unit = Unit
    override fun press(): Unit = Unit
    override fun success(): Unit = Unit
}

val LocalSatRideHaptics = staticCompositionLocalOf<SatRideHaptics> { NoSatRideHaptics }
