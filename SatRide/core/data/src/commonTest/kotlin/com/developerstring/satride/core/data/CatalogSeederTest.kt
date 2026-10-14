package com.developerstring.satride.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.developerstring.satride.core.common.MutableClock
import com.developerstring.satride.core.database.SatRideDatabase
import com.developerstring.satride.core.database.toDomain
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.TxMode
import com.developerstring.satride.core.network.SatRideHttpClient
import com.developerstring.satride.core.orbit.Orbit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The offline first-launch path.
 *
 * If this is broken, a new user's first experience is an empty screen and a spinner — which for an
 * app whose whole claim is that it works without a network is the worst possible failure, and one
 * that only shows up on a fresh install.
 */
class CatalogSeederTest {

    private val db = Room.inMemoryDatabaseBuilder<SatRideDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()

    private val clock = MutableClock(Instant.parse("2026-08-02T12:00:00Z"))

    @AfterTest
    fun tearDown() = db.close()

    private fun TestScope.seeder(json: String?): CatalogSeeder = CatalogSeeder(
        source = { json },
        satelliteDao = db.satelliteDao(),
        tleDao = db.tleDao(),
        transmitterDao = db.transmitterDao(),
        dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
    )

    private val snapshotJson: String get() = TestFixtures.read("catalog_snapshot_sample.json")

    @Test
    fun seedsTheBundledCatalogIntoAnEmptyDatabase() = runTest {
        val result = seeder(snapshotJson).seedIfEmpty().getOrThrow()

        assertTrue(!result.skipped)
        assertEquals(6, result.satellites)
        assertTrue(result.transmitters > 50, "only ${result.transmitters} transmitters seeded")
        assertEquals(6, db.satelliteDao().count())
        assertEquals(6, db.tleDao().count())
    }

    /** The point of the bundle: it is what the catalog screen shows before any refresh. */
    @Test
    fun everythingSeededIsMarkedCurated() = runTest {
        seeder(snapshotJson).seedIfEmpty().getOrThrow()

        val curated = db.satelliteDao().observeCurated().first()

        assertEquals(6, curated.size)
        assertTrue(curated.all { it.curated })
    }

    @Test
    fun classifiesSeededSatellitesFromTheirRecordedGroup() = runTest {
        seeder(snapshotJson).seedIfEmpty().getOrThrow()

        assertEquals(SatClass.CREWED.name, db.satelliteDao().find(25544)?.classType)
        assertEquals(SatClass.WEATHER.name, db.satelliteDao().find(33591)?.classType, "NOAA 19")
    }

    @Test
    fun seededElementSetsAreUsableByThePropagator() = runTest {
        seeder(snapshotJson).seedIfEmpty().getOrThrow()

        val iss = db.tleDao().find(25544)!!
        val satellite = Orbit.satellite(iss.toDomain())

        val state = satellite.stateAt(clock.now())
        assertTrue(
            state.position.magnitude in 6500.0..7200.0,
            "seeded ISS elements put it at ${state.position.magnitude} km from Earth centre",
        )
    }

    @Test
    fun seededTransmittersReachTheRadioSide() = runTest {
        seeder(snapshotJson).seedIfEmpty().getOrThrow()

        val issTransmitters = db.transmitterDao().forSatellite(25544)

        assertTrue(issTransmitters.isNotEmpty())
        assertTrue(
            issTransmitters.any { it.mode == TxMode.SSTV.name },
            "the ISS SSTV downlink must be available offline — it is the headline decode feature",
        )
    }

    /**
     * Re-seeding after a refresh would overwrite fresher element sets with whatever was true on
     * the day the release was built — a regression that would only show as slightly wrong pass
     * times, which is exactly the kind of bug nobody reports.
     */
    @Test
    fun doesNotSeedOverACatalogThatAlreadyExists() = runTest {
        val seeder = seeder(snapshotJson)
        seeder.seedIfEmpty().getOrThrow()

        val second = seeder.seedIfEmpty().getOrThrow()

        assertTrue(second.skipped)
        assertEquals(0, second.satellites)
    }

    /**
     * The bundle bridges the gap until the first refresh; it must not suppress that refresh.
     *
     * Stamping the seeded rows with "now" made the refresh policy — which measures from the last
     * fetch — treat a months-old bundle as freshly downloaded, so a new install ran on stale
     * elements until a full refresh interval had passed. Found by watching the first launch, not
     * by a failing test, which is why there is one now.
     */
    @Test
    fun seededElementSetsAreStampedWithTheSnapshotsAgeNotTheInstallTime() = runTest {
        val snapshot = SatRideHttpClient.json.decodeFromString<CatalogSnapshot>(snapshotJson)
        clock.current = Instant.parse(snapshot.generatedAt) + 40.days

        seeder(snapshotJson).seedIfEmpty().getOrThrow()

        val lastFetch = db.tleDao().lastFetchMillis()!!
        assertTrue(
            clock.now() - Instant.fromEpochMilliseconds(lastFetch) > 30.days,
            "the bundle was recorded as freshly fetched, which would delay the first real refresh",
        )
    }

    @Test
    fun survivesAMissingBundle() = runTest {
        val result = seeder(null).seedIfEmpty().getOrThrow()

        assertTrue(result.skipped)
        assertEquals(0, db.satelliteDao().count())
    }

    @Test
    fun reportsACorruptBundleInsteadOfCrashingOnLaunch() = runTest {
        val result = seeder("{ not json at all").seedIfEmpty()

        assertTrue(result.isFailure)
        assertEquals(0, db.satelliteDao().count())
    }
}
