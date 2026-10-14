package com.developerstring.satride.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What happens to a reminder when the orbit it was based on is recomputed.
 *
 * These are the tests that matter most in P6. Everything else in the alert path fails loudly —
 * a scheduling error is a `Result.failure` a screen can show. This one fails silently: the
 * notification arrives at a plausible time that is quietly wrong, and the user goes outside and
 * watches an empty sky.
 */
class AlertReconciliationTest {

    @Test
    fun aPassThatHasNotMovedIsLeftAlone() {
        val stored = alert(aos = T0 + 2.hours)
        val recomputed = mapOf(ISS to listOf(pass(aos = T0 + 2.hours + 20.seconds)))

        val actions = AlertReconciliation.reconcile(listOf(stored), recomputed, T0)

        assertIs<AlertAction.Keep>(actions.single())
    }

    @Test
    fun driftBeyondAMinuteReschedules() {
        val stored = alert(aos = T0 + 2.hours)
        val moved = pass(aos = T0 + 2.hours + 4.minutes, maxElevationDeg = 55.0, aosAzimuthDeg = 300.0)

        val action = AlertReconciliation.reconcile(listOf(stored), mapOf(ISS to listOf(moved)), T0).single()

        val reschedule = assertIs<AlertAction.Reschedule>(action)
        assertEquals(4.minutes, reschedule.drift)
        assertEquals(moved.aos, reschedule.alert.aos)
        // The freshly computed geometry travels with it: an alert that keeps last week's
        // "max 62°, rises W" is a reminder that lies about the pass it is announcing.
        assertEquals(55.0, reschedule.alert.maxElevationDeg)
        assertEquals(300.0, reschedule.alert.aosAzimuthDeg)
    }

    @Test
    fun aRescheduledAlertKeepsItsIdentitySoTheAlarmIsReplacedNotDuplicated() {
        val stored = alert(aos = T0 + 2.hours)
        val moved = pass(aos = T0 + 2.hours + 3.minutes)

        val action = AlertReconciliation.reconcile(listOf(stored), mapOf(ISS to listOf(moved)), T0).single()

        assertEquals(stored.id, action.alert.id)
    }

    @Test
    fun driftIsToleratedRightUpToTheThreshold() {
        val stored = alert(aos = T0 + 2.hours)
        val barelyMoved = pass(aos = T0 + 2.hours + 59.seconds)

        val action = AlertReconciliation.reconcile(listOf(stored), mapOf(ISS to listOf(barelyMoved)), T0).single()

        assertIs<AlertAction.Keep>(action)
    }

    @Test
    fun aPassThatNoLongerExistsIsDropped() {
        val stored = alert(aos = T0 + 2.hours)
        // The satellite still has passes — just not this one. A marginal pass can fall below the
        // user's minimum elevation on fresh elements and stop existing.
        val elsewhere = pass(aos = T0 + 8.hours)

        val action = AlertReconciliation.reconcile(listOf(stored), mapOf(ISS to listOf(elsewhere)), T0).single()

        assertEquals(AlertAction.Drop(stored, DropReason.GONE), action)
    }

    @Test
    fun anAlertWhosePassHasHappenedIsDropped() {
        val stored = alert(aos = T0 - 5.minutes)

        val action = AlertReconciliation.reconcile(listOf(stored), emptyMap(), T0).single()

        assertEquals(AlertAction.Drop(stored, DropReason.PASSED), action)
    }

    @Test
    fun aSatelliteWeCouldNotRecomputeKeepsItsAlert() {
        // No entry in the map at all: the element set is missing or the propagation failed.
        // "We could not check" must not be treated as "it is not happening".
        val stored = alert(aos = T0 + 2.hours)

        val action = AlertReconciliation.reconcile(listOf(stored), emptyMap(), T0).single()

        assertIs<AlertAction.Keep>(action)
    }

    @Test
    fun aSatelliteWithNoPassesAtAllLosesItsAlert() {
        // Distinct from the case above: the map *has* an entry, and it is empty, so the
        // recomputation genuinely says there is no such pass.
        val stored = alert(aos = T0 + 2.hours)

        val action = AlertReconciliation.reconcile(listOf(stored), mapOf(ISS to emptyList()), T0).single()

        assertEquals(AlertAction.Drop(stored, DropReason.GONE), action)
    }

    @Test
    fun theNearestCandidateWinsWhenTwoPassesAreClose() {
        val stored = alert(aos = T0 + 2.hours)
        val near = pass(aos = T0 + 2.hours + 5.minutes)
        val far = pass(aos = T0 + 2.hours + 18.minutes)

        val action = AlertReconciliation.reconcile(listOf(stored), mapOf(ISS to listOf(far, near)), T0).single()

        assertEquals(near.aos, action.alert.aos)
    }

    @Test
    fun aPassThatMovedBackwardsIsMatchedToo() {
        val stored = alert(aos = T0 + 2.hours)
        val earlier = pass(aos = T0 + 2.hours - 3.minutes)

        val action = AlertReconciliation.reconcile(listOf(stored), mapOf(ISS to listOf(earlier)), T0).single()

        val reschedule = assertIs<AlertAction.Reschedule>(action)
        assertTrue(reschedule.drift.isNegative())
    }

    @Test
    fun aPassThatMovedIntoThePastIsDroppedRatherThanRescheduled() {
        val stored = alert(aos = T0 + 2.minutes)
        val moved = pass(aos = T0 - 4.minutes)

        val action = AlertReconciliation.reconcile(listOf(stored), mapOf(ISS to listOf(moved)), T0).single()

        assertEquals(AlertAction.Drop(stored, DropReason.PASSED), action)
    }

    @Test
    fun eachSatellitesAlertsAreJudgedAgainstItsOwnPasses() {
        val iss = alert(noradId = ISS, aos = T0 + 2.hours)
        val noaa = alert(noradId = 33591, aos = T0 + 3.hours)

        val actions = AlertReconciliation.reconcile(
            stored = listOf(iss, noaa),
            recomputed = mapOf(
                ISS to listOf(pass(noradId = ISS, aos = T0 + 2.hours + 10.seconds)),
                33591 to listOf(pass(noradId = 33591, aos = T0 + 3.hours + 5.minutes)),
            ),
            now = T0,
        )

        assertIs<AlertAction.Keep>(actions[0])
        assertIs<AlertAction.Reschedule>(actions[1])
    }
}
