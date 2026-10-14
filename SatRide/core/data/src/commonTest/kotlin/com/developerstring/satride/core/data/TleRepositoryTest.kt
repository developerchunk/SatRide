package com.developerstring.satride.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.developerstring.satride.core.common.MutableClock
import com.developerstring.satride.core.database.SatRideDatabase
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.core.network.GpGroup
import com.developerstring.satride.core.network.api.TleApi
import kotlinx.coroutines.flow.first
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The refresh path end to end: recorded responses in, merged catalog out.
 *
 * Uses a real in-memory database and the real Ktor stack over MockEngine rather than fakes, so
 * what is being verified is the behaviour that will run on a device — including the SQL that
 * implements the merge rule.
 */
class TleRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder<SatRideDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(kotlinx.coroutines.Dispatchers.Default)
        .build()

    private val clock = MutableClock(Instant.parse("2026-08-02T12:00:00Z"))
    private val preferencesPath = "${FileSystem.SYSTEM_TEMPORARY_DIRECTORY}/satride-data-${nextFile++}.preferences_pb"
    private val settings = SettingsRepository(
        PreferenceDataStoreFactory.createWithPath(produceFile = { preferencesPath.toPath() }),
    )

    @AfterTest
    fun tearDown() {
        db.close()
        runCatching { FileSystem.SYSTEM.delete(preferencesPath.toPath()) }
    }

    /**
     * Built on the test's own scheduler, so the retry plugin's exponential backoff runs in virtual
     * time. On a real dispatcher a single 503 would cost fourteen seconds of wall clock.
     */
    private fun TestScope.repository(engine: RoutingEngine): DefaultTleRepository {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DefaultTleRepository(
            tleApi = TleApi(engine.client()),
            tleDao = db.tleDao(),
            satelliteDao = db.satelliteDao(),
            settings = settings,
            dispatchers = TestDispatchers(dispatcher),
            clock = clock,
        )
    }

    private fun allSourcesRespond(): RoutingEngine = RoutingEngine()
        .onUrlContaining("GROUP=stations", TestFixtures.read("celestrak_stations.json"))
        .onUrlContaining("live.ariss.org", TestFixtures.read("ariss_iss.txt"))
        .onUrlContaining("amsat.org", TestFixtures.read("amsat_nasabare_head.txt"))

    @Test
    fun mergesEverySourceIntoTheCatalog() = runTest {
        val result = repository(allSourcesRespond()).refresh(setOf(GpGroup.STATIONS))

        assertTrue(result.tlesUpdated > 20, "only ${result.tlesUpdated} element sets stored")
        assertTrue(db.satelliteDao().count() > 20)
        assertTrue(result.succeededSources.isNotEmpty())
    }

    /**
     * The rule the whole multi-source design rests on. ARISS and CelesTrak both carry the ISS;
     * whichever has the newer epoch has to win, regardless of which source it is.
     */
    @Test
    fun theNewestEpochWinsAcrossSources() = runTest {
        // The recorded ARISS and CelesTrak fixtures hold the same ISS element set, so the merge is
        // forced with a deliberately newer one from AMSAT's slot.
        val newerIss = buildString {
            appendLine("ISS (ZARYA)")
            appendLine("1 25544U 98067A   26214.50000000  .00007322  00000-0  13941-3 0  9999")
            appendLine("2 25544  51.6314  74.3802 0007140   2.2740 357.8280 15.49304641578823")
        }
        val engine = allSourcesRespond().let {
            RoutingEngine()
                .onUrlContaining("GROUP=stations", TestFixtures.read("celestrak_stations.json"))
                .onUrlContaining("live.ariss.org", TestFixtures.read("ariss_iss.txt"))
                .onUrlContaining("amsat.org", newerIss)
        }

        repository(engine).refresh(setOf(GpGroup.STATIONS))

        val stored = db.tleDao().find(25544)!!
        assertEquals(
            "amsat-nasabare",
            stored.sourceId,
            "the newer AMSAT epoch should have won regardless of source priority",
        )
    }

    @Test
    fun anOlderSourceDoesNotOverwriteAFresherStoredElementSet() = runTest {
        val stale = buildString {
            appendLine("ISS (ZARYA)")
            appendLine("1 25544U 98067A   26100.00000000  .00007322  00000-0  13941-3 0  9995")
            appendLine("2 25544  51.6314  74.3802 0007140   2.2740 357.8280 15.49304641578823")
        }
        val engine = RoutingEngine()
            .onUrlContaining("GROUP=stations", TestFixtures.read("celestrak_stations.json"))
            .onUrlContaining("live.ariss.org", stale)
            .failUrlContaining("amsat.org")

        repository(engine).refresh(setOf(GpGroup.STATIONS))

        val stored = db.tleDao().find(25544)!!
        assertEquals("celestrak-stations", stored.sourceId)
    }

    /**
     * Community servers go down. One outage must cost the user that source's satellites, not the
     * whole refresh — which is exactly what an all-or-nothing implementation would do.
     */
    @Test
    fun oneFailedSourceDoesNotLoseTheOthers() = runTest {
        val engine = RoutingEngine()
            .failUrlContaining("GROUP=stations")
            .onUrlContaining("live.ariss.org", TestFixtures.read("ariss_iss.txt"))
            .onUrlContaining("amsat.org", TestFixtures.read("amsat_nasabare_head.txt"))

        val result = repository(engine).refresh(setOf(GpGroup.STATIONS))

        assertTrue(result.isPartial, "expected a partial success, got $result")
        assertTrue(result.tlesUpdated > 5, "surviving sources produced only ${result.tlesUpdated}")
        assertTrue(result.failedSources.any { it.sourceId == "celestrak-stations" })
    }

    /**
     * The SatNOGS fallback is expensive — a quarter-megabyte of element sets we usually already
     * have — so it must only be reached when nothing else worked.
     */
    @Test
    fun theFallbackIsOnlyUsedWhenEverythingElseFails() = runTest {
        val working = allSourcesRespond()
        repository(working).refresh(setOf(GpGroup.STATIONS))
        assertTrue(
            working.requestedUrls.none { it.contains("db.satnogs.org") },
            "the fallback was fetched even though primary sources worked",
        )

        val allDown = RoutingEngine()
            .failUrlContaining("celestrak.org")
            .failUrlContaining("live.ariss.org")
            .failUrlContaining("amsat.org")
            .onUrlContaining("db.satnogs.org", TestFixtures.read("amsat_nasabare_head.txt"))

        val result = repository(allDown).refresh(setOf(GpGroup.STATIONS))

        assertTrue(allDown.requestedUrls.any { it.contains("db.satnogs.org") })
        assertTrue(result.tlesUpdated > 5, "the fallback produced nothing")
    }

    @Test
    fun reportsCompleteFailureWhenNothingIsReachable() = runTest {
        val engine = RoutingEngine()
            .failUrlContaining("celestrak.org")
            .failUrlContaining("live.ariss.org")
            .failUrlContaining("amsat.org")
            .failUrlContaining("db.satnogs.org")

        val result = repository(engine).refresh(setOf(GpGroup.STATIONS))

        assertTrue(result.isCompleteFailure)
        assertEquals(0, result.tlesUpdated)
        assertTrue(result.representativeError != null)
    }

    /**
     * "You already have this" is a success with nothing to fetch, not an outage.
     *
     * CelesTrak refuses a repeat request for an unchanged group with a 403 and an explanation, and
     * updates only every two hours — so this is the answer most refreshes get. Counted as a
     * failure it made the app report a broken catalog nearly all the time, and it fired the
     * SatNOGS fallback on every refresh: a quarter of a megabyte to re-download element sets we
     * already had.
     */
    @Test
    fun aSourceThatIsAlreadyCurrentIsNotAFailure() = runTest {
        val engine = RoutingEngine()
            .refuseUrlContaining(
                fragment = "celestrak.org",
                status = io.ktor.http.HttpStatusCode.Forbidden,
                content = "GP data has not updated since your last successful download of " +
                    "GROUP=stations at 2026-09-05 10:39:34 UTC. Data is updated once every 2 hours.",
            )
            .failUrlContaining("live.ariss.org")
            .failUrlContaining("amsat.org")

        val result = repository(engine).refresh(setOf(GpGroup.STATIONS))

        assertTrue(result.upToDateSources.isNotEmpty(), "the refusal was read as a failure")
        assertTrue(!result.isCompleteFailure, "nothing was broken; there was nothing to fetch")
        assertTrue(
            engine.requestedUrls.none { it.contains("db.satnogs.org") },
            "the fallback ran even though the catalog was already current",
        )
    }

    @Test
    fun freshnessFollowsTheOldestStoredEpoch() = runTest {
        val repository = repository(allSourcesRespond())
        assertEquals(TleFreshness.Empty, repository.freshness().first())

        repository.refresh(setOf(GpGroup.STATIONS))
        assertTrue(repository.freshness().first() is TleFreshness.Fresh)

        clock.advanceBy(5.days)
        assertTrue(repository.freshness().first() is TleFreshness.Aging)

        clock.advanceBy(5.days)
        val stale = repository.freshness().first()
        assertTrue(stale is TleFreshness.Stale, "expected Stale, got $stale")
        assertTrue(stale.needsRefresh)
    }

    @Test
    fun doesNotRefreshAgainInsideTheConfiguredInterval() = runTest {
        val engine = allSourcesRespond()
        val repository = repository(engine)

        repository.refresh(setOf(GpGroup.STATIONS))
        val afterFirst = engine.requestedUrls.size

        clock.advanceBy(2.hours)
        val skipped = repository.refreshIfStale(setOf(GpGroup.STATIONS))

        assertEquals(null, skipped)
        assertEquals(afterFirst, engine.requestedUrls.size, "a second fetch happened too soon")
    }

    @Test
    fun refreshesOnceTheIntervalHasElapsed() = runTest {
        val engine = allSourcesRespond()
        val repository = repository(engine)
        repository.refresh(setOf(GpGroup.STATIONS))
        val afterFirst = engine.requestedUrls.size

        clock.advanceBy(25.hours)
        val result = repository.refreshIfStale(setOf(GpGroup.STATIONS))

        assertTrue(result != null)
        assertTrue(engine.requestedUrls.size > afterFirst)
    }

    @Test
    fun respectsTheUsersChoiceToDisableAutomaticRefresh() = runTest {
        val engine = allSourcesRespond()
        val repository = repository(engine)
        settings.setAutoRefreshTles(false)

        assertEquals(null, repository.refreshIfStale(setOf(GpGroup.STATIONS)))
        assertTrue(engine.requestedUrls.isEmpty())
    }

    @Test
    fun classifiesSatellitesFromTheGroupTheyCameFrom() = runTest {
        repository(allSourcesRespond()).refresh(setOf(GpGroup.STATIONS))

        assertEquals("CREWED", db.satelliteDao().find(25544)?.classType)
    }

    private companion object {
        private var nextFile: Int = 0
    }
}
