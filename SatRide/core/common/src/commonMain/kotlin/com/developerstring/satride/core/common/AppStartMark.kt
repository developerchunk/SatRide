package com.developerstring.satride.core.common

import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * When this launch began.
 *
 * Created eagerly as Koin starts — which on Android is `Application.onCreate`, the earliest point
 * common code exists — so [elapsed] is measured from as close to process start as this layer can
 * see.
 *
 * It exists because the splash's eight-second guarantee is a promise to the *user*, and the user's
 * clock starts when they tap the icon. Measuring the cap from the moment the splash's view model
 * happens to be constructed silently excludes process start, Koin wiring and the first frame — on a
 * Pixel 6a that is 2.3 seconds of cold start, so an eight-second cap delivered a ten-second wait.
 *
 * Monotonic, not wall-clock: a clock adjustment mid-launch must not be able to make the cap fire
 * instantly or never.
 */
class AppStartMark {
    private val mark = TimeSource.Monotonic.markNow()

    /** How long since the app started. */
    val elapsed: Duration get() = mark.elapsedNow()

    /** What is left of [budget], never negative. */
    fun remainingOf(budget: Duration): Duration = (budget - elapsed).coerceAtLeast(Duration.ZERO)
}
