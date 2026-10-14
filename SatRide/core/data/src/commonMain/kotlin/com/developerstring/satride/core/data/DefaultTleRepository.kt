package com.developerstring.satride.core.data

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.AppError
import com.developerstring.satride.core.database.dao.SatelliteDao
import com.developerstring.satride.core.database.dao.TleDao
import com.developerstring.satride.core.database.entity.SatelliteEntity
import com.developerstring.satride.core.database.toDomain
import com.developerstring.satride.core.database.toEntity
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.network.GpGroup
import com.developerstring.satride.core.network.TleSource
import com.developerstring.satride.core.network.api.NotModified
import com.developerstring.satride.core.network.api.TleApi
import com.developerstring.satride.core.orbit.sgp4.TleParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Fetches element sets from every configured source and keeps the freshest of each.
 *
 * The multi-source approach is Look4Sat's, and it earns its complexity: CelesTrak is the most
 * complete catalog but ARISS refreshes the ISS more eagerly, AMSAT is often ahead for amateur
 * satellites, and any one of these volunteer-run servers can be down on the evening a user wants
 * to catch a pass.
 */
class DefaultTleRepository(
    private val tleApi: TleApi,
    private val tleDao: TleDao,
    private val satelliteDao: SatelliteDao,
    private val settings: SettingsRepository,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
) : TleRepository {

    private val logger = Logger.withTag("TleRepository")

    override suspend fun tleFor(noradId: Int): Tle? = withContext(dispatchers.io) {
        tleDao.find(noradId)?.toDomain()
    }

    override suspend fun tlesFor(noradIds: List<Int>): Map<Int, Tle> = withContext(dispatchers.io) {
        tleDao.findAll(noradIds).associate { it.noradId to it.toDomain() }
    }

    override fun freshness(): Flow<TleFreshness> = tleDao.observeLastFetchMillis().map { lastFetch ->
        if (lastFetch == null) {
            TleFreshness.Empty
        } else {
            TleFreshness.of(clock.now() - Instant.fromEpochMilliseconds(lastFetch))
        }
    }

    override suspend fun elementAgeOf(noradId: Int): Duration? = withContext(dispatchers.io) {
        tleDao.find(noradId)?.let { clock.now() - Instant.fromEpochMilliseconds(it.epochMillis) }
    }

    /**
     * The whole refresh policy: check on app foreground, fetch if it has been long enough.
     *
     * Measured from the last *fetch*, not from the element epoch. Some satellites are simply not
     * updated often upstream, and keying off their epoch would mean re-downloading the entire
     * catalog on every launch to chase data that has not changed.
     *
     * No scheduler, no background worker. A satellite tracker is opened deliberately, so
     * refreshing when the user actually arrives is both sufficient and impossible to get wrong on
     * a platform that has changed its background-execution rules four times.
     */
    override suspend fun refreshIfStale(groups: Set<GpGroup>): RefreshResult? {
        val preferences = settings.current()
        if (!preferences.autoRefreshTles) return null

        val lastFetch = withContext(dispatchers.io) { tleDao.lastFetchMillis() }
            ?: return refresh(groups)
        val sinceLastFetch = clock.now() - Instant.fromEpochMilliseconds(lastFetch)

        return if (sinceLastFetch >= preferences.tleRefreshInterval) refresh(groups) else null
    }

    /**
     * Fetches every source, then merges.
     *
     * Sources are fetched one at a time rather than concurrently. These are volunteer-run servers
     * and seven simultaneous requests from every install would be a burst of load for no benefit
     * the user can perceive — a background refresh has no deadline.
     */
    override suspend fun refresh(groups: Set<GpGroup>, onProgress: (RefreshProgress) -> Unit): RefreshResult =
        withContext(dispatchers.io) {
            val fetchedAt = clock.now()
            val outcomes = mutableListOf<RefreshResult.SourceOutcome>()
            val bySource = mutableListOf<Pair<TleSource, List<Tle>>>()

            val sources = TleSource.defaults(groups)
            // One step per source, plus the transmitter catalog and the merge, so the bar reaches the
            // end when the work does rather than sitting at 90% while the largest download finishes.
            val totalSteps = sources.size + RefreshProgress.STEPS_AFTER_SOURCES

            // Fetched concurrently, a few at a time. Eleven sequential round trips is most of the
            // wait on a mobile connection, and this is a refresh someone is watching. The cap keeps it
            // polite: these are volunteer-run servers, and four in flight is what an ordinary browser
            // opens to one host, not a burst.
            // A plain counter would be raced by four concurrent fetches, and a progress bar that
            // skips or repeats a step undermines the point of showing a real one. StateFlow's update
            // is atomic and needs no opt-in, unlike the experimental atomics.
            val completed = MutableStateFlow(0)
            val limit = Semaphore(MAX_CONCURRENT_SOURCES)

            val fetched = coroutineScope {
                sources.map { source ->
                    async {
                        val outcome = limit.withPermit { fetch(source) }
                        onProgress(
                            RefreshProgress(completed.updateAndGet { it + 1 }, totalSteps, source.label),
                        )
                        source to outcome
                    }
                }.awaitAll()
            }

            fetched.forEach { (source, result) ->
                when (result) {
                    is FetchOutcome.Success -> {
                        bySource += source to result.tles
                        outcomes += RefreshResult.SourceOutcome(source.id, source.label, result.tles.size)
                    }

                    // Not a failure and not worth a warning in the log: this is the normal answer
                    // for most of the day, because CelesTrak updates every two hours and we ask
                    // more often than that.
                    is FetchOutcome.UpToDate -> {
                        logger.i { "${source.label} is already current" }
                        outcomes += RefreshResult.SourceOutcome(
                            sourceId = source.id,
                            label = source.label,
                            fetched = 0,
                            upToDate = true,
                        )
                    }

                    is FetchOutcome.Failure -> {
                        logger.w { "${source.label} failed: ${result.error.message}" }
                        outcomes += RefreshResult.SourceOutcome(source.id, source.label, 0, result.error)
                    }
                }
            }

            onProgress(RefreshProgress(sources.size, totalSteps, "Merging element sets"))

            // Only when everything genuinely failed. The fallback is a quarter-megabyte of element
            // sets we usually already have, so fetching it speculatively would waste the user's
            // data — and "every source said we are already current" is not a failure, it is the
            // ordinary answer between CelesTrak's two-hourly updates.
            if (bySource.isEmpty() && outcomes.none { it.upToDate }) {
                logger.w { "every primary source failed; trying ${TleSource.fallback.label}" }
                when (val result = fetch(TleSource.fallback)) {
                    is FetchOutcome.Success -> {
                        bySource += TleSource.fallback to result.tles
                        outcomes += RefreshResult.SourceOutcome(
                            TleSource.fallback.id,
                            TleSource.fallback.label,
                            result.tles.size,
                        )
                    }

                    // SatNOGS does not speak CelesTrak's not-modified protocol, so this branch is
                    // unreachable in practice — recorded rather than ignored so that stops being
                    // true loudly rather than silently if it ever changes.
                    FetchOutcome.UpToDate -> outcomes += RefreshResult.SourceOutcome(
                        sourceId = TleSource.fallback.id,
                        label = TleSource.fallback.label,
                        fetched = 0,
                        upToDate = true,
                    )

                    is FetchOutcome.Failure -> outcomes += RefreshResult.SourceOutcome(
                        TleSource.fallback.id,
                        TleSource.fallback.label,
                        0,
                        result.error,
                    )
                }
            }

            val merged = merge(bySource)
            if (merged.isNotEmpty()) {
                tleDao.upsertAllIfNewer(merged.map { it.tle.toEntity(fetchedAt, it.sourceId) })
                satelliteDao.upsertFromCatalog(merged.map { it.toSatelliteEntity() })
            }

            RefreshResult(
                sources = outcomes,
                tlesUpdated = merged.size,
                satellitesSeen = merged.size,
                transmittersUpdated = 0,
            )
        }

    /**
     * Resolves the same satellite appearing in several sources.
     *
     * **Newest epoch wins, regardless of source.** Source priority only breaks a tie between
     * identical epochs, where the two are the same element set anyway and the choice is cosmetic.
     * Ordering it the other way — priority first — would let a stale CelesTrak group overwrite a
     * fresher ARISS ISS set every single refresh, which is precisely the bug this rule prevents.
     */
    private fun merge(bySource: List<Pair<TleSource, List<Tle>>>): List<MergedTle> {
        val best = mutableMapOf<Int, MergedTle>()
        bySource.forEach { (source, tles) ->
            tles.forEach { tle ->
                val candidate = MergedTle(tle, source.id, source.priority, source.group)
                val existing = best[tle.noradId]
                if (existing == null || candidate.beats(existing)) {
                    best[tle.noradId] = candidate
                }
            }
        }
        return best.values.toList()
    }

    private suspend fun fetch(source: TleSource): FetchOutcome = try {
        FetchOutcome.Success(tleApi.fetch(source))
    } catch (upToDate: NotModified) {
        FetchOutcome.UpToDate
    } catch (error: AppError) {
        FetchOutcome.Failure(error)
    }

    private companion object {
        /**
         * How many catalogs are fetched at once.
         *
         * Enough to hide most of the latency of eleven round trips, few enough to stay a
         * well-behaved client of servers that are run by volunteers and paid for out of pocket.
         * Four is roughly what a browser opens to a single host — busy, not a burst.
         */
        const val MAX_CONCURRENT_SOURCES = 4
    }

    private sealed interface FetchOutcome {
        data class Success(val tles: List<Tle>) : FetchOutcome

        /** The source says we already hold its current data. A success with nothing to merge. */
        data object UpToDate : FetchOutcome

        data class Failure(val error: AppError) : FetchOutcome
    }

    private data class MergedTle(val tle: Tle, val sourceId: String, val priority: Int, val group: GpGroup?) {
        fun beats(other: MergedTle): Boolean = when {
            tle.epoch > other.tle.epoch -> true
            tle.epoch < other.tle.epoch -> false
            else -> priority < other.priority
        }

        fun toSatelliteEntity(): SatelliteEntity = SatelliteEntity(
            noradId = tle.noradId,
            name = tle.name.ifBlank { "Object ${tle.noradId}" },
            classType = SatelliteClassifier.classify(tle.name, group).name,
            // Read straight back out of the element set, which is the only place the public
            // catalogs say anything about when a satellite was launched.
            internationalDesignator = runCatching {
                TleParser.parse(tle).internationalDesignator.ifBlank { null }
            }.getOrNull(),
        )
    }
}
