package com.developerstring.satride.core.common

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A [Clock] whose reading can be set.
 *
 * Every layer that cares about time takes a `Clock` rather than calling `Clock.System.now()`,
 * so "this element set is four days old" can be tested by moving the clock instead of waiting
 * four days. It lives in production code, not test code, because P5's time-travel control needs
 * exactly this: a clock the user can scrub.
 */
class MutableClock(initial: Instant) : Clock {
    var current: Instant = initial

    override fun now(): Instant = current

    /** Moves the clock forward (or back, with a negative duration). */
    fun advanceBy(duration: Duration) {
        current += duration
    }
}
