package com.developerstring.satride.feature.passes

import app.cash.turbine.test
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.TxStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class PassesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // UTC so the day-grouping assertions say what they mean, rather than depending on where the
    // machine running the tests happens to be.
    private val zone = TimeZone.UTC

    private fun viewModel(
        passRepository: FakePassRepository = FakePassRepository(),
        satellites: List<com.developerstring.satride.core.model.Satellite> = listOf(satellite()),
        transmitters: List<com.developerstring.satride.core.model.Transmitter> = emptyList(),
        tracker: FakeTracker = FakeTracker(),
        alerts: FakeAlertScheduler = FakeAlertScheduler(),
        clock: FixedClock = FixedClock(),
    ) = PassesViewModel(
        passRepository = passRepository,
        satelliteRepository = FakeSatelliteRepository(satellites),
        transmitterRepository = FakeTransmitterRepository(transmitters),
        tracker = tracker,
        alertScheduler = alerts,
        clock = clock,
        // The state chain is `flowOn`-ed onto `dispatchers.default`; pointing that at the test
        // dispatcher keeps it under the virtual clock instead of on a real thread pool.
        dispatchers = TestDispatchers(dispatcher),
        timeZone = zone,
    )

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    @Test
    fun passesAreGroupedIntoLocalDaysInOrder() = runTest(dispatcher) {
        val repository = FakePassRepository(
            listOf(
                pass(aos = T0 + 2.hours),
                pass(aos = T0 + 4.hours),
                // 02:00 the next day, UTC.
                pass(aos = T0 + 8.hours),
            ),
        )
        val model = viewModel(passRepository = repository)

        val state = model.state.first { !it.loading }

        assertEquals(2, state.days.size)
        assertEquals(listOf("Today", "Tomorrow"), state.days.map { it.label })
        assertEquals(2, state.days[0].passes.size)
        assertEquals(1, state.days[1].passes.size)
        assertTrue(state.days[0].passes[0].aos < state.days[0].passes[1].aos)
    }

    @Test
    fun theCountdownTicks() = runTest(dispatcher) {
        val clock = FixedClock()
        val model = viewModel(
            passRepository = FakePassRepository(listOf(pass(aos = T0 + 30.minutes))),
            clock = clock,
        )

        assertEquals(30.minutes, model.state.first { !it.loading }.next?.startsIn)

        clock.instant = T0 + 5.minutes

        // The whole screen is a function of *now*: the second tick recomputes it, without anything
        // having to poke the view model.
        assertEquals(25.minutes, model.state.first { it.next?.startsIn == 25.minutes }.next?.startsIn)
    }

    @Test
    fun theFreeWindowIsTwentyFourHours() = runTest(dispatcher) {
        val repository = FakePassRepository(listOf(pass(aos = T0 + 2.hours), pass(aos = T0 + 3.days)))
        val model = viewModel(passRepository = repository)

        val state = model.state.first { !it.loading }

        assertEquals(PassWindow.DAY, state.window)
        assertEquals(1, state.totalPasses)
        assertEquals(24.hours, repository.requestedWindows.last())
    }

    @Test
    fun theWeekIsAvailableToEveryoneAndIsActuallyComputed() = runTest(dispatcher) {
        val repository = FakePassRepository(listOf(pass(aos = T0 + 2.hours), pass(aos = T0 + 3.days)))
        val model = viewModel(passRepository = repository)
        model.state.first { !it.loading }

        model.onEvent(PassesEvent.WindowSelected(PassWindow.WEEK))
        val state = model.state.first { it.window == PassWindow.WEEK && it.totalPasses == 2 }

        assertEquals(2, state.totalPasses)
        // The cache only reaches two days at startup, so choosing the week has to *cause* the
        // propagation. Without this the subscriber's week silently ends after 48 hours.
        assertTrue(repository.refreshedWindows.contains(7.days), "the week was never computed")
    }

    @Test
    fun theGradeCombinesElevationWithHavingSomethingToHear() = runTest(dispatcher) {
        val model = viewModel(
            passRepository = FakePassRepository(
                listOf(
                    pass(noradId = ISS, aos = T0 + 1.hours, maxElevationDeg = 70.0),
                    pass(noradId = NOAA, aos = T0 + 2.hours, maxElevationDeg = 70.0),
                    pass(noradId = NOAA, aos = T0 + 3.hours, maxElevationDeg = 12.0),
                ),
            ),
            satellites = listOf(satellite(), satellite(NOAA, "NOAA 19", SatClass.WEATHER)),
            transmitters = listOf(transmitter(noradId = ISS)),
        )

        val rows = model.state.first { !it.loading }.days.flatMap { it.passes }

        // High and audible.
        assertEquals(PassGrade.GREAT, rows[0].grade)
        // Just as high, but nothing transmitting: spectacular to watch, useless to tune.
        assertEquals(PassGrade.OK, rows[1].grade)
        assertEquals(PassGrade.VISUAL_ONLY, rows[2].grade)
    }

    @Test
    fun aDeadTransmitterDoesNotEarnTheRadioBadge() = runTest(dispatcher) {
        val model = viewModel(
            passRepository = FakePassRepository(listOf(pass(aos = T0 + 1.hours, maxElevationDeg = 70.0))),
            transmitters = listOf(transmitter(status = TxStatus.INACTIVE)),
        )

        val row = model.state.first { !it.loading }.next!!

        assertTrue(!row.hasDownlink, "an inactive transmitter promises something to hear that is not there")
        assertEquals(PassGrade.OK, row.grade)
    }

    @Test
    fun filtersNarrowTheTimelineAndSayWhenTheyHideEverything() = runTest(dispatcher) {
        val model = viewModel(
            passRepository = FakePassRepository(
                listOf(pass(aos = T0 + 1.hours, maxElevationDeg = 15.0)),
            ),
        )
        model.state.first { !it.loading }

        model.onEvent(PassesEvent.FiltersChanged(PassFilters(minMaxElevationDeg = 40.0)))

        val state = model.state.first { it.filters.minMaxElevationDeg == 40.0 }
        assertEquals(0, state.totalPasses)
        // Distinct from a quiet sky: there *are* passes, the filters hide them, and only one of
        // those two has a fix the user can act on.
        assertTrue(state.filteredToNothing)
    }

    @Test
    fun theFollowedOnlyFilterUsesTheTracker() = runTest(dispatcher) {
        val tracker = FakeTracker()
        val model = viewModel(
            passRepository = FakePassRepository(
                listOf(pass(noradId = ISS, aos = T0 + 1.hours), pass(noradId = NOAA, aos = T0 + 2.hours)),
            ),
            satellites = listOf(satellite(), satellite(NOAA, "NOAA 19", SatClass.WEATHER)),
            tracker = tracker,
        )
        tracker.follow(NOAA)
        model.state.first { !it.loading }

        model.onEvent(PassesEvent.FiltersChanged(PassFilters(followedOnly = true)))

        val state = model.state.first { it.filters.followedOnly }
        assertEquals(1, state.totalPasses)
        assertEquals(NOAA, state.next?.noradId)
    }

    @Test
    fun aSatelliteThatNeverSetsIsNotAPass() = runTest(dispatcher) {
        // A pass search over a two-day window returns one window-long "pass" for every
        // geostationary object. Rendered as cards they are a wall of identical, meaningless rows —
        // there is nothing to plan around for something that is always up.
        val model = viewModel(
            passRepository = FakePassRepository(
                listOf(
                    pass(noradId = ISS, aos = T0 + 1.hours, duration = 9.minutes),
                    pass(noradId = NOAA, aos = T0, duration = 48.hours),
                ),
            ),
            satellites = listOf(satellite(), satellite(NOAA, "METEOSAT-10", SatClass.WEATHER)),
        )

        val state = model.state.first { !it.loading }

        assertEquals(1, state.totalPasses)
        assertEquals(ISS, state.next?.noradId)
    }

    @Test
    fun askyOfOnlyGeostationaryObjectsIsQuietRatherThanOverFiltered() = runTest(dispatcher) {
        val model = viewModel(
            passRepository = FakePassRepository(listOf(pass(noradId = NOAA, aos = T0, duration = 48.hours))),
            satellites = listOf(satellite(NOAA, "METEOSAT-10", SatClass.WEATHER)),
        )

        val state = model.state.first { !it.loading }

        assertTrue(state.isEmpty)
        // Blaming the filters would send the user to a sheet that cannot help them.
        assertTrue(!state.filteredToNothing)
    }

    @Test
    fun theSamePassCachedTwiceIsListedOnce() = runTest(dispatcher) {
        // A search clips an in-progress pass to the moment it started, and the search runs more
        // than once — so the cache ends up holding two rows for one transit, differing only in how
        // much of the start was cut off.
        val model = viewModel(
            passRepository = FakePassRepository(
                listOf(
                    pass(aos = T0 - 20.minutes, duration = 60.minutes),
                    pass(aos = T0 - 6.minutes, duration = 46.minutes),
                    pass(aos = T0 + 4.hours, duration = 10.minutes),
                ),
            ),
        )

        val state = model.state.first { !it.loading }

        assertEquals(2, state.totalPasses)
        // The earliest row wins: it was computed first, so its AOS is the least clipped.
        assertEquals(T0 - 20.minutes, state.next?.aos)
    }

    @Test
    fun twoGenuinelySeparatePassesOfOneSatelliteBothSurvive() = runTest(dispatcher) {
        val model = viewModel(
            passRepository = FakePassRepository(
                listOf(
                    pass(aos = T0 + 1.hours, duration = 10.minutes),
                    pass(aos = T0 + 2.hours, duration = 10.minutes),
                ),
            ),
        )

        assertEquals(2, model.state.first { !it.loading }.totalPasses)
    }

    @Test
    fun aQuietSkyIsNotReportedAsAFilterProblem() = runTest(dispatcher) {
        val model = viewModel(passRepository = FakePassRepository(emptyList()))

        val state = model.state.first { !it.loading }

        assertTrue(state.isEmpty)
        assertTrue(!state.filteredToNothing)
        assertTrue(!state.observerUnknown)
    }

    @Test
    fun withoutAPositionTheEmptyTimelineSaysSo() = runTest(dispatcher) {
        val model = viewModel(
            passRepository = FakePassRepository(emptyList()),
            tracker = FakeTracker(observer = null),
        )

        assertTrue(model.state.first { !it.loading }.observerUnknown)
    }

    @Test
    fun anyoneCanArmAReminder() = runTest(dispatcher) {
        val alerts = FakeAlertScheduler()
        val model = viewModel(
            passRepository = FakePassRepository(listOf(pass(aos = T0 + 2.hours))),
            alerts = alerts,
        )
        val id = model.state.first { !it.loading }.next!!.id

        model.onEvent(PassesEvent.RemindToggled(id))
        assertTrue(alerts.alerts.first { it.isNotEmpty() }.isNotEmpty())
    }

    @Test
    fun armingAReminderCarriesThePassesOwnNumbers() = runTest(dispatcher) {
        val alerts = FakeAlertScheduler()
        val model = viewModel(
            passRepository = FakePassRepository(
                listOf(pass(aos = T0 + 2.hours, maxElevationDeg = 78.0, aosAzimuthDeg = 271.0)),
            ),
            alerts = alerts,
        )
        val row = model.state.first { !it.loading }.next!!

        model.onEvent(PassesEvent.RemindToggled(row.id))
        val armed = alerts.alerts.first { it.isNotEmpty() }.single()

        assertEquals(row.id, armed.id)
        assertEquals(row.aos, armed.aos)
        assertEquals(78.0, armed.maxElevationDeg)
        // Written from the pass, so the notification can be produced by a boot receiver with no
        // orbit model warmed up.
        assertEquals("Pass in 10 min — max 78°, rises W", armed.body)
    }

    @Test
    fun aSecondTapCancelsTheReminder() = runTest(dispatcher) {
        val alerts = FakeAlertScheduler()
        val model = viewModel(
            passRepository = FakePassRepository(listOf(pass(aos = T0 + 2.hours))),
            alerts = alerts,
        )

        // Collected throughout, as a live screen would. The view model reads its rows from the
        // state it publishes, which only exists while something is looking at it.
        val collector = backgroundScope.launch { model.state.collect { } }

        val id = model.state.first { !it.loading }.next!!.id
        model.onEvent(PassesEvent.RemindToggled(id))
        model.state.first { it.next?.hasAlert == true }

        model.onEvent(PassesEvent.RemindToggled(id))

        // Waited for rather than asserted immediately: cancelling is a suspending call the view
        // model launches, so it has not run yet at the moment onEvent returns.
        assertTrue(alerts.alerts.first { it.isEmpty() }.isEmpty())
        assertEquals(listOf(id), alerts.cancelled)
        collector.cancel()
    }

    @Test
    fun aMissingPermissionIsAskedForRatherThanFailedSilently() = runTest(dispatcher) {
        val alerts = FakeAlertScheduler(
            capability = com.developerstring.satride.core.notifications.AlertCapability(
                supported = true,
                exact = true,
                needsPermission = true,
            ),
        )
        val model = viewModel(
            passRepository = FakePassRepository(listOf(pass(aos = T0 + 2.hours))),
            alerts = alerts,
        )
        val id = model.state.first { !it.loading }.next!!.id

        model.effects.test {
            model.onEvent(PassesEvent.RemindToggled(id))
            assertEquals(PassesEffect.RequestNotificationPermission, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun grantingPermissionArmsTheReminderTheUserOriginallyAskedFor() = runTest(dispatcher) {
        val alerts = FakeAlertScheduler(
            capability = com.developerstring.satride.core.notifications.AlertCapability(
                supported = true,
                exact = true,
                needsPermission = true,
            ),
        )
        val model = viewModel(
            passRepository = FakePassRepository(listOf(pass(aos = T0 + 2.hours))),
            alerts = alerts,
        )
        val id = model.state.first { !it.loading }.next!!.id
        model.onEvent(PassesEvent.RemindToggled(id))

        // The permission has now been granted, so the capability no longer reports needing it.
        alerts.capability =
            com.developerstring.satride.core.notifications.AlertCapability(supported = true, exact = true)
        model.onNotificationPermissionResult(granted = true, pendingId = id)

        assertEquals(id, alerts.alerts.first { it.isNotEmpty() }.single().id)
    }

    @Test
    fun tappingAPassOpensItByItsSatelliteAndTime() = runTest(dispatcher) {
        val model = viewModel(passRepository = FakePassRepository(listOf(pass(aos = T0 + 2.hours))))
        val row = model.state.first { !it.loading }.next!!

        model.effects.test {
            model.onEvent(PassesEvent.PassClicked(row.id))
            val effect = assertIs<PassesEffect.OpenPass>(awaitItem())
            assertEquals(ISS, effect.noradId)
            assertEquals(row.aos.toEpochMilliseconds(), effect.aosEpochMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aPassInProgressIsMarkedAsSuch() = runTest(dispatcher) {
        val model = viewModel(
            passRepository = FakePassRepository(listOf(pass(aos = T0 - 2.minutes, duration = 10.minutes))),
        )

        val row = model.state.first { !it.loading }.next
        assertNotNull(row)
        assertTrue(row.inProgress)
        // The live dot walks the stored arc rather than being propagated per frame.
        assertNotNull(row.livePoint())
    }

    @Test
    fun theThumbnailArcIsDownsampledButKeepsItsEnds() = runTest(dispatcher) {
        val full = pass(aos = T0 + 1.hours, samples = 41)
        val model = viewModel(passRepository = FakePassRepository(listOf(full)))

        val arc = model.state.first { !it.loading }.next!!.arc

        assertTrue(arc.size <= 14, "a 56 dp plot cannot show 41 samples: ${arc.size}")
        assertEquals(full.arc.first().topo.azimuthDeg.toFloat(), arc.first().azimuthDeg)
        assertEquals(full.arc.last().topo.azimuthDeg.toFloat(), arc.last().azimuthDeg)
    }
}
