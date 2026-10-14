package com.developerstring.satride.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.developerstring.satride.core.database.entity.PassCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PassCacheDao {

    @Upsert
    suspend fun upsert(passes: List<PassCacheEntity>)

    /**
     * Cached passes for one satellite from one place, computed from one element set.
     *
     * All three key parts are required. Matching on fewer would return a pass computed for
     * somewhere the user is not, or from elements that have since been replaced — which looks
     * like a working feature right up until the satellite is not where the app says.
     */
    @Query(
        """
        SELECT * FROM pass_cache
        WHERE noradId = :noradId
          AND observerGeohash = :observerGeohash
          AND tleEpochMillis = :tleEpochMillis
          AND losMillis >= :notBeforeMillis
        ORDER BY aosMillis
        """,
    )
    suspend fun forSatellite(
        noradId: Int,
        observerGeohash: String,
        tleEpochMillis: Long,
        notBeforeMillis: Long,
    ): List<PassCacheEntity>

    /**
     * The timeline's window, filtered and capped in SQL.
     *
     * Both of those used to happen in Kotlin, over everything the cache held. That was fine while
     * the cache held the starter set's next two days; it is not fine once the tracked selection can
     * be the whole catalog, because each row carries a sampled arc that is parsed out of JSON on
     * the way to a domain object. Sixteen thousand satellites is tens of thousands of cached
     * passes and something like a million arc points — rebuilt from scratch every time a write
     * invalidated the query, which during a precompute walk is constantly.
     *
     * So the elevation floor and the limit are part of the statement. The rows that never reach
     * Kotlin are never parsed, and the cost of an emission is bounded by what the screen can show
     * rather than by what the user has selected.
     */
    @Query(
        """
        SELECT * FROM pass_cache
        WHERE observerGeohash = :observerGeohash
          AND losMillis >= :notBeforeMillis
          AND aosMillis <= :notAfterMillis
          AND maxElevationDeg >= :minElevationDeg
        ORDER BY aosMillis
        LIMIT :limit
        """,
    )
    fun observeUpcoming(
        observerGeohash: String,
        notBeforeMillis: Long,
        notAfterMillis: Long,
        minElevationDeg: Double,
        limit: Int,
    ): Flow<List<PassCacheEntity>>

    /**
     * Drops rows that can no longer be used: passes that have finished, and anything computed
     * from an element set that has since been superseded.
     *
     * Without this the table grows on every refresh — each new epoch writes a fresh generation of
     * rows and nothing ever removes the previous one.
     */
    @Query(
        """
        DELETE FROM pass_cache
        WHERE losMillis < :nowMillis
           OR tleEpochMillis < (SELECT COALESCE(MAX(epochMillis), 0) FROM tles WHERE noradId = pass_cache.noradId)
        """,
    )
    suspend fun prune(nowMillis: Long)

    @Query("DELETE FROM pass_cache WHERE noradId = :noradId")
    suspend fun deleteForSatellite(noradId: Int)

    /**
     * Caps the table, dropping the furthest-out passes first.
     *
     * Needed once the tracked selection can be the whole catalog: a two-day search over thirty
     * thousand satellites is on the order of a hundred thousand passes, each carrying a sampled
     * arc, which would run to hundreds of megabytes of local storage for a timeline nobody reads
     * past the first screen.
     *
     * Furthest-out rather than lowest-quality: what makes a pass worth keeping is that the user
     * might act on it, and that is overwhelmingly a function of how soon it is. A pass six days
     * away is recomputed on demand in well under a second if anyone ever opens it.
     */
    @Query(
        """
        DELETE FROM pass_cache WHERE rowid IN (
            SELECT rowid FROM pass_cache ORDER BY aosMillis DESC LIMIT MAX(0, (SELECT COUNT(*) FROM pass_cache) - :maxRows)
        )
        """,
    )
    suspend fun trimTo(maxRows: Int)

    @Query("SELECT COUNT(*) FROM pass_cache")
    suspend fun count(): Int

    @Query("SELECT COUNT(DISTINCT noradId) FROM pass_cache WHERE observerGeohash = :observerGeohash")
    fun observeCoveredSatellites(observerGeohash: String): Flow<Int>
}
