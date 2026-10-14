package com.developerstring.satride.feature.passes

import app.cash.turbine.test
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.DefaultSkyGuideRequests
import com.developerstring.satride.core.model.TxStatus
import com.developerstring.satride.feature.passes.detail.PassDetailEffect
import com.developerstring.satride.feature.passes.detail.PassDetailEvent
import com.developerstring.satride.feature.passes.detail.PassDetailViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PassDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    private fun viewModel(
        passes: List<com.developerstring.satride.core.model.Pass>,
        aos: kotlin.time.Instant,
        transmitters: List<com.developerstring.satride.core.model.Transmitter> = emptyList(),
        alerts: FakeAlertScheduler = FakeAlertScheduler(),
        tracker: FakeTracker = FakeTracker(),
        guideRequests: DefaultSkyGuideRequests = DefaultSkyGuideRequests(),
        clock: FixedClock = FixedClock(),
    ) = PassDetailViewModel(
        noradId = ISS,
        aos = aos,
        passRepository = FakePassRepository(passes),
        satelliteRepository = FakeSatelliteRepository(listOf(satellite())),
        transmitterRepository = FakeTransmitterRepository(transmitters),
        tracker = tracker,
        alertScheduler = alerts,
        guideRequests = guideRequests,
        clock = clock,
        // `flowOn`-ed state chain: point it at the test dispatcher so it stays under the
        // virtual clock rather than a real thread pool.
        dispatchers = TestDispatchers(dispatcher),
    )

    @Test
    fun thePassIsFoundBySatelliteAndTime() = runTest(dispatcher) {
        val target = pass(aos = T0 + 2.hours)
        val model = viewModel(passes = listOf(pass(aos = T0 + 30.minutes), target), aos = target.aos)

        val state = model.state.first { !it.loading }

        assertEquals(target.aos, state.pass?.aos)
        assertEquals("ISS (ZARYA)", state.satelliteName)
    }

    @Test
    fun aPassThatDriftedByAMinuteIsStillTheSamePass() = runTest(dispatcher) {
        // The elements were refreshed between the timeline being drawn and this screen opening.
        // Matching on exact equality would report the pass missing.
        val moved = pass(aos = T0 + 2.hours + 90.seconds)
        val model = viewModel(passes = listOf(moved), aos = T0 + 2.hours)

        val state = model.state.first { !it.loading }

        assertEquals(moved.aos, state.pass?.aos)
        assertTrue(!state.notFound)
    }

    @Test
    fun aPassThatNoLongerExistsIsReportedRatherThanLeftBlank() = runTest(dispatcher) {
        val model = viewModel(passes = listOf(pass(aos = T0 + 9.hours)), aos = T0 + 2.hours)

        val state = model.state.first { !it.loading }

        assertNull(state.pass)
        assertTrue(state.notFound)
    }

    @Test
    fun theElevationCurveSpansTheWholePassAndPeaksAtTheCulmination() = runTest(dispatcher) {
        val target = pass(aos = T0 + 2.hours, maxElevationDeg = 62.0)
        val model = viewModel(passes = listOf(target), aos = target.aos)

        val curve = model.state.first { !it.loading }.elevationCurve

        assertEquals(0f, curve.first().fraction)
        assertEquals(1f, curve.last().fraction)
        // Zero at the horizon on both ends, peak in the middle: the shape of a pass.
        assertTrue(curve.first().value < 1f)
        assertTrue(curve.last().value < 1f)
        assertTrue(abs(curve.maxOf { it.value } - 62f) < 0.5f)
    }

    @Test
    fun theDopplerPreviewIsDrawnForTheHighestActiveDownlink() = runTest(dispatcher) {
        val target = pass(aos = T0 + 2.hours)
        val model = viewModel(
            passes = listOf(target),
            aos = target.aos,
            transmitters = listOf(
                transmitter(id = "vhf", downlinkHz = 145_800_000),
                transmitter(id = "uhf", downlinkHz = 437_800_000),
            ),
        )

        val state = model.state.first { !it.loading }

        // Shift scales with frequency, so the harsher of the two is the one worth warning about.
        assertEquals("uhf", state.dopplerTransmitter?.id)
        // ~437.8 MHz at 6 km/s closing is about 8.8 kHz.
        assertTrue(state.dopplerPeakHz in 8_000.0..9_500.0, "peak was ${state.dopplerPeakHz}")
        // Normalised to its own peak, and crossing zero at closest approach.
        assertTrue(state.dopplerCurve.all { it.value in -1.001f..1.001f })
        assertTrue(state.dopplerCurve.first().value > 0.9f, "approach should be a positive shift")
        assertTrue(state.dopplerCurve.last().value < -0.9f, "recession should be a negative shift")
    }

    @Test
    fun aDeadTransmitterIsStillListedButDoesNotEarnTheGrade() = runTest(dispatcher) {
        val target = pass(aos = T0 + 2.hours, maxElevationDeg = 70.0)
        val model = viewModel(
            passes = listOf(target),
            aos = target.aos,
            transmitters = listOf(transmitter(status = TxStatus.INACTIVE)),
        )

        val state = model.state.first { !it.loading }

        assertEquals(1, state.downlinks.size)
        assertTrue(!state.downlinks.single().isActive)
        assertEquals(PassGrade.OK, state.grade)
    }

    @Test
    fun openInSkyPutsTheSatelliteOnTheGuideBus() = runTest(dispatcher) {
        val target = pass(aos = T0 + 2.hours)
        val guideRequests = DefaultSkyGuideRequests()
        val model = viewModel(passes = listOf(target), aos = target.aos, guideRequests = guideRequests)
        model.state.first { !it.loading }

        model.effects.test {
            model.onEvent(PassDetailEvent.OpenInSkyClicked)
            assertIs<PassDetailEffect.OpenSky>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        // The same handoff a notification tap uses, rather than a second path into the sky view.
        val pending = guideRequests.pending.value
        assertEquals(ISS, pending?.noradId)
        assertTrue(pending!!.guide)
    }

    @Test
    fun armingAReminderUsesTheSameIdentityTheTimelineUses() = runTest(dispatcher) {
        val target = pass(aos = T0 + 2.hours)
        val alerts = FakeAlertScheduler()
        val model = viewModel(
            passes = listOf(target),
            aos = target.aos,
            alerts = alerts,
        )
        model.state.first { !it.loading }

        model.onEvent(PassDetailEvent.RemindToggled)

        val armed = alerts.alerts.first { it.isNotEmpty() }.single()
        // Same id the timeline would produce, so the two screens show one reminder rather than two.
        assertEquals(passId(ISS, target.aos), armed.id)
    }

    @Test
    fun theLiveDotOnlyExistsWhileThePassIsHappening() = runTest(dispatcher) {
        val target = pass(aos = T0 - 2.minutes, duration = 10.minutes)
        val tracker = FakeTracker().apply {
            interpolatedTopo = com.developerstring.satride.core.model.Topocentric(
                azimuthDeg = 180.0,
                elevationDeg = 40.0,
                rangeKm = 500.0,
                rangeRateKmS = -1.0,
            )
        }
        val model = viewModel(passes = listOf(target), aos = target.aos, tracker = tracker)

        val state = model.state.first { !it.loading }
        assertNotNull(state.live)
        assertTrue(state.inProgress)
    }

    @Test
    fun aFuturePassHasNoLiveDotEvenWhenTheTrackerWouldOfferOne() = runTest(dispatcher) {
        val target = pass(aos = T0 + 2.hours)
        val tracker = FakeTracker().apply {
            interpolatedTopo = com.developerstring.satride.core.model.Topocentric(180.0, 40.0, 500.0, -1.0)
        }
        val model = viewModel(passes = listOf(target), aos = target.aos, tracker = tracker)

        val state = model.state.first { !it.loading }

        // Drawing it would claim a pass is in progress when it is two hours away.
        assertNull(state.live)
        assertTrue(!state.inProgress)
    }
}
