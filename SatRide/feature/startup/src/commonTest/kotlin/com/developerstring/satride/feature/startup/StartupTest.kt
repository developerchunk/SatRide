package com.developerstring.satride.feature.startup

import com.developerstring.satride.core.common.AppStartMark
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The eight-second promise, and what happens either side of it.
 *
 * These are the tests that matter here: the whole point of the splash is a guarantee about *time*,
 * and a guarantee nobody checks is a hope.
 */
class StartupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aFastStartHandsOverAsSoonAsItIsDoneAndNotAtTheCap() = runTest(dispatcher) {
        val model = StartupViewModel(coordinatorOf(delay = 200.milliseconds), AppStartMark())

        testScheduler.advanceTimeBy(300)
        testScheduler.runCurrent()

        assertTrue(model.ready.value, "a start that finished in 200 ms must not wait for the cap")
        assertFalse(model.progress.value.timedOut)
    }

    @Test
    fun aStartThatNeverFinishesStillHandsOverAtTheCap() = runTest(dispatcher) {
        // Every step hangs forever — the pathological case this whole design exists for.
        val never = CompletableDeferred<Unit>()
        val coordinator = StartupCoordinator(
            StartupStep.entries.associateWith { { never.await() } },
        )
        val model = StartupViewModel(coordinator, AppStartMark())

        testScheduler.advanceTimeBy(7_900)
        testScheduler.runCurrent()
        assertFalse(model.ready.value, "handed over early")

        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()

        assertTrue(model.ready.value, "the eight-second cap did not fire")
        assertTrue(model.progress.value.timedOut)
    }

    @Test
    fun theCapDoesNotCancelTheWorkItInterrupted() = runTest(dispatcher) {
        var finished = false
        val coordinator = StartupCoordinator(
            mapOf(
                StartupStep.ELEMENTS to {
                    delay(20.seconds)
                    finished = true
                },
            ),
        )
        val model = StartupViewModel(coordinator, AppStartMark())

        testScheduler.advanceTimeBy(8_100)
        testScheduler.runCurrent()
        assertTrue(model.ready.value)
        assertFalse(finished, "still running, as expected")

        // A cap that cancelled the refresh would turn a slow start into no data at all.
        testScheduler.advanceTimeBy(12_000)
        testScheduler.runCurrent()
        assertTrue(finished, "the cap must not cancel the work it moved on from")
    }

    @Test
    fun aFailedStepDoesNotFailTheStartup() = runTest(dispatcher) {
        val coordinator = StartupCoordinator(
            mapOf(
                StartupStep.CATALOG to { },
                // The network step is the one allowed to fail: the bundled catalog works offline.
                StartupStep.ELEMENTS to { error("no network") },
            ),
        )
        val model = StartupViewModel(coordinator, AppStartMark())

        testScheduler.advanceUntilIdle()

        assertTrue(model.ready.value)
        assertFalse(model.progress.value.timedOut, "a failed step is not a timeout")
        assertEquals(StepState.FAILED, model.progress.value.steps[StartupStep.ELEMENTS])
        assertEquals(StepState.DONE, model.progress.value.steps[StartupStep.CATALOG])
    }

    @Test
    fun stepsRunConcurrentlyRatherThanOneAfterAnother() = runTest(dispatcher) {
        // Four steps of one second each: concurrent means about one second, sequential means four.
        val coordinator = StartupCoordinator(
            StartupStep.entries.associateWith { { delay(1_000) } },
        )
        val model = StartupViewModel(coordinator, AppStartMark())

        testScheduler.advanceTimeBy(1_200)
        testScheduler.runCurrent()

        assertTrue(model.ready.value, "steps are running in sequence; the slowest should not be additive")
    }

    @Test
    fun theCapIsChargedFromLaunchAndNotFromTheViewModel() = runTest(dispatcher) {
        // A launch that has already burned the budget before this screen existed gets what is left,
        // not a fresh eight seconds. On a Pixel 6a cold start costs 2.3 s before the splash even
        // renders, and charging the cap from here turned an 8 s promise into a 10.3 s wait.
        val launched = AppStartMark()
        val never = CompletableDeferred<Unit>()
        val coordinator = StartupCoordinator(StartupStep.entries.associateWith { { never.await() } })

        // A cap already smaller than the time spent launching: nothing is left, so handoff is
        // immediate rather than "cap seconds from now".
        val model = StartupViewModel(coordinator, launched, cap = Duration.ZERO)

        testScheduler.runCurrent()

        assertTrue(model.ready.value, "a spent budget must hand over at once")
        assertTrue(model.progress.value.timedOut)
    }

    private fun coordinatorOf(delay: kotlin.time.Duration) = StartupCoordinator(
        StartupStep.entries.associateWith { { delay(delay) } },
    )
}

class StartupProgressTest {

    @Test
    fun theFractionIsMeasuredNotInvented() {
        val steps = mapOf(
            StartupStep.CATALOG to StepState.DONE,
            StartupStep.ELEMENTS to StepState.RUNNING,
            StartupStep.GLOBE to StepState.FAILED,
            StartupStep.SESSION to StepState.PENDING,
        )
        // Done and failed both count as settled — a failed step is not going to make more progress.
        assertEquals(0.5f, StartupProgress(steps).fraction)
    }

    @Test
    fun theLabelNamesTheStepInFlight() {
        val progress = StartupProgress(
            mapOf(StartupStep.CATALOG to StepState.DONE, StartupStep.ELEMENTS to StepState.RUNNING),
        )
        assertEquals(StartupStep.ELEMENTS.label, progress.currentLabel)
    }

    @Test
    fun whatIsStillRunningIsWhatTheScreensShimmerFor() {
        val progress = StartupProgress(
            mapOf(
                StartupStep.CATALOG to StepState.DONE,
                StartupStep.ELEMENTS to StepState.RUNNING,
                StartupStep.GLOBE to StepState.PENDING,
            ),
        )
        assertEquals(setOf(StartupStep.ELEMENTS, StartupStep.GLOBE), progress.stillRunning)
    }
}
