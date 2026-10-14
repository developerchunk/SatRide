package com.developerstring.satride.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.developerstring.satride.core.common.MutableClock
import com.developerstring.satride.core.database.SatRideDatabase
import com.developerstring.satride.core.database.toDomain
import com.developerstring.satride.core.database.toEntity
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.orbit.sgp4.TleFormatter
import com.developerstring.satride.core.orbit.sgp4.TleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Pass prediction as the app consumes it: stored elements in, cached passes out.
 *
 * The arithmetic itself was verified against Skyfield in P1; what is being checked here is that
 * the cache returns the right answer and — more importantly — refuses to return a wrong one.
 */
class PassRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder<SatRideDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()

    // Just after the ISS element set in the fixtures, so predictions are from fresh elements.
    private val clock = MutableClock(Instant.parse("2026-08-02T00:00:00Z"))
    private val london = GeoPos(51.5074, -0.1278, 35.0)

    private val preferencesPath = "${FileSystem.SYSTEM_TEMPORARY_DIRECTORY}/satride-pass-${nextFile++}.preferences_pb"
    private val settings = SettingsRepository(
        PreferenceDataStoreFactory.createWithPath(produceFile = { preferencesPath.toPath() }),
    )

    @AfterTest
    fun tearDown() {
        db.close()
        runCatching { FileSystem.SYSTEM.delete(preferencesPath.toPath()) }
    }

    private val issTle: Tle by lazy {
        val lines = TestFixtures.read("ariss_iss.txt").lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        TleFormatter.format(TleParser.parse(lines[0], lines[1], lines[2]))
    }

    private suspend fun storeIss() {
        db.tleDao().upsert(listOf(issTle.toEntity(fetchedAt = clock.now(), sourceId = "test")))
    }

    private fun TestScope.repository(position: GeoPos? = london): DefaultPassRepository {
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler))
        val tleRepository = object : TleRepository {
            override suspend fun tleFor(noradId: Int): Tle? = db.tleDao().find(noradId)?.toDomain()

            override suspend fun tlesFor(noradIds: List<Int>) = emptyMap<Int, Tle>()
            override fun freshness() = flowOf(TleFreshness.Empty)
            override suspend fun elementAgeOf(noradId: Int): kotlin.time.Duration? = null
            override suspend fun refresh(
                groups: Set<com.developerstring.satride.core.network.GpGroup>,
                onProgress: (RefreshProgress) -> Unit,
            ) = RefreshResult.empty()

            override suspend fun refreshIfStale(groups: Set<com.developerstring.satride.core.network.GpGroup>) = null
        }
        return DefaultPassRepository(
            passCacheDao = db.passCacheDao(),
            satelliteDao = db.satelliteDao(),
            tleRepository = tleRepository,
            selectionRepository = EmptySelection,
            observerPositions = { flowOf(position) },
            settings = settings,
            dispatchers = dispatchers,
            clock = clock,
        )
    }

    @Test
    fun predictsRealPassesForARealObserver() = runTest {
        storeIss()

        val passes = repository().passesFor(25544, london, 2.days)

        // The ISS is inclined 51.6 degrees and London sits at 51.5, so it passes overhead many
        // times a day. Anything near zero would mean the chain is broken, not that the sky is quiet.
        assertTrue(passes.size >= 4, "expected several ISS passes over London in two days, got ${passes.size}")
        passes.forEach {
            assertTrue(it.aos < it.tca && it.tca < it.los, "pass times out of order: $it")
            assertTrue(it.maxElevationDeg > 0.0)
            assertTrue(it.arc.isNotEmpty(), "a pass with no arc cannot be plotted")
        }
    }

    @Test
    fun cachesSoASecondRequestDoesNotRecompute() = runTest {
        storeIss()
        val repository = repository()

        val first = repository.passesFor(25544, london, 2.days)
        val cachedRows = db.passCacheDao().count()
        val second = repository.passesFor(25544, london, 2.days)

        assertTrue(cachedRows > 0, "nothing was cached")
        // Compared as epoch milliseconds, which is the cache's storage resolution. The propagator
        // returns finer than that; rounding it away is four orders of magnitude below the ten
        // seconds the predictor itself was verified to, so it is not worth a wider column.
        assertEquals(
            first.map { it.aos.toEpochMilliseconds() },
            second.map { it.aos.toEpochMilliseconds() },
        )
        assertEquals(cachedRows, db.passCacheDao().count(), "the second call recomputed and rewrote")
    }

    /**
     * A cached pass from another place is not stale, it is wrong — and it would look entirely
     * plausible in the UI, which is what makes it worth a test of its own.
     */
    @Test
    fun doesNotServeAPassComputedForSomewhereElse() = runTest {
        storeIss()
        val repository = repository()
        repository.passesFor(25544, london, 2.days)

        val sydney = GeoPos(-33.8688, 151.2093)
        val sydneyPasses = repository.passesFor(25544, sydney, 2.days)

        assertTrue(sydneyPasses.isNotEmpty())
        assertTrue(
            sydneyPasses.map { it.aos }.toSet() != repository.passesFor(25544, london, 2.days).map { it.aos }.toSet(),
            "Sydney was given London's passes",
        )
    }

    @Test
    fun recomputesWhenTheElementSetIsReplaced() = runTest {
        storeIss()
        val repository = repository()
        repository.passesFor(25544, london, 2.days)
        val before = db.passCacheDao()
            .forSatellite(25544, "gcpvj", issTle.epoch.toEpochMilliseconds(), clock.now().toEpochMilliseconds())

        // A newer element set for the same satellite invalidates everything derived from the old one.
        val newer = issTle.copy(epoch = issTle.epoch + 1.days)
        db.tleDao().upsert(listOf(newer.toEntity(fetchedAt = clock.now(), sourceId = "test")))
        val after = repository.passesFor(25544, london, 2.days)

        assertTrue(before.isNotEmpty())
        assertTrue(after.isNotEmpty())
        assertTrue(
            db.passCacheDao().count() > before.size,
            "the new epoch reused the old cache instead of recomputing",
        )
    }

    @Test
    fun returnsNothingWhenTheSatelliteHasNoElementSet() = runTest {
        assertTrue(repository().passesFor(99999, london, 2.days).isEmpty())
    }

    @Test
    fun reportsNoPassesRatherThanGuessingWhenThePositionIsUnknown() = runTest {
        storeIss()

        assertTrue(repository(position = null).passesFor(25544, 2.days).isEmpty())
        assertTrue(repository(position = null).upcoming(2.days).first().isEmpty())
    }

    @Test
    fun appliesTheUsersMinimumElevationFilter() = runTest {
        storeIss()
        settings.setMinimumPassElevation(0.0)
        val all = repository().passesFor(25544, london, 3.days)

        db.passCacheDao().deleteForSatellite(25544)
        settings.setMinimumPassElevation(40.0)
        val highOnly = repository().passesFor(25544, london, 3.days)

        assertTrue(all.size > highOnly.size, "the filter changed nothing: ${all.size} vs ${highOnly.size}")
        assertTrue(highOnly.all { it.maxElevationDeg >= 40.0 })
    }

    @Test
    fun observesUpcomingPassesFromTheCache() = runTest {
        storeIss()
        val repository = repository()
        repository.passesFor(25544, london, 2.days)

        val upcoming = repository.upcoming(2.days).first()

        assertTrue(upcoming.isNotEmpty())
        assertTrue(upcoming.zipWithNext().all { (a, b) -> a.aos <= b.aos }, "passes are not in time order")
    }

    @Test
    fun pruningClearsPassesThatHaveAlreadyHappened() = runTest {
        storeIss()
        val repository = repository()
        repository.passesFor(25544, london, 2.days)
        val before = db.passCacheDao().count()

        clock.advanceBy(3.days)
        repository.prune()

        assertTrue(before > 0)
        assertEquals(0, db.passCacheDao().count())
    }

    /**
     * Nothing selected.
     *
     * These tests are about prediction and caching for one satellite at a time, which is the path
     * a detail screen takes. Precomputation over a selection is covered where the selection is —
     * putting a fake selection here would only test this fake.
     */
    private object EmptySelection : TrackingSelectionRepository {
        override fun selection() = flowOf(TrackingSelection.EMPTY)
        override fun observeSelectedIds() = flowOf(emptyList<Int>())
        override fun observeSelectedCount() = flowOf(0)
        override fun observeCatalogCount() = flowOf(0)
        override fun observeClassCounts() = flowOf(emptyMap<com.developerstring.satride.core.model.SatClass, Int>())

        override fun browse(query: CatalogQuery, limit: Int, offset: Int) =
            flowOf(emptyList<com.developerstring.satride.core.model.Satellite>())

        override fun countMatching(query: CatalogQuery) = flowOf(0)
        override suspend fun setMode(mode: com.developerstring.satride.core.datastore.TrackingMode) = Unit
        override suspend fun setBudget(budget: com.developerstring.satride.core.datastore.TrackingBudget) = Unit
        override suspend fun setTracked(noradIds: Collection<Int>, tracked: Boolean) = Unit
        override suspend fun setTrackedForQuery(query: CatalogQuery, tracked: Boolean) = 0
        override suspend fun selectAll() = 0
        override suspend fun clearSelection() = Unit
        override suspend fun selectCurated() = 0
        override suspend fun selectWithDownlink() = 0
    }

    private companion object {
        private var nextFile: Int = 0
    }
}
