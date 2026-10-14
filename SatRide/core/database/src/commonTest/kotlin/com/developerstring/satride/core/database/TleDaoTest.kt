package com.developerstring.satride.core.database

import com.developerstring.satride.core.database.entity.TleEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TleDaoTest {

    private val db = inMemoryTestDatabase()
    private val dao = db.tleDao()

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun store(noradId: Int, epochMillis: Long, sourceId: String, name: String = "SAT") {
        dao.upsertIfNewer(
            noradId = noradId,
            name = name,
            line1 = "1 line for $sourceId",
            line2 = "2 line for $sourceId",
            epochMillis = epochMillis,
            fetchedAtMillis = 1_000_000L,
            sourceId = sourceId,
        )
    }

    @Test
    fun storesAnElementSet() = runTest {
        store(25544, epochMillis = 5_000L, sourceId = "celestrak-stations")

        val found = dao.find(25544)

        assertEquals(5_000L, found?.epochMillis)
        assertEquals("celestrak-stations", found?.sourceId)
    }

    /**
     * The merge rule the whole multi-source strategy depends on: newest epoch wins, whichever
     * source it came from. Getting this backwards would mean a stale fallback quietly overwriting
     * fresh primary data on every refresh — invisible in the UI, and worth kilometres of error.
     */
    @Test
    fun anOlderEpochNeverOverwritesANewerOne() = runTest {
        store(25544, epochMillis = 9_000L, sourceId = "celestrak-stations")

        store(25544, epochMillis = 4_000L, sourceId = "satnogs-tle")

        assertEquals(9_000L, dao.find(25544)?.epochMillis)
        assertEquals("celestrak-stations", dao.find(25544)?.sourceId)
    }

    @Test
    fun aNewerEpochReplacesTheStoredOne() = runTest {
        store(25544, epochMillis = 4_000L, sourceId = "celestrak-stations")

        store(25544, epochMillis = 9_000L, sourceId = "ariss-iss")

        assertEquals(9_000L, dao.find(25544)?.epochMillis)
        assertEquals("ariss-iss", dao.find(25544)?.sourceId)
    }

    /**
     * Equal epochs mean the same element set arrived twice. Keeping the stored row avoids a
     * pointless write, and — more importantly — avoids invalidating the pass cache, which is
     * keyed on the epoch.
     */
    @Test
    fun anIdenticalEpochIsNotRewritten() = runTest {
        store(25544, epochMillis = 7_000L, sourceId = "celestrak-stations")

        store(25544, epochMillis = 7_000L, sourceId = "amsat-nasabare")

        assertEquals("celestrak-stations", dao.find(25544)?.sourceId)
    }

    @Test
    fun reportsTheOldestEpochBecauseThatIsWhatStalenessMeans() = runTest {
        store(1, epochMillis = 9_000L, sourceId = "a")
        store(2, epochMillis = 3_000L, sourceId = "b")
        store(3, epochMillis = 6_000L, sourceId = "c")

        assertEquals(3_000L, dao.observeOldestEpochMillis().first())
    }

    @Test
    fun reportsNoEpochWhenNothingIsStored() = runTest {
        assertNull(dao.observeOldestEpochMillis().first())
    }

    @Test
    fun bulkUpsertWritesUnconditionallyForImportsAndTheBundledSnapshot() = runTest {
        store(25544, epochMillis = 9_000L, sourceId = "celestrak-stations")

        dao.upsert(
            listOf(
                TleEntity(
                    noradId = 25544,
                    name = "ISS",
                    line1 = "1 imported",
                    line2 = "2 imported",
                    epochMillis = 1_000L,
                    fetchedAtMillis = 2_000L,
                    sourceId = "user-import",
                ),
            ),
        )

        assertEquals(
            "user-import",
            dao.find(25544)?.sourceId,
            "an explicit import is the user's decision and must not lose to the epoch rule",
        )
    }

    @Test
    fun findsManyAtOnce() = runTest {
        store(1, 1_000L, "a")
        store(2, 2_000L, "b")
        store(3, 3_000L, "c")

        assertEquals(setOf(1, 3), dao.findAll(listOf(1, 3, 99)).map { it.noradId }.toSet())
    }
}
