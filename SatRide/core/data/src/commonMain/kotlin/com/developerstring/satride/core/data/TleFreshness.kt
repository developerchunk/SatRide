package com.developerstring.satride.core.data

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * How long it has been since the catalog was last refreshed.
 *
 * SGP4's error grows roughly one to three kilometres per day for a low orbit — invisible for the
 * first couple of days, a noticeable pointing offset after a few, enough to make pass times wrong
 * after a week. SatRide surfaces that rather than hiding it: a user aiming a phone at empty sky
 * deserves to be told the data is old rather than left to conclude the app is broken.
 *
 * Measured from the **last successful fetch**, not from the oldest element set held. An earlier
 * version used the oldest epoch, on the theory that freshness should be reported by the worst data
 * the user might point at. On a real catalog that is permanently alarming: among four hundred-odd
 * objects there is always some obscure one whose operator last published elements a week ago, so
 * the banner was red on a catalog refreshed thirty seconds earlier. A warning that is always on is
 * a warning nobody reads — which is exactly what this type's own documentation says it must avoid.
 * Per-satellite element age is still shown on the detail screen, where it is about one satellite
 * and is actionable.
 */
sealed interface TleFreshness {

    /** Nothing stored yet — before the first refresh and before the snapshot is seeded. */
    data object Empty : TleFreshness

    /** Under [FRESH_LIMIT]. Error is well inside the AR view's own pointing accuracy. */
    data class Fresh(val age: Duration) : TleFreshness

    /** Usable, but worth refreshing when there is a connection. */
    data class Aging(val age: Duration) : TleFreshness

    /** Old enough that pass times can be visibly wrong. The UI says so plainly. */
    data class Stale(val age: Duration) : TleFreshness

    val needsRefresh: Boolean get() = this !is Fresh

    companion object {
        /** The age, or `null` for [Empty]. */
        val TleFreshness.ageOrNull: Duration?
            get() = when (this) {
                is Fresh -> age
                is Aging -> age
                is Stale -> age
                Empty -> null
            }

        val FRESH_LIMIT: Duration = 3.days
        val AGING_LIMIT: Duration = 7.days

        fun of(age: Duration): TleFreshness = when {
            age < FRESH_LIMIT -> Fresh(age)
            age < AGING_LIMIT -> Aging(age)
            else -> Stale(age)
        }
    }
}
