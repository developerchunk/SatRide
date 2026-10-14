package com.developerstring.satride.core.database

import com.developerstring.satride.core.database.entity.SatelliteEntity
import com.developerstring.satride.core.database.entity.TransmitterEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SatelliteDaoTest {

    private val db = inMemoryTestDatabase()
    private val dao = db.satelliteDao()

    @AfterTest
    fun tearDown() = db.close()

    private fun satellite(noradId: Int, name: String, classType: String = "OTHER", curated: Boolean = false) =
        SatelliteEntity(noradId = noradId, name = name, classType = classType, curated = curated)

    @Test
    fun roundTripsASatellite() = runTest {
        dao.upsert(listOf(satellite(25544, "ISS (ZARYA)", "CREWED", curated = true)))

        val found = dao.find(25544)

        assertEquals("ISS (ZARYA)", found?.name)
        assertEquals("CREWED", found?.classType)
        assertTrue(found!!.curated)
    }

    @Test
    fun upsertReplacesRatherThanDuplicating() = runTest {
        dao.upsert(listOf(satellite(25544, "ISS")))
        dao.upsert(listOf(satellite(25544, "ISS (ZARYA)")))

        assertEquals(1, dao.count())
        assertEquals("ISS (ZARYA)", dao.find(25544)?.name)
    }

    /**
     * The curated list is what a first-time user sees before any network call succeeds, so its
     * order is part of the product: crewed and weather satellites first, not alphabetical.
     */
    @Test
    fun curatedListLeadsWithCrewedAndWeatherSatellites() = runTest {
        dao.upsert(
            listOf(
                satellite(1, "AAAA Cubesat", "CUBESAT", curated = true),
                satellite(2, "NOAA 19", "WEATHER", curated = true),
                satellite(3, "ISS (ZARYA)", "CREWED", curated = true),
                satellite(4, "ZZZZ Amateur", "AMATEUR", curated = true),
                satellite(5, "Not curated", "CREWED", curated = false),
            ),
        )

        val curated = dao.observeCurated().first()

        assertEquals(listOf(3, 2, 4, 1), curated.map { it.noradId })
    }

    @Test
    fun searchMatchesAnywhereInTheNameButRanksPrefixesFirst() = runTest {
        dao.upsert(
            listOf(
                satellite(1, "METEOR-M2 3"),
                satellite(2, "NOAA 19"),
                satellite(3, "NOAA 18"),
                satellite(4, "COSMOS NOAA CLONE"),
            ),
        )

        val results = dao.search("NOAA").first()

        assertEquals(3, results.size)
        assertEquals("COSMOS NOAA CLONE", results.last().name, "a mid-name match must rank below prefixes")
    }

    @Test
    fun searchIsCaseInsensitive() = runTest {
        dao.upsert(listOf(satellite(1, "METEOR-M2 3")))

        assertEquals(1, dao.search("meteor").first().size)
        assertEquals(1, dao.search("METEOR").first().size)
    }

    @Test
    fun searchFindsACatalogNumber() = runTest {
        dao.upsert(listOf(satellite(25544, "ISS (ZARYA)"), satellite(33591, "NOAA 19")))

        val results = dao.search("25544").first()

        assertEquals(listOf(25544), results.map { it.noradId })
    }

    /** The catalog's default filter: satellites the radio side of the app can actually use. */
    @Test
    fun listsOnlySatellitesThatHaveADownlink() = runTest {
        dao.upsert(listOf(satellite(25544, "ISS"), satellite(99999, "Silent Object")))
        db.transmitterDao().upsert(
            listOf(
                TransmitterEntity(
                    id = "tx-1",
                    noradId = 25544,
                    description = "APRS",
                    downlinkLowHz = 145_825_000,
                    mode = "AFSK",
                    status = "ACTIVE",
                ),
            ),
        )

        val withTransmitters = dao.observeWithTransmitters().first()

        assertEquals(listOf(25544), withTransmitters.map { it.noradId })
    }

    @Test
    fun emitsAgainWhenTheUnderlyingRowsChange() = runTest {
        dao.upsert(listOf(satellite(1, "First", curated = true)))
        assertEquals(1, dao.observeCurated().first().size)

        dao.upsert(listOf(satellite(2, "Second", curated = true)))

        assertEquals(2, dao.observeCurated().first().size)
    }
}
