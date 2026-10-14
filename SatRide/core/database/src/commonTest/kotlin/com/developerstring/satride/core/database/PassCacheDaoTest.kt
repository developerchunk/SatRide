package com.developerstring.satride.core.database

import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.model.TimedTopo
import com.developerstring.satride.core.model.Topocentric
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class PassCacheDaoTest {

    private val db = inMemoryTestDatabase()
    private val dao = db.passCacheDao()

    private val here = "gcpvj"
    private val elsewhere = "u33db"
    private val epoch = Instant.fromEpochMilliseconds(1_000_000L)
    private val now = Instant.fromEpochMilliseconds(2_000_000L)

    @AfterTest
    fun tearDown() = db.close()

    private fun pass(noradId: Int, aosMillis: Long, maxElevationDeg: Double = 45.0): Pass {
        val aos = Instant.fromEpochMilliseconds(aosMillis)
        val los = Instant.fromEpochMilliseconds(aosMillis + 600_000L)
        return Pass(
            noradId = noradId,
            aos = aos,
            tca = Instant.fromEpochMilliseconds(aosMillis + 300_000L),
            los = los,
            maxElevationDeg = maxElevationDeg,
            aosAzimuthDeg = 200.0,
            losAzimuthDeg = 20.0,
            arc = listOf(
                TimedTopo(aos, Topocentric(200.0, 0.0, 2300.0, -6.5)),
                TimedTopo(los, Topocentric(20.0, 0.0, 2300.0, 6.5)),
            ),
        )
    }

    @Test
    fun roundTripsAPassIncludingItsArc() = runTest {
        val original = pass(25544, aosMillis = 3_000_000L)
        dao.upsert(listOf(original.toEntity(here, epoch, now)))

        val restored = dao.forSatellite(25544, here, epoch.toEpochMilliseconds(), now.toEpochMilliseconds())
            .single()
            .toDomain()

        assertEquals(original.aos, restored.aos)
        assertEquals(original.maxElevationDeg, restored.maxElevationDeg)
        assertEquals(original.arc.size, restored.arc.size)
        assertEquals(original.arc.first().topo.rangeRateKmS, restored.arc.first().topo.rangeRateKmS)
        assertEquals(original.quality, restored.quality)
    }

    /**
     * Reusing a pass computed for somewhere else is the failure this key exists to prevent: the
     * app would show times that look plausible and are simply wrong.
     */
    @Test
    fun doesNotReturnAPassComputedForADifferentPlace() = runTest {
        dao.upsert(listOf(pass(25544, 3_000_000L).toEntity(elsewhere, epoch, now)))

        val found = dao.forSatellite(25544, here, epoch.toEpochMilliseconds(), now.toEpochMilliseconds())

        assertTrue(found.isEmpty())
    }

    @Test
    fun doesNotReturnAPassComputedFromSupersededElements() = runTest {
        dao.upsert(listOf(pass(25544, 3_000_000L).toEntity(here, epoch, now)))

        val found = dao.forSatellite(
            noradId = 25544,
            observerGeohash = here,
            tleEpochMillis = epoch.toEpochMilliseconds() + 86_400_000L,
            notBeforeMillis = now.toEpochMilliseconds(),
        )

        assertTrue(found.isEmpty())
    }

    @Test
    fun omitsPassesThatHaveAlreadyFinished() = runTest {
        dao.upsert(
            listOf(
                pass(25544, aosMillis = 100_000L).toEntity(here, epoch, now), // ends at 700_000
                pass(25544, aosMillis = 3_000_000L).toEntity(here, epoch, now),
            ),
        )

        val upcoming = dao.forSatellite(25544, here, epoch.toEpochMilliseconds(), now.toEpochMilliseconds())

        assertEquals(listOf(3_000_000L), upcoming.map { it.aosMillis })
    }

    @Test
    fun observesUpcomingPassesAcrossSatellitesInTimeOrder() = runTest {
        dao.upsert(
            listOf(
                pass(2, aosMillis = 5_000_000L).toEntity(here, epoch, now),
                pass(1, aosMillis = 3_000_000L).toEntity(here, epoch, now),
                pass(3, aosMillis = 9_000_000L).toEntity(here, epoch, now),
            ),
        )

        val upcoming = dao.observeUpcoming(
            observerGeohash = here,
            notBeforeMillis = now.toEpochMilliseconds(),
            notAfterMillis = 6_000_000L,
            minElevationDeg = 0.0,
            limit = 100,
        ).first()

        assertEquals(listOf(1, 2), upcoming.map { it.noradId })
    }

    /**
     * The elevation floor and the row cap are part of the query, not applied afterwards.
     *
     * They have to be. Each row carries a sampled arc that is parsed out of JSON on the way to a
     * domain object, so a row that reaches Kotlin costs something whether or not it is then thrown
     * away — and with the whole catalog tracked the cache holds tens of thousands of them.
     */
    @Test
    fun theTimelineQueryFiltersAndCapsInSql() = runTest {
        dao.upsert(
            listOf(
                pass(1, aosMillis = 1_000_000L, maxElevationDeg = 3.0).toEntity(here, epoch, now),
                pass(2, aosMillis = 2_000_000L, maxElevationDeg = 40.0).toEntity(here, epoch, now),
                pass(3, aosMillis = 3_000_000L, maxElevationDeg = 60.0).toEntity(here, epoch, now),
                pass(4, aosMillis = 4_000_000L, maxElevationDeg = 70.0).toEntity(here, epoch, now),
            ),
        )

        val filtered = dao.observeUpcoming(
            observerGeohash = here,
            notBeforeMillis = now.toEpochMilliseconds(),
            notAfterMillis = 9_000_000L,
            minElevationDeg = 10.0,
            limit = 100,
        ).first()
        assertEquals(listOf(2, 3, 4), filtered.map { it.noradId }, "the low pass must never reach Kotlin")

        val capped = dao.observeUpcoming(
            observerGeohash = here,
            notBeforeMillis = now.toEpochMilliseconds(),
            notAfterMillis = 9_000_000L,
            minElevationDeg = 10.0,
            limit = 2,
        ).first()
        assertEquals(listOf(2, 3), capped.map { it.noradId }, "the cap keeps the soonest passes")
    }

    /**
     * The cache is bounded whatever the user selects.
     *
     * A two-day search over the whole catalog is on the order of a hundred thousand passes, each
     * with an arc — hundreds of megabytes of local storage for a timeline nobody reads past the
     * first screen. Furthest-out goes first: what makes a pass worth keeping is how soon it is.
     */
    @Test
    fun trimmingKeepsTheSoonestPasses() = runTest {
        dao.upsert(
            (1..10).map { pass(it, aosMillis = it * 1_000_000L).toEntity(here, epoch, now) },
        )

        dao.trimTo(4)

        val remaining = dao.observeUpcoming(
            observerGeohash = here,
            notBeforeMillis = 0L,
            notAfterMillis = 99_000_000L,
            minElevationDeg = 0.0,
            limit = 100,
        ).first()
        assertEquals(listOf(1, 2, 3, 4), remaining.map { it.noradId })
    }

    /** A limit above the row count is not a limit. */
    @Test
    fun trimmingBelowTheCurrentSizeDoesNothing() = runTest {
        dao.upsert((1..3).map { pass(it, aosMillis = it * 1_000_000L).toEntity(here, epoch, now) })

        dao.trimTo(50)

        assertEquals(3, dao.count())
    }

    /**
     * Without pruning, every refresh writes a new generation of rows keyed on the new epoch and
     * nothing removes the old one — the table grows for the life of the install.
     */
    @Test
    fun pruningDropsFinishedPasses() = runTest {
        dao.upsert(
            listOf(
                pass(1, aosMillis = 100_000L).toEntity(here, epoch, now),
                pass(2, aosMillis = 9_000_000L).toEntity(here, epoch, now),
            ),
        )

        dao.prune(now.toEpochMilliseconds())

        assertEquals(1, dao.count())
    }

    @Test
    fun pruningDropsPassesComputedFromSupersededElements() = runTest {
        db.tleDao().upsertIfNewer(
            noradId = 1,
            name = "SAT",
            line1 = "1",
            line2 = "2",
            epochMillis = epoch.toEpochMilliseconds() + 86_400_000L,
            fetchedAtMillis = now.toEpochMilliseconds(),
            sourceId = "celestrak-stations",
        )
        dao.upsert(listOf(pass(1, aosMillis = 9_000_000L).toEntity(here, epoch, now)))

        dao.prune(now.toEpochMilliseconds())

        assertEquals(0, dao.count(), "a pass from a superseded element set is wrong, not merely old")
    }
}
