package com.developerstring.satride.core.notifications

import com.developerstring.satride.core.model.Pass
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What has to happen to one stored alert in the light of freshly computed passes.
 *
 * The whole point of this type is that the decision is data, not a side effect: it can be
 * computed from two lists and asserted against, which is the only way to be confident about
 * something whose failure mode is a notification that silently never arrives.
 */
sealed interface AlertAction {
    val alert: PassAlert

    /** The pass has not moved enough to matter. Leave the armed alarm alone. */
    data class Keep(override val alert: PassAlert) : AlertAction

    /** New elements moved the pass. Re-arm at the new time, keeping the alert's identity. */
    data class Reschedule(override val alert: PassAlert, val previousAos: Instant) : AlertAction {
        val drift: Duration get() = alert.aos - previousAos
    }

    /** Cancel and forget: the pass has happened, or no longer exists at all. */
    data class Drop(override val alert: PassAlert, val reason: DropReason) : AlertAction
}

enum class DropReason {
    /** AOS is in the past. */
    PASSED,

    /**
     * The recomputed elements no longer produce a pass anywhere near this one.
     *
     * Real and not rare: a marginal pass that peaked at 10.2° on last week's elements can fall
     * below the user's minimum on this week's and stop existing as far as the app is concerned.
     */
    GONE,
}

/**
 * Keeps stored alerts in step with recomputed passes.
 *
 * SGP4 predictions move as elements are refreshed — typically by seconds a day out, occasionally
 * by a minute or more for a satellite that has manoeuvred. An alert armed against last week's
 * numbers would fire at a time that is quietly wrong, which is worse than not firing: the user
 * goes outside and looks at an empty sky.
 *
 * Nothing here talks to a scheduler or a database. It is given what is stored and what has been
 * recomputed and answers what should change.
 */
object AlertReconciliation {

    /**
     * How far a pass may move before the alarm is re-armed.
     *
     * A minute is the honest line. Below it the difference is invisible against the ten-minute
     * lead time and inside the slop of an inexact alarm, and re-arming on every second of drift
     * would rewrite every alarm on the phone after every refresh for no benefit anyone could
     * perceive.
     */
    val DRIFT_TOLERANCE: Duration = 60.seconds

    /**
     * How far away a recomputed pass may be and still be considered *the same pass*.
     *
     * Wide enough to absorb any drift a refresh realistically produces, and far narrower than the
     * gap between two consecutive passes of the same satellite — the shortest of which, for a very
     * low orbit, is about ninety minutes. So a match is unambiguous.
     */
    val MATCH_WINDOW: Duration = 20.minutes

    /**
     * @param stored every alert the app is holding.
     * @param recomputed freshly predicted passes, grouped by satellite. A satellite absent from
     *   the map is one whose passes could not be recomputed — a missing element set, a decayed
     *   object — and its alerts are left alone rather than dropped, because "we could not check"
     *   and "it is not happening" are different answers and only one of them should cancel a
     *   reminder the user asked for.
     */
    fun reconcile(stored: List<PassAlert>, recomputed: Map<Int, List<Pass>>, now: Instant): List<AlertAction> =
        stored.map { alert ->
            when {
                alert.aos <= now -> AlertAction.Drop(alert, DropReason.PASSED)
                !recomputed.containsKey(alert.noradId) -> AlertAction.Keep(alert)
                else -> matchAgainst(alert, recomputed.getValue(alert.noradId), now)
            }
        }

    private fun matchAgainst(alert: PassAlert, passes: List<Pass>, now: Instant): AlertAction {
        val match = passes
            .filter { (it.aos - alert.aos).absoluteValue <= MATCH_WINDOW }
            .minByOrNull { (it.aos - alert.aos).absoluteValue }
            ?: return AlertAction.Drop(alert, DropReason.GONE)

        if (match.aos <= now) return AlertAction.Drop(alert, DropReason.PASSED)

        val drift = match.aos - alert.aos
        if (drift.absoluteValue < DRIFT_TOLERANCE) return AlertAction.Keep(alert)

        // Identity is preserved deliberately: the alert keeps the id it was created with, so
        // re-arming replaces the platform alarm instead of adding a second one beside it.
        return AlertAction.Reschedule(
            alert = alert.copy(
                aos = match.aos,
                maxElevationDeg = match.maxElevationDeg,
                aosAzimuthDeg = match.aosAzimuthDeg,
            ),
            previousAos = alert.aos,
        )
    }
}
