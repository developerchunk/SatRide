package com.developerstring.satride.feature.satellites

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.core.datastore.TrackingBudget
import com.developerstring.satride.core.datastore.TrackingMode
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Satellite
import com.developerstring.satride.core.network.GpGroup
import com.developerstring.satride.feature.satellites.tracking.TrackingEvent
import com.developerstring.satride.feature.satellites.tracking.TrackingViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tracking screen.
 *
 * Its job is to make "pick from every satellite in the catalog" tractable, so the tests are about
 * the operations that only matter at that scale — bulk selection, and the mode actually taking
 * effect — rather than about whether a checkbox draws.
 *
 * ## Why these await instead of advancing
 *
 * The view model writes through a real `SettingsRepository`, and DataStore runs its writes on its
 * own dispatcher rather than the test's. `advanceUntilIdle()` therefore returns while a write — and
 * the coroutine suspended on it — is still in flight, which passed reliably on an idle machine and
 * failed under a full parallel test run. Waiting on the observable outcome removes the timing
 * question entirely.
 */
class TrackingViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setMainDispatcher() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
        runCatching { FileSystem.SYSTEM.delete(preferencesPath.toPath()) }
    }

    private val preferencesPath =
        "${FileSystem.SYSTEM_TEMPORARY_DIRECTORY}/satride-tracking-${nextFile++}.preferences_pb"

    private val settings = SettingsRepository(
        PreferenceDataStoreFactory.createWithPath(produceFile = { preferencesPath.toPath() }),
    )

    private val catalog = MutableStateFlow(
        listOf(
            satellite(25544, "ISS (ZARYA)", SatClass.CREWED, curated = true),
            satellite(33591, "NOAA 19", SatClass.WEATHER, curated = true),
            satellite(7530, "AO-07", SatClass.AMATEUR),
            satellite(99001, "STARLINK-1007", SatClass.OTHER),
            satellite(99002, "STARLINK-1008", SatClass.OTHER),
        ),
    )

    private val selection = FakeTrackingSelectionRepository(catalog = catalog)
    private val tracker = FakeSatelliteTracker()
    private val refresher = FakeRefreshController()

    private fun TestScope.viewModel(): TrackingViewModel {
        val viewModel = TrackingViewModel(
            selectionRepository = selection,
            tracker = tracker,
            refreshController = refresher,
            settings = settings,
            dispatchers = TestDispatchers(mainDispatcher),
        )
        // The state is `WhileSubscribed`; without a live collector the upstream stops between
        // assertions and debounced work never runs.
        backgroundScope.launch { viewModel.state.collect { } }
        testScheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun opensOnTheStarterSetWithTheCatalogCounted() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val state = viewModel.state.first { !it.loading }

        assertEquals(TrackingMode.CURATED, state.mode)
        assertEquals(5, state.catalog)
        assertEquals(5, state.matching)
    }

    /**
     * The single most important control on the screen: a search turned into a selection in one tap.
     *
     * It has to select everything the filter matches rather than the page on screen, which is what
     * makes picking a constellation out of a catalog of tens of thousands possible at all.
     */
    @Test
    fun addingAllMatchesSelectsEveryMatchAndNotJustThePage() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.state.first { !it.loading }

        viewModel.onEvent(TrackingEvent.MatchingToggled(tracked = true))

        assertEquals(
            catalog.value.map { it.noradId }.toSet(),
            selection.selected.first { it.size == catalog.value.size },
        )
    }

    /**
     * Ticking something has to change what is tracked.
     *
     * Left in starter-set mode the tick would be stored and change nothing — a control that
     * appears to work and does not, which is worse than one that refuses.
     */
    @Test
    fun tickingASatelliteSwitchesOutOfTheStarterSet() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.state.first { !it.loading }

        viewModel.onEvent(TrackingEvent.RowToggled(99001))

        assertTrue(99001 in selection.selected.first { 99001 in it })
        assertEquals(TrackingMode.SELECTED, selection.mode.first { it == TrackingMode.SELECTED })
    }

    /** Un-ticking must not drag the user back to the starter set they deliberately left. */
    @Test
    fun unTickingLeavesTheModeAlone() = runTest(mainDispatcher) {
        selection.mode.value = TrackingMode.SELECTED
        selection.selected.value = setOf(99001)
        val viewModel = viewModel()
        viewModel.state.first { !it.loading }

        viewModel.onEvent(TrackingEvent.RowToggled(99001))

        assertTrue(selection.selected.first { it.isEmpty() }.isEmpty())
        assertEquals(TrackingMode.SELECTED, selection.mode.value)
    }

    /**
     * "Show all possible satellites" means both halves: track everything, and fetch the rest of the
     * catalog. Doing only the first would quietly mean "everything we happen to have downloaded",
     * which is not what the button says.
     */
    @Test
    fun trackingEverythingAlsoFetchesTheRestOfTheCatalog() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.state.first { !it.loading }

        viewModel.onEvent(TrackingEvent.TrackEverything)

        // Awaited rather than advanced. Every step of this runs through DataStore, which writes on
        // its own real dispatcher, so the test scheduler reaches idle repeatedly while the view
        // model's coroutine is still suspended on a real thread. Waiting for the observable end
        // state is the only race-free way to assert on it.
        refresher.callCount.first { it == 1 }
        settings.settings.first { it.downloadFullCatalog }

        assertEquals(TrackingMode.ALL, selection.mode.value)
        assertEquals(GpGroup.everything, refresher.lastGroups)
    }

    @Test
    fun theStarterSetShortcutSelectsExactlyTheCuratedObjects() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.state.first { !it.loading }

        viewModel.onEvent(TrackingEvent.SelectStarterSet)

        assertEquals(setOf(25544, 33591), selection.selected.first { it.size == 2 })
    }

    @Test
    fun clearingEmptiesTheSelection() = runTest(mainDispatcher) {
        selection.selected.value = setOf(25544, 7530)
        val viewModel = viewModel()
        viewModel.state.first { !it.loading }

        viewModel.onEvent(TrackingEvent.ClearSelection)

        assertTrue(selection.selected.first { it.isEmpty() }.isEmpty())
    }

    @Test
    fun theBudgetIsStoredSoTheEngineCanReadIt() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.state.first { !it.loading }

        viewModel.onEvent(TrackingEvent.BudgetSelected(TrackingBudget.PERFORMANCE))

        assertEquals(
            TrackingBudget.PERFORMANCE,
            selection.budget.first { it == TrackingBudget.PERFORMANCE },
        )
    }

    /**
     * A new search starts at the top.
     *
     * Keeping a page size grown by scrolling would make the first keystroke after browsing deep
     * into the catalog re-query several hundred rows that are about to be thrown away.
     */
    @Test
    fun searchingResetsThePage() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.state.first { !it.loading }

        viewModel.onEvent(TrackingEvent.LoadMore)
        viewModel.onEvent(TrackingEvent.QueryChanged("starlink"))

        assertEquals("starlink", viewModel.state.first { it.query == "starlink" }.query)
    }

    /** Every dispatcher on the test's own, so `flowOn` stays under the virtual clock. */
    private class TestDispatchers(dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
    }

    private fun satellite(noradId: Int, name: String, classType: SatClass, curated: Boolean = false) =
        Satellite(noradId = noradId, name = name, classType = classType, curated = curated)

    private companion object {
        private var nextFile: Int = 0
    }
}
