package com.developerstring.satride.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.developerstring.satride.core.common.AppError
import com.developerstring.satride.core.common.MutableClock
import com.developerstring.satride.core.database.SatRideDatabase
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.network.GpGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SatelliteRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder<SatRideDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()

    private val clock = MutableClock(Instant.parse("2026-08-02T12:00:00Z"))

    @AfterTest
    fun tearDown() = db.close()

    private fun TestScope.repository() = DefaultSatelliteRepository(
        satelliteDao = db.satelliteDao(),
        tleDao = db.tleDao(),
        dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
        clock = clock,
    )

    private val validImport = TestFixtures.read("ariss_iss.txt")

    @Test
    fun importsElementSetsTheUserSupplied() = runTest {
        val repository = repository()

        val count = repository.importTle(validImport).getOrThrow()

        assertEquals(1, count)
        assertEquals("ISS (ZARYA)", repository.find(25544)?.name)
        assertEquals(
            DefaultSatelliteRepository.USER_IMPORT_SOURCE_ID,
            db.tleDao().find(25544)?.sourceId,
        )
    }

    @Test
    fun importsAMultiSatelliteFile() = runTest {
        val count = repository().importTle(TestFixtures.read("amsat_nasabare_head.txt")).getOrThrow()

        assertTrue(count >= 10, "only $count element sets imported")
    }

    /**
     * Strict here, lenient for catalogs. Text a person pasted is far more likely to have lost a
     * character than a catalog is to be serving corruption, so a checksum failure is real feedback
     * rather than a satellite quietly going missing.
     */
    @Test
    fun rejectsAnElementSetWhoseChecksumDoesNotMatch() = runTest {
        val lines = validImport.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val corrupted = lines[0] + "\n" +
            lines[1].dropLast(1) + (if (lines[1].last() == '7') '8' else '7') + "\n" +
            lines[2]

        val result = repository().importTle(corrupted)

        assertTrue(result.isFailure, "a corrupted checksum was accepted")
        assertEquals(0, db.tleDao().count())
    }

    @Test
    fun reportsTextThatContainsNoElementSets() = runTest {
        val result = repository().importTle("Here is the TLE you asked for, thanks!")

        assertTrue(result.exceptionOrNull() is AppError.InvalidInput)
    }

    @Test
    fun searchIgnoresAnEmptyQueryInsteadOfMatchingCharacterByCharacter() = runTest {
        val repository = repository()
        repository.importTle(TestFixtures.read("amsat_nasabare_head.txt")).getOrThrow()

        val all = repository.observeAll().first()
        val blank = repository.search("   ").first()

        assertEquals(all.size, blank.size)
    }

    @Test
    fun classifiesImportedSatellitesFromTheirNameAlone() = runTest {
        // A user's import carries no group, so the name is all there is to go on.
        repository().importTle(validImport).getOrThrow()

        assertEquals(SatClass.CREWED, repository().find(25544)?.classType)
    }

    @Test
    fun importedElementSetsSurviveACatalogRefresh() = runTest {
        val repository = repository()
        repository.importTle(validImport).getOrThrow()

        // Simulates what a refresh writes: catalog metadata for a satellite already present.
        db.satelliteDao().upsertFromCatalog(
            noradId = 25544,
            name = "ISS (ZARYA)",
            classType = SatClass.CREWED.name,
            internationalDesignator = "98067A",
        )

        assertEquals(
            DefaultSatelliteRepository.USER_IMPORT_SOURCE_ID,
            db.tleDao().find(25544)?.sourceId,
            "a catalog refresh discarded the user's own import",
        )
    }

    /**
     * `curated` is set by the bundled snapshot and must survive every later refresh — otherwise
     * the offline starter list silently empties the first time the network works, which is the
     * moment everything appears to be going right.
     */
    @Test
    fun aCatalogRefreshDoesNotClearTheCuratedFlag() = runTest {
        val seeder = CatalogSeeder(
            source = { TestFixtures.read("catalog_snapshot_sample.json") },
            satelliteDao = db.satelliteDao(),
            tleDao = db.tleDao(),
            transmitterDao = db.transmitterDao(),
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
        )
        seeder.seedIfEmpty().getOrThrow()
        assertEquals(6, db.satelliteDao().observeCurated().first().size)

        db.satelliteDao().upsertFromCatalog(
            noradId = 25544,
            name = "ISS (ZARYA) UPDATED",
            classType = SatelliteClassifier.classify("ISS (ZARYA)", GpGroup.STATIONS).name,
            internationalDesignator = "98067A",
        )

        val curated = db.satelliteDao().observeCurated().first()
        assertEquals(6, curated.size, "the refresh emptied the curated list")
        assertEquals("ISS (ZARYA) UPDATED", curated.first { it.noradId == 25544 }.name)
    }
}
