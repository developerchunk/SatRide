package com.developerstring.satride.core.sensors

/**
 * Short physical feedback.
 *
 * Lives in `core:sensors` because this module already owns the boundary to device hardware and
 * the dependency injection for it. The name says input, but the vibration motor is the same kind
 * of thing as the magnetometer: a peripheral whose API differs per platform and which nothing
 * above this layer should have to know about.
 *
 * Deliberately tiny. The AR guide needs a tick and a confirmation and nothing else, and every
 * additional effect is one more thing to make feel right on two platforms.
 */
public interface Haptics {

    /** A light tap. The guide loop's metronome; fired many times a second at its fastest. */
    public fun tick()

    /** A firmer, distinct event. Used once, when the user lands on the target. */
    public fun lock()

    /** False where there is no vibration motor, so callers can skip the work of driving it. */
    public val isAvailable: Boolean
}

/** For platforms with no haptics, tests, and users who have turned them off. */
public object NoHaptics : Haptics {
    override fun tick(): Unit = Unit
    override fun lock(): Unit = Unit
    override val isAvailable: Boolean = false
}
