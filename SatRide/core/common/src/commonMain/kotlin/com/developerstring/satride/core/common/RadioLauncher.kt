package com.developerstring.satride.core.common

/**
 * Starts a radio session tuned to a specific downlink.
 *
 * A service contract rather than a navigation callback, because that is what it becomes: P9 makes
 * this configure and start an actual SDR session, which happens to also move the user to the radio
 * screen. Modelling it as an injected interface now means the [Tune] buttons are written against
 * their final shape, and the only thing P9 changes is the implementation.
 *
 * Lives in `core:common` because both the features that call it and the app shell that implements
 * it need to see it, and features may not depend on each other.
 */
interface RadioLauncher {

    /** True when tuning can actually do something. Drives whether [Tune] is enabled. */
    val isAvailable: Boolean

    /**
     * Tunes to a satellite's downlink.
     *
     * [transmitterId] is the SatNOGS record, so the session knows the mode and bandwidth as well
     * as the frequency — a frequency alone would leave the receiver guessing at demodulation.
     */
    fun tune(noradId: Int, transmitterId: String)
}

/**
 * The implementation until P9.
 *
 * Reports itself unavailable rather than silently accepting a tune request and doing nothing —
 * that is what lets the UI disable the button and explain why, instead of looking broken when a
 * tap has no effect.
 */
class UnavailableRadioLauncher : RadioLauncher {
    override val isAvailable: Boolean = false
    override fun tune(noradId: Int, transmitterId: String) = Unit
}
