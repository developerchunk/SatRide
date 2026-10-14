package com.developerstring.satride.core.sensors

import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

/**
 * Haptics through UIKit's feedback generators.
 *
 * Two generators are kept alive rather than built per event, and each is prepared after use.
 * `prepare()` spins the Taptic Engine up, and without it the first tap of a burst arrives tens of
 * milliseconds late — which in a guide loop is felt as the phone lagging behind the hand.
 */
internal class IosHaptics : Haptics {

    private val light = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    private val heavy = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)

    override val isAvailable: Boolean = true

    override fun tick() {
        light.impactOccurred()
        light.prepare()
    }

    override fun lock() {
        heavy.impactOccurred()
        heavy.prepare()
    }
}
