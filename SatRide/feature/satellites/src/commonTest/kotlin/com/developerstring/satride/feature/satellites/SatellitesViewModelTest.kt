package com.developerstring.satride.feature.satellites

import app.cash.turbine.test
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.MutableClock
import com.developerstring.satride.core.data.RefreshState
import com.developerstring.satride.core.data.TleFreshness
import com.developerstring.satride.core.data.TrackingEngine
import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.ObserverPosition
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.TxMode
import com.developerstring.satride.core.sensors.LocationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The catalog view model.
 *
 * Driven with fakes that hold real state, so what is verified is behaviour rather than which
 * methods happen to get called.
 */
class SatellitesViewModelTest {

    /**
     * `viewModelScope` runs on `Dispatchers.Main`, which on Kotlin/Native is the platform run loop
     * and is not driven by a test's virtual clock — so without this every view model's internal
     * collection silently never runs, and the whole class passes on JVM and hangs on iOS.
     */
    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setMainDispatcher() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private val now = Instant.parse("2026-08-02T12:00:00Z")
    private val clock = MutableClock(now)

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    private class FakeLocationProvider : LocationProvider {
        private val state = MutableStateFlow<ObserverPosition>(ObserverPosition.Manual(GeoPos(51.5, -0.12)))
        override val position: StateFlow<ObserverPosition> = state
        override suspend fun requestFix() = Unit
        override fun setManual(position: GeoPos?) {
            state.value = position?.let { ObserverPosition.Manual(it) } ?: ObserverPosition.Unknown
        }
    }

    private val satellites = FakeSatelliteRepository(
        listOf(
            satellite(25544, "ISS (ZARYA)", SatClass.CREWED, curated = true),
            satellite(33591, "NOAA 19", SatClass.WEATHER, curated = true),
            satellite(7530, "AO-07", SatClass.AMATEUR, curated = true),
            satellite(99001, "CUBESAT ONE", SatClass.CUBESAT),
            satellite(99002, "DEBRIS R/B", SatClass.ROCKET_BODY),
        ),
    )
    private val transmitters = FakeTransmitterRepository(
        listOf(
            transmitter(25544, downlinkHz = 145_800_000L, mode = TxMode.FM),
            transmitter(7530, id = "tx-ao7", downlinkHz = 435_100_000L, mode = TxMode.CW),
            transmitter(99001, id = "tx-cube", downlinkHz = 437_000_000L, mode = TxMode.AFSK),
            transmitter(99002, id = "tx-deb", downlinkHz = 1_700_000_000L, mode = TxMode.HRPT),
        ),
    )
    private val passes = FakePassRepository(
        listOf(
            pass(33591, aos = now + 20.minutes, maxElevationDeg = 62.0),
            pass(25544, aos = now + 5.minutes, maxElevationDeg = 31.0),
        ),
    )
    private val tles = FakeTleRepository(TleFreshness.Fresh(2.hours))
    private val selection = FakeTrackingSelectionRepository(catalog = satellites.all)

    init {
        satellites.transmitterOwners.value = setOf(25544, 7530, 99001, 99002)
    }

    private val tracker = FakeSatelliteTracker()
    private val catalogRefresher = FakeRefreshController()

    /**
     * Built with a live subscriber held open for the test.
     *
     * The state flow is `WhileSubscribed`, so without one the upstream stops between assertions
     * and debounced work never runs — the view model would look broken when it is the test that
     * unplugged it.
     */
    private fun TestScope.viewModel(): SatellitesViewModel {
        val viewModel = SatellitesViewModel(
            satelliteRepository = satellites,
            transmitterRepository = transmitters,
            passRepository = passes,
            tleRepository = tles,
            trackingEngine = tracker,
            selectionRepository = selection,
            refreshController = catalogRefresher,
            clock = clock,
            // The state chain is `flowOn`-ed onto `dispatchers.default`; pointing that at the
            // test dispatcher keeps it under the virtual clock instead of on a real thread pool.
            dispatchers = TestDispatchers(mainDispatcher),
        )
        backgroundScope.launch { viewModel.state.collect { } }
        testScheduler.advanceUntilIdle()
        return viewModel
    }

    /**
     * Popular, not "up now": the landing segment has to have something in it on a cold first
     * launch, before any location fix or catalog sync has landed.
     */
    @Test
    fun startsOnPopular() = runTest(mainDispatcher) {
        val state = viewModel().state.first { !it.loading }

        assertEquals(CatalogSegment.POPULAR, state.segment)
    }

    @Test
    fun popularShowsTheCuratedSet() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(SatellitesEvent.SegmentSelected(CatalogSegment.POPULAR))

        val state = viewModel.state.first { !it.loading && it.segment == CatalogSegment.POPULAR }

