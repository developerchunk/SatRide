package com.developerstring.satride.core.data

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.notifications.AlertCapability
import com.developerstring.satride.core.notifications.AlertStore
import com.developerstring.satride.core.notifications.DefaultAlertScheduler
import com.developerstring.satride.core.notifications.PassAlert
import com.developerstring.satride.core.notifications.PlatformAlertScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The regression this guards: on a fresh install the location permission is granted *after*
 * startup, so [CatalogSync]'s one-shot precompute finds no position and computes nothing — and
 * without this coordinator the timeline stayed empty until the next app launch.
 */
class PassPrecomputeCoordinatorTest {

    private val bangalore = GeoPos(12.97, 77.59)

    @Test
    fun firstFixTriggersAPrecompute() = runTest {
        val world = world()
        backgroundScope.launch { world.coordinator.watch() }

        runCurrent()
        assertEquals(0, world.passes.refreshCalls, "no position yet, so nothing to compute")

        world.positions.value = bangalore
        runCurrent()
        assertEquals(1, world.passes.refreshCalls, "the first fix must fill the pass cache")
    }

    @Test
    fun gpsJitterInsideTheSameCacheCellDoesNotRecompute() = runTest {
        val world = world()
        backgroundScope.launch { world.coordinator.watch() }

        world.positions.value = bangalore
        runCurrent()
        world.positions.value = GeoPos(bangalore.latDeg + 0.0001, bangalore.lonDeg + 0.0001)
        runCurrent()

        assertEquals(1, world.passes.refreshCalls, "metres of jitter must not re-run SGP4 across the catalog")
    }

    @Test
    fun movingToANewCellRecomputes() = runTest {
        val world = world()
        backgroundScope.launch { world.coordinator.watch() }

        world.positions.value = bangalore
        runCurrent()
        world.positions.value = GeoPos(28.61, 77.20) // Delhi: a different cache cell entirely.
        runCurrent()

        assertEquals(2, world.passes.refreshCalls, "a new observer cell has an empty cache to fill")
    }

    /**
     * The complaint this closes: turning on "track everything" and finding the timeline unchanged.
     *
     * The walk is keyed on the selection as well as the position, so enlarging what is tracked has
     * to start predicting the new satellites rather than leaving the timeline on whatever was
     * computed for the old, smaller set.
     */
    @Test
    fun enlargingTheSelectionRestartsTheWalk() = runTest {
        val world = world()
        backgroundScope.launch { world.coordinator.watch() }

        world.positions.value = bangalore
        runCurrent()
        assertEquals(1, world.passes.walkCalls)

        world.selection.value = IntArray(5_000) { 40_000 + it }
        runCurrent()

        assertEquals(2, world.passes.walkCalls, "a bigger selection has satellites nobody has predicted")
    }

    /** And the screen can say how far through it is, then stop saying anything once it is done. */
    @Test
    fun coverageReportsTheSelectionAndThenCompletes() = runTest {
        val world = world()
        backgroundScope.launch { world.coordinator.watch() }

        world.selection.value = IntArray(1_200) { 40_000 + it }
        world.positions.value = bangalore
        runCurrent()

        val coverage = world.coordinator.coverage.value
        assertEquals(1_200, coverage.tracked)
        assertEquals(1_200, coverage.predicted)
        assertEquals(true, coverage.complete)
        assertEquals(false, coverage.running)
    }

    // --- fakes -------------------------------------------------------------------------------

    private class World(
        val coordinator: PassPrecomputeCoordinator,
        val positions: MutableStateFlow<GeoPos?>,
        val passes: RecordingPassRepository,
        val selection: MutableStateFlow<IntArray>,
    )

