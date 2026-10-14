package com.developerstring.satride.core.data

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Satellite
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.model.Transmitter
import com.developerstring.satride.core.network.GpGroup
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * The data layer's contract with the rest of the app.
 *
 * Features depend on these interfaces and never on Room, Ktor or DataStore. That is what lets a
 * ViewModel be tested with a fake in a plain coroutine test, and what keeps the storage decisions
 * in this module replaceable.
 */
interface SatelliteRepository {
    /** The bundled starter set: available offline, from the first launch, before any refresh. */
    fun observeCurated(): Flow<List<Satellite>>

    fun observeAll(): Flow<List<Satellite>>

    /** The objects the user has ticked on the tracking screen. */
    fun observeTracked(): Flow<List<Satellite>>

    fun observe(noradId: Int): Flow<Satellite?>

    fun search(query: String): Flow<List<Satellite>>

    fun observeByClass(classType: SatClass): Flow<List<Satellite>>

    /** Satellites with at least one known downlink — the ones the radio half of the app can use. */
    fun observeWithTransmitters(): Flow<List<Satellite>>

    suspend fun find(noradId: Int): Satellite?

    suspend fun count(): Int

    /**
     * Imports element sets the user supplied.
     *
     * Parsed strictly: a human pasting text is far more likely to have introduced a typo than a
     * catalog is to be serving corruption, so here a bad checksum is worth reporting rather than
     * tolerating.
     */
    suspend fun importTle(text: String): Result<Int>
}

interface TleRepository {
    suspend fun tleFor(noradId: Int): Tle?

    suspend fun tlesFor(noradIds: List<Int>): Map<Int, Tle>

    /** How long since the catalog was last refreshed — see [TleFreshness]. */
    fun freshness(): Flow<TleFreshness>

    /**
     * How old one satellite's element set is.
     *
     * Distinct from [freshness], which is about the whole catalog. This is what the detail screen
     * shows, because accuracy for the satellite in front of the user depends on *its* epoch, not
     * on when we last talked to a server.
     */
    suspend fun elementAgeOf(noradId: Int): Duration?

    /**
     * Fetches from every configured source and merges by newest epoch.
     *
     * [onProgress] is called as each source completes. The count is real, not an animation: the
     * source list is known before the first request, so "4 of 11" means four endpoints have
     * actually answered.
     */
    suspend fun refresh(
        groups: Set<GpGroup> = GpGroup.defaults,
        onProgress: (RefreshProgress) -> Unit = {},
    ): RefreshResult

    /** Runs [refresh] only if the data is old enough to warrant it. */
    suspend fun refreshIfStale(groups: Set<GpGroup> = GpGroup.defaults): RefreshResult?
}

interface TransmitterRepository {
    fun observeForSatellite(noradId: Int): Flow<List<Transmitter>>

    /**
     * The whole transmitter catalog.
     *
     * Exposed as one stream rather than as a set of pre-computed indexes because what counts as
     * "a ham satellite" or "UHF" is a product decision the catalog screen owns, not a storage
     * one. It re-emits only when a refresh replaces the table, which is roughly daily.
     */
    fun observeAll(): Flow<List<Transmitter>>

    suspend fun forSatellite(noradId: Int): List<Transmitter>

    suspend fun refresh(): Result<Int>
}

interface PassRepository {
    /** Upcoming passes for the predicted slice of the selection, from the current observer position. */
    fun upcoming(window: Duration): Flow<List<Pass>>

    suspend fun passesFor(noradId: Int, window: Duration): List<Pass>

    suspend fun passesFor(noradId: Int, observer: GeoPos, window: Duration): List<Pass>

    suspend fun nextPass(noradId: Int, observer: GeoPos): Pass?

    /**
     * Precomputes part of the tracked selection out to [window], returning how many passes were found.
     *
     * On the interface because the timeline needs it: [upcoming] reads the cache, and the cache is
     * only filled two days ahead at startup. Opening the seven-day view is asking for work that
     * has not been done yet, and would otherwise show a week that quietly ends after 48 hours.
     */
    suspend fun refreshUpcoming(window: Duration): Int

    /**
     * Predicts the **whole** tracked selection out to [window], reporting progress as it goes.
     *
     * Separate from [refreshUpcoming] because it is slow by nature: a two-day pass search is
     * roughly a hundred times the cost of a live position, so a selection of thirty thousand
     * objects is minutes of background work rather than the seconds a user will wait. Callers run
     * it in an application-scoped job and let the timeline fill in underneath.
     *
     * Defaults to [refreshUpcoming] so a fake used to test something else need not implement it.
     */
    suspend fun precomputeSelection(window: Duration, onProgress: ((done: Int, total: Int) -> Unit)? = null): Int =
        refreshUpcoming(window)

    /** Drops cached passes that have finished or were computed from superseded elements. */
    suspend fun prune()
}