        assertEquals(setOf(25544, 33591, 7530), state.rows.map { it.noradId }.toSet())
    }

    @Test
    fun weatherSegmentShowsOnlyWeatherSatellites() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(SatellitesEvent.SegmentSelected(CatalogSegment.WEATHER))

        val state = viewModel.state.first { it.segment == CatalogSegment.WEATHER && !it.loading }

        assertEquals(listOf(33591), state.rows.map { it.noradId })
    }

    /**
     * "Ham/FM" means satellites a beginner can actually hear, not everything with an amateur
     * classification — a CubeSat whose only downlink is a mode SatRide cannot decode would be a
     * disappointing first listen.
     */
    @Test
    fun hamSegmentRequiresADecodableDownlink() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(SatellitesEvent.SegmentSelected(CatalogSegment.HAM))

        val state = viewModel.state.first { it.segment == CatalogSegment.HAM && !it.loading }

        // AO-07 (CW) and the CubeSat (AFSK) qualify; the rocket body's HRPT does not.
        assertEquals(setOf(7530, 99001), state.rows.map { it.noradId }.toSet())
    }

    @Test
    fun theAllSegmentOpensForEveryone() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.state.first { !it.loading }

        viewModel.onEvent(SatellitesEvent.SegmentSelected(CatalogSegment.ALL))

        val state = viewModel.state.first { it.segment == CatalogSegment.ALL }
        assertEquals(5, state.rows.size)
    }

    @Test
    fun searchIsDebouncedAndCutsAcrossSegments() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.state.first { !it.loading }

        viewModel.onEvent(SatellitesEvent.QueryChanged("N"))
        viewModel.onEvent(SatellitesEvent.QueryChanged("NO"))
        viewModel.onEvent(SatellitesEvent.QueryChanged("NOAA"))

        val state = viewModel.state.first { it.rows.map { row -> row.noradId } == listOf(33591) }

        assertEquals(listOf(33591), state.rows.map { it.noradId })
        // Only the settled query reached the repository, not each intermediate keystroke.
        assertEquals("NOAA", satellites.lastQuery)
    }

    @Test
    fun theQueryIsReflectedImmediatelyEvenThoughTheSearchIsDebounced() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(SatellitesEvent.QueryChanged("MET"))

        // The field must not lag the user's typing, whatever the query behind it is doing.
        assertEquals("MET", viewModel.state.first { it.query == "MET" }.query)
    }

    @Test
    fun rowsCarryTheNextPassAndTheRadioFlag() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(SatellitesEvent.SegmentSelected(CatalogSegment.POPULAR))
        val state = viewModel.state.first { it.segment == CatalogSegment.POPULAR && it.rows.isNotEmpty() }

        val noaa = state.rows.first { it.noradId == 33591 }
        assertEquals(20.minutes, noaa.nextPassIn)
        assertEquals(62.0, noaa.nextPassMaxElevationDeg)
        assertTrue(!noaa.hasDownlink, "NOAA 19 has no transmitter in this fixture")

        assertTrue(state.rows.first { it.noradId == 25544 }.hasDownlink)
    }

    @Test
    fun classFilterIsFreeAndNarrowsTheList() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(SatellitesEvent.SegmentSelected(CatalogSegment.ALL))
        viewModel.state.first { it.segment == CatalogSegment.ALL }

        viewModel.onEvent(SatellitesEvent.FiltersChanged(SatelliteFilters(classes = setOf(SatClass.WEATHER))))

        val state = viewModel.state.first { it.filters.classes.isNotEmpty() && it.rows.size == 1 }
        assertEquals(listOf(33591), state.rows.map { it.noradId })
    }

    @Test
    fun bandFiltersApplyForEveryone() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.state.first { !it.loading }

        viewModel.onEvent(SatellitesEvent.SegmentSelected(CatalogSegment.ALL))
        viewModel.onEvent(SatellitesEvent.FiltersChanged(SatelliteFilters(bands = setOf(RadioBand.UHF))))

        val state = viewModel.state.first { it.filters.bands.isNotEmpty() }
        assertEquals(setOf(RadioBand.UHF), state.filters.bands)
    }

    /**
     * The star on a row reaches storage.
     *
     * The single most important interaction added with the tracking selection, and the one most
     * easily broken by a state projection that has not caught up — which is why the view model
     * reads the selection's own flow rather than `state.value` when it toggles.
     */
    @Test
    fun tappingTheStarSelectsTheSatelliteForTracking() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.state.first { !it.loading }

        viewModel.onEvent(SatellitesEvent.TrackToggled(25544))
        testScheduler.advanceUntilIdle()
        assertTrue(25544 in selection.selected.value)

        viewModel.onEvent(SatellitesEvent.TrackToggled(25544))
        testScheduler.advanceUntilIdle()
        assertTrue(25544 !in selection.selected.value)
    }

    @Test
    fun bandFilterWorksOnceEntitled() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(SatellitesEvent.SegmentSelected(CatalogSegment.ALL))
        viewModel.state.first { it.segment == CatalogSegment.ALL }

        viewModel.onEvent(SatellitesEvent.FiltersChanged(SatelliteFilters(bands = setOf(RadioBand.UHF))))

        // AO-07 at 435 MHz and the CubeSat at 437 MHz; the ISS is VHF and the rocket body L-band.
        val state = viewModel.state.first { it.filters.bands.isNotEmpty() && it.rows.size == 2 }
        assertEquals(setOf(7530, 99001), state.rows.map { it.noradId }.toSet())
    }

    @Test
    fun clearingFiltersRestoresTheFullSegment() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(SatellitesEvent.SegmentSelected(CatalogSegment.ALL))
        viewModel.onEvent(SatellitesEvent.FiltersChanged(SatelliteFilters(classes = setOf(SatClass.WEATHER))))
        viewModel.state.first { it.filters.activeCount == 1 }

        viewModel.onEvent(SatellitesEvent.FiltersCleared)

        assertEquals(5, viewModel.state.first { it.filters.isEmpty }.rows.size)
    }

    @Test
    fun pullingToRefreshStartsOneAndSurfacesRealProgress() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(SatellitesEvent.RefreshRequested)
        assertEquals(1, catalogRefresher.calls, "the pull did not reach the refresher")

        val started = viewModel.state.first { it.refreshing }
        val running = assertIs<RefreshState.Running>(started.refresh)
        assertEquals(11, running.progress.total)
        assertEquals(0f, running.progress.fraction)

        catalogRefresher.advanceTo(completed = 5, label = "CelesTrak · CubeSats")

        val midway = viewModel.state.first { (it.refresh as? RefreshState.Running)?.progress?.completed == 5 }
        val progress = (midway.refresh as RefreshState.Running).progress
        assertEquals("CelesTrak · CubeSats", progress.label)
        assertTrue(progress.fraction in 0.4f..0.5f, "fraction was ${progress.fraction}")
    }

    @Test
    fun theOutcomeIsReportedWhenTheRefreshFinishes() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.effects.test {
            viewModel.onEvent(SatellitesEvent.RefreshRequested)
            catalogRefresher.finish(null, "Updated 12 satellites and 30 downlinks.")

            val message = assertIs<SatellitesEffect.ShowMessage>(awaitItem())
            assertTrue(message.text.contains("12"), "expected the updated count, got: ${message.text}")
        }
        assertTrue(!viewModel.state.first().refreshing, "the indicator should have cleared")
    }

    @Test
    fun aSecondPullWhileRefreshingIsIgnored() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(SatellitesEvent.RefreshRequested)
        viewModel.state.first { it.refreshing }
        viewModel.onEvent(SatellitesEvent.RefreshRequested)

        assertEquals(1, catalogRefresher.calls, "a second pull mid-refresh started another fetch")
    }

    /**
     * The refresh belongs to the application, not to this screen. A user who pulls to refresh and
     * then opens a satellite must not silently lose the fetch they started.
     */
    @Test
    fun aRefreshSurvivesTheScreenThatStartedIt() = runTest(mainDispatcher) {
        viewModel().onEvent(SatellitesEvent.RefreshRequested)

        // A fresh view model — as if the user navigated away and came back — still sees it.
        val reopened = viewModel()

        assertTrue(reopened.state.first { it.refreshing }.refreshing)
        assertEquals(1, catalogRefresher.calls, "the refresh was restarted rather than resumed")
    }

    @Test
    fun tappingARowAsksToOpenIt() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.effects.test {
            viewModel.onEvent(SatellitesEvent.RowClicked(25544))
            assertEquals(SatellitesEffect.OpenDetail(25544), awaitItem())
        }
    }

    @Test
    fun freshnessIsCarriedThroughToTheBanner() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        assertIs<TleFreshness.Fresh>(viewModel.state.first { !it.loading }.freshness)

        tles.freshness.value = TleFreshness.Stale(9.days)

        assertIs<TleFreshness.Stale>(viewModel.state.first { it.freshness is TleFreshness.Stale }.freshness)
    }

    @Test
    fun theFilterSheetIsOpenedAndDismissedThroughState() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.state.first { !it.loading }

        viewModel.onEvent(SatellitesEvent.FilterSheetOpened)
        assertTrue(viewModel.state.first { it.filterSheetOpen }.filterSheetOpen)

        viewModel.onEvent(SatellitesEvent.FilterSheetDismissed)
        assertTrue(!viewModel.state.first { !it.filterSheetOpen }.filterSheetOpen)
    }
}