    private fun kotlinx.coroutines.test.TestScope.world(): World {
        val positions = MutableStateFlow<GeoPos?>(null)
        val selection = MutableStateFlow(intArrayOf(25544))
        val passes = RecordingPassRepository(selection)
        val store = EmptyStore()
        val clock = Clock.System
        val alertCoordinator = PassAlertCoordinator(
            scheduler = DefaultAlertScheduler(store, NoPlatform(), clock),
            store = store,
            passRepository = passes,
            tleRepository = EmptyTleRepository(),
            observerPositions = { positions },
            clock = clock,
        )
        return World(
            coordinator = PassPrecomputeCoordinator(
                observerPositions = { positions },
                passRepository = passes,
                selectionRepository = SelectionOf(selection),
                alertCoordinator = alertCoordinator,
                dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler)),
            ),
            positions = positions,
            passes = passes,
            selection = selection,
        )
    }

    /** A selection the test can change; nothing else on the port is exercised here. */
    private class SelectionOf(private val ids: MutableStateFlow<IntArray>) : TrackingSelectionRepository {
        override fun selection(): Flow<TrackingSelection> = ids.map {
            TrackingSelection(
                com.developerstring.satride.core.datastore.TrackingMode.SELECTED,
                com.developerstring.satride.core.datastore.TrackingBudget.default,
                it,
            )
        }

        override fun observeSelectedIds(): Flow<List<Int>> = ids.map { it.toList() }
        override fun observeSelectedCount(): Flow<Int> = ids.map { it.size }
        override fun observeCatalogCount(): Flow<Int> = ids.map { it.size }
        override fun observeClassCounts(): Flow<Map<com.developerstring.satride.core.model.SatClass, Int>> =
            flowOf(emptyMap())

        override fun browse(
            query: CatalogQuery,
            limit: Int,
            offset: Int,
        ): Flow<List<com.developerstring.satride.core.model.Satellite>> = flowOf(emptyList())

        override fun countMatching(query: CatalogQuery): Flow<Int> = flowOf(0)
        override suspend fun setMode(mode: com.developerstring.satride.core.datastore.TrackingMode) = Unit
        override suspend fun setBudget(budget: com.developerstring.satride.core.datastore.TrackingBudget) = Unit
        override suspend fun setTracked(noradIds: Collection<Int>, tracked: Boolean) = Unit
        override suspend fun setTrackedForQuery(query: CatalogQuery, tracked: Boolean) = 0
        override suspend fun selectAll() = 0
        override suspend fun clearSelection() = Unit
        override suspend fun selectCurated() = 0
        override suspend fun selectWithDownlink() = 0
    }

    private class RecordingPassRepository(private val selection: MutableStateFlow<IntArray>) : PassRepository {
        var refreshCalls = 0
        var walkCalls = 0
        override fun upcoming(window: Duration): Flow<List<Pass>> = flowOf(emptyList())
        override suspend fun passesFor(noradId: Int, window: Duration): List<Pass> = emptyList()
        override suspend fun passesFor(noradId: Int, observer: GeoPos, window: Duration): List<Pass> = emptyList()
        override suspend fun nextPass(noradId: Int, observer: GeoPos): Pass? = null
        override suspend fun prune() = Unit
        override suspend fun refreshUpcoming(window: Duration): Int {
            refreshCalls++
            return 0
        }

        override suspend fun precomputeSelection(
            window: Duration,
            onProgress: ((done: Int, total: Int) -> Unit)?,
        ): Int {
            walkCalls++
            // Reports against the real selection, the way the repository does — otherwise the
            // coverage this test asserts on would only ever be the fake's own invention.
            val size = selection.value.size
            onProgress?.invoke(size, size)
            return 0
        }
    }

    private class EmptyStore : AlertStore {
        override fun observeAll(): Flow<List<PassAlert>> = MutableStateFlow(emptyList<PassAlert>()).map { it }
        override suspend fun all(): List<PassAlert> = emptyList()
        override suspend fun put(alert: PassAlert) = Unit
        override suspend fun remove(alertId: String) = Unit
        override suspend fun removePassed(now: Instant) = Unit
    }

    private class NoPlatform : PlatformAlertScheduler {
        override val capability = AlertCapability(supported = false)
        override suspend fun arm(alert: PassAlert): Result<Unit> = Result.success(Unit)
        override suspend fun disarm(alertId: String) = Unit
    }

    /** Never consulted: the alert store is empty, so reconcile never needs an element set. */
    private class EmptyTleRepository : TleRepository {
        override suspend fun tleFor(noradId: Int): com.developerstring.satride.core.model.Tle? = null
        override suspend fun tlesFor(noradIds: List<Int>) = emptyMap<Int, com.developerstring.satride.core.model.Tle>()
        override fun freshness(): Flow<TleFreshness> = flowOf(TleFreshness.Empty)
        override suspend fun elementAgeOf(noradId: Int): Duration? = null
        override suspend fun refresh(
            groups: Set<com.developerstring.satride.core.network.GpGroup>,
            onProgress: (RefreshProgress) -> Unit,
        ): RefreshResult =
            RefreshResult(sources = emptyList(), tlesUpdated = 0, satellitesSeen = 0, transmittersUpdated = 0)

        override suspend fun refreshIfStale(
            groups: Set<com.developerstring.satride.core.network.GpGroup>,
        ): RefreshResult? = null
    }
}
