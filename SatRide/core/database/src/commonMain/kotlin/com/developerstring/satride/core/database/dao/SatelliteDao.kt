package com.developerstring.satride.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.developerstring.satride.core.database.entity.SatelliteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SatelliteDao {

    @Upsert
    suspend fun upsert(satellites: List<SatelliteEntity>)

    /**
     * Writes what a catalog refresh knows, leaving alone what it does not.
     *
     * A plain upsert would replace the whole row, which resets `curated` to false — quietly
     * emptying the offline starter list on the first successful refresh, exactly when everything
     * looks like it is working.
     */
    @Query(
        """
        INSERT INTO satellites (noradId, name, classType, launchDate, countryCode, internationalDesignator, curated, tracked)
        VALUES (:noradId, :name, :classType, NULL, NULL, :internationalDesignator, 0, 0)
        ON CONFLICT(noradId) DO UPDATE SET
            name = excluded.name,
            classType = excluded.classType,
            internationalDesignator = COALESCE(excluded.internationalDesignator, satellites.internationalDesignator)
        """,
    )
    suspend fun upsertFromCatalog(noradId: Int, name: String, classType: String, internationalDesignator: String?)

    @Transaction
    suspend fun upsertFromCatalog(satellites: List<SatelliteEntity>) {
        satellites.forEach {
            upsertFromCatalog(it.noradId, it.name, it.classType, it.internationalDesignator)
        }
    }

    @Query("UPDATE satellites SET curated = 1 WHERE noradId IN (:noradIds)")
    suspend fun markCurated(noradIds: List<Int>)

    /**
     * The starter set, ordered so crewed and weather satellites lead.
     *
     * The ordering is deliberate rather than alphabetical: the first screen a new user sees
     * should open on the ISS and the NOAA birds, which are what they came for.
     */
    @Query(
        """
        SELECT * FROM satellites WHERE curated = 1
        ORDER BY CASE classType
            WHEN 'CREWED' THEN 0
            WHEN 'WEATHER' THEN 1
            WHEN 'AMATEUR' THEN 2
            WHEN 'CUBESAT' THEN 3
            ELSE 4 END,
        name COLLATE NOCASE
        """,
    )
    fun observeCurated(): Flow<List<SatelliteEntity>>

    @Query("SELECT * FROM satellites ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SatelliteEntity>>

    /**
     * Name or catalog-number search.
     *
     * Matched anywhere in the name, not just as a prefix: users look for "NOAA 19" as often as
     * "19", and they type the catalog number when they know it.
     */
    @Query(
        """
        SELECT * FROM satellites
        WHERE name LIKE '%' || :query || '%' COLLATE NOCASE
           OR CAST(noradId AS TEXT) LIKE :query || '%'
        ORDER BY
            CASE WHEN name LIKE :query || '%' COLLATE NOCASE THEN 0 ELSE 1 END,
            name COLLATE NOCASE
        LIMIT 200
        """,
    )
    fun search(query: String): Flow<List<SatelliteEntity>>

    @Query("SELECT * FROM satellites WHERE classType = :classType ORDER BY name COLLATE NOCASE")
    fun observeByClass(classType: String): Flow<List<SatelliteEntity>>

    /**
     * Satellites with at least one known downlink — the only ones the radio half of the app can
     * do anything with, and the default filter on the catalog screen.
     */
    @Query(
        """
        SELECT s.* FROM satellites s
        WHERE EXISTS (SELECT 1 FROM transmitters t WHERE t.noradId = s.noradId)
        ORDER BY s.name COLLATE NOCASE
        """,
    )
    fun observeWithTransmitters(): Flow<List<SatelliteEntity>>

    @Query("SELECT * FROM satellites WHERE noradId = :noradId")
    suspend fun find(noradId: Int): SatelliteEntity?

    @Query("SELECT * FROM satellites WHERE noradId = :noradId")
    fun observe(noradId: Int): Flow<SatelliteEntity?>

    @Query("SELECT COUNT(*) FROM satellites")
    suspend fun count(): Int

    // --- Tracking selection -------------------------------------------------------------------
    //
    // The engine's read path is `observeTrackedIds`, which returns catalog numbers and nothing
    // else. That is the whole point: a sweep over a selection of twenty thousand objects must not
    // materialise twenty thousand `SatelliteEntity` rows a minute to learn twenty thousand
    // integers. Names and classes are looked up separately, by the screens that actually show them.

    @Query("SELECT noradId FROM satellites WHERE tracked = 1")
    fun observeTrackedIds(): Flow<List<Int>>

    @Query("SELECT noradId FROM satellites WHERE tracked = 1")
    suspend fun trackedIds(): List<Int>

    @Query("SELECT noradId FROM satellites WHERE curated = 1")
    suspend fun curatedIds(): List<Int>

    @Query("SELECT noradId FROM satellites")
    suspend fun allIds(): List<Int>

    @Query("SELECT noradId FROM satellites")
    fun observeAllIds(): Flow<List<Int>>

    @Query("SELECT COUNT(*) FROM satellites WHERE tracked = 1")
    fun observeTrackedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM satellites")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM satellites WHERE tracked = 1 ORDER BY name COLLATE NOCASE")
    fun observeTracked(): Flow<List<SatelliteEntity>>

    /**
     * Flips the flag for a batch of catalog numbers.
     *
     * Chunked by the caller — SQLite's default limit is 999 bound parameters, and "track
     * everything visible" over a filtered catalog routinely exceeds that.
     */
    @Query("UPDATE satellites SET tracked = :tracked WHERE noradId IN (:noradIds)")
    suspend fun setTracked(noradIds: List<Int>, tracked: Boolean)

    @Query("UPDATE satellites SET tracked = :tracked")
    suspend fun setTrackedForAll(tracked: Boolean)

    @Query("UPDATE satellites SET tracked = :tracked WHERE classType IN (:classTypes)")
    suspend fun setTrackedForClasses(classTypes: List<String>, tracked: Boolean)

    @Query("UPDATE satellites SET tracked = :tracked WHERE curated = 1")
    suspend fun setTrackedForCurated(tracked: Boolean)

    /**
     * Satellites with a receivable downlink, tracked or otherwise — "everything I could listen to"
     * as a single statement rather than as a set intersection computed in Kotlin over the whole
     * catalog.
     */
    @Query(
        """
        UPDATE satellites SET tracked = :tracked
        WHERE EXISTS (SELECT 1 FROM transmitters t WHERE t.noradId = satellites.noradId)
        """,
    )
    suspend fun setTrackedForTransmitting(tracked: Boolean)

    /**
     * The manage screen's list: name-or-number search, optional class narrowing, optional
     * "selected only", paged.
     *
     * One query with nullable parameters rather than a query per combination. The `:classCount = 0`
     * trick lets an empty class filter mean "no narrowing" without a second statement — Room has no
     * conditional composition, and four boolean options would otherwise be sixteen queries.
     *
     * `LIMIT`/`OFFSET` rather than the whole result set: the full catalog is tens of thousands of
     * rows and the screen shows a few dozen at a time. Paging keeps the Flow's payload — and the
     * work Room does to build it on every change — proportional to what is on screen.
     */
    @Query(
        """
        SELECT * FROM satellites
        WHERE (:query = '' OR name LIKE '%' || :query || '%' COLLATE NOCASE
               OR CAST(noradId AS TEXT) LIKE :query || '%')
          AND (:classCount = 0 OR classType IN (:classTypes))
          AND (:trackedOnly = 0 OR tracked = 1)
          AND (:withDownlinkOnly = 0
               OR EXISTS (SELECT 1 FROM transmitters t WHERE t.noradId = satellites.noradId))
        ORDER BY tracked DESC, curated DESC, name COLLATE NOCASE
        LIMIT :limit OFFSET :offset
        """,
    )
    fun browse(
        query: String,
        classTypes: List<String>,
        classCount: Int,
        trackedOnly: Boolean,
        withDownlinkOnly: Boolean,
        limit: Int,
        offset: Int,
    ): Flow<List<SatelliteEntity>>

    /** How many rows [browse] would return without its limit — the "N results" the header shows. */
    @Query(
        """
        SELECT COUNT(*) FROM satellites
        WHERE (:query = '' OR name LIKE '%' || :query || '%' COLLATE NOCASE
               OR CAST(noradId AS TEXT) LIKE :query || '%')
          AND (:classCount = 0 OR classType IN (:classTypes))
          AND (:trackedOnly = 0 OR tracked = 1)
          AND (:withDownlinkOnly = 0
               OR EXISTS (SELECT 1 FROM transmitters t WHERE t.noradId = satellites.noradId))
        """,
    )
    fun browseCount(
        query: String,
        classTypes: List<String>,
        classCount: Int,
        trackedOnly: Boolean,
        withDownlinkOnly: Boolean,
    ): Flow<Int>

    /** Catalog numbers matching a [browse] filter, for "select everything I am looking at". */
    @Query(
        """
        SELECT noradId FROM satellites
        WHERE (:query = '' OR name LIKE '%' || :query || '%' COLLATE NOCASE
               OR CAST(noradId AS TEXT) LIKE :query || '%')
          AND (:classCount = 0 OR classType IN (:classTypes))
          AND (:trackedOnly = 0 OR tracked = 1)
          AND (:withDownlinkOnly = 0
               OR EXISTS (SELECT 1 FROM transmitters t WHERE t.noradId = satellites.noradId))
        """,
    )
    suspend fun browseIds(
        query: String,
        classTypes: List<String>,
        classCount: Int,
        trackedOnly: Boolean,
        withDownlinkOnly: Boolean,
    ): List<Int>

    /** Population per class, for the counts on the manage screen's class chips. */
    @Query("SELECT classType AS classType, COUNT(*) AS total FROM satellites GROUP BY classType")
    fun observeClassCounts(): Flow<List<ClassCount>>

    @Query("SELECT COUNT(*) FROM satellites WHERE noradId = :noradId AND tracked = 1")
    suspend fun isTracked(noradId: Int): Int
}

/** One row of [SatelliteDao.observeClassCounts]. */
data class ClassCount(val classType: String, val total: Int)
