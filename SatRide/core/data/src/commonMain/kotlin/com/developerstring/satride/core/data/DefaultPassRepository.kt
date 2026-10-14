package com.developerstring.satride.core.data

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.Geohash
import com.developerstring.satride.core.database.dao.PassCacheDao
import com.developerstring.satride.core.database.dao.SatelliteDao
import com.developerstring.satride.core.database.toDomain
import com.developerstring.satride.core.database.toEntity
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.orbit.Orbit
import com.developerstring.satride.core.orbit.passes.PassSearchOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Pass prediction, computed locally and cached.
 *
 * Nothing here calls a server: passes come from P1's propagator running on the stored element
 * sets, which is what makes the whole app work on a mountainside with no signal. The cache exists
 * only to avoid recomputing the same answer — a ten-day search across the curated set is a second
 * or two of work, which is fine once and wasteful on every screen open.
 *
 * The cache key is the triple that fully determines the answer: satellite, observer cell, element
 * epoch. Any of the three changing makes a stored result wrong rather than merely old, so it is
 * never reused across a change.
 */
class DefaultPassRepository(
    private val passCacheDao: PassCacheDao,
    private val satelliteDao: SatelliteDao,
    private val tleRepository: TleRepository,
    private val selectionRepository: TrackingSelectionRepository,
    private val observerPositions: ObserverPositionProvider,
    private val settings: SettingsRepository,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
) : PassRepository {

    private val logger = Logger.withTag("PassRepository")

    /**
     * Where the rotation resumes on the next run.
     *
     * Deliberately in memory rather than persisted: a process restart re-predicting the head of
     * the selection is the right behaviour, because that is where the starter set and the
     * satellites the user is most likely to open live.
     */
    private var rotationCursor: Int = 0

    private val _predictedIds = MutableStateFlow<Set<Int>>(emptySet())

    /**
     * Which satellites the cached timeline currently covers.
     *
     * Surfaced so the timeline can say "showing 400 of your 12,000 tracked satellites" instead of
     * quietly presenting a slice as if it were the whole thing.
     */
    val predictedIds: StateFlow<Set<Int>> = _predictedIds.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun upcoming(window: Duration): Flow<List<Pass>> =
        combine(observerPositions.position(), settings.settings) { position, preferences ->
            position to preferences.minimumPassElevationDeg
        }.flatMapLatest { (position, minElevation) ->
            if (position == null) {
                // No position, no passes — and said plainly rather than as an empty list that
                // looks like a quiet sky.
                flowOf(emptyList())
            } else {
                val geohash = Geohash.encode(position.latDeg, position.lonDeg)
                val now = clock.now()
                passCacheDao.observeUpcoming(
                    observerGeohash = geohash,
                    notBeforeMillis = now.toEpochMilliseconds(),
                    notAfterMillis = (now + window).toEpochMilliseconds(),
                    minElevationDeg = minElevation,
                    limit = MAX_TIMELINE_PASSES,
                ).map { rows -> rows.map { it.toDomain() } }
            }
        }

    override suspend fun passesFor(noradId: Int, window: Duration): List<Pass> {
        val position = currentPosition() ?: return emptyList()
        return passesFor(noradId, position, window)
    }

    override suspend fun passesFor(noradId: Int, observer: GeoPos, window: Duration): List<Pass> {
        val tle = tleRepository.tleFor(noradId) ?: return emptyList()
        val geohash = Geohash.encode(observer.latDeg, observer.lonDeg)
        val now = clock.now()

        val cached = withContext(dispatchers.io) {
            passCacheDao.forSatellite(
                noradId = noradId,
                observerGeohash = geohash,
                tleEpochMillis = tle.epoch.toEpochMilliseconds(),
                notBeforeMillis = now.toEpochMilliseconds(),
            )
        }
        // A cached window can be shorter than the one now being asked for, so it is only reused
        // when it actually reaches far enough — otherwise a widened window would silently return
        // the old, narrower answer.
        val cacheReachesFarEnough = cached.isNotEmpty() &&
            cached.maxOf { it.losMillis } >= (now + window - RECOMPUTE_MARGIN).toEpochMilliseconds()
        if (cacheReachesFarEnough) {
            return cached.map { it.toDomain() }
        }

        val minElevation = settings.current().minimumPassElevationDeg
        val passes = withContext(dispatchers.default) {
            runCatching {
                Orbit.satellite(tle).passes(
                    observer = observer,
                    start = now,
                    end = now + window,
                    options = PassSearchOptions(minPeakElevationDeg = minElevation),
                )
            }.onFailure {
                // A decayed or unusable element set is one satellite's problem, not the
                // timeline's — the other satellites still have passes worth showing.
                logger.w(it) { "pass prediction failed for $noradId" }
            }.getOrDefault(emptyList())
        }

        if (passes.isNotEmpty()) {
            withContext(dispatchers.io) {
                passCacheDao.upsert(passes.map { it.toEntity(geohash, tle.epoch, now) })
            }
        }
        return passes
    }

    override suspend fun nextPass(noradId: Int, observer: GeoPos): Pass? =
        passesFor(noradId, observer, DEFAULT_WINDOW).firstOrNull()

    override suspend fun prune() = withContext(dispatchers.io) {
        passCacheDao.prune(clock.now().toEpochMilliseconds())
    }

    /**
     * Precomputes the curated set for the current position.
     *
     * Uses [PRECOMPUTE_WINDOW], not [DEFAULT_WINDOW]. Eagerly computing ten days across the whole
     * curated catalog produces about ten thousand passes — most of which no one will ever look at,
     * and whose stored arcs came to tens of megabytes. Two days covers what the timeline shows on
     * opening; anything further out is computed for one satellite at a time, on demand, when the
     * user actually asks.
     *
     * Sequential rather than concurrent: SGP4 is CPU-bound, so running thirty of them in parallel
     * on a phone competes with the frame the AR view is trying to draw.
     */
    override suspend fun refreshUpcoming(window: Duration): Int =
        refreshUpcoming(window, budgetedLimit(), onProgress = null)

    /**
     * Predicts a bounded, prioritised head of the tracked selection.
     *
     * The fast path, called at startup and whenever the timeline's window widens: it has to finish
     * in the seconds a user will wait, so it covers the starter set and then a rotating slice of
     * the selection. [precomputeSelection] is what eventually covers the rest.
     */
    suspend fun refreshUpcoming(
        window: Duration,
        limit: Int,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Int {
        val position = currentPosition() ?: return 0

        prune()

        val order = predictionOrder(limit)
        if (order.isEmpty()) return 0

        var computed = 0
        order.forEachIndexed { index, noradId ->
            computed += passesFor(noradId, position, window).size
            onProgress?.invoke(index + 1, order.size)
        }
        _predictedIds.value = _predictedIds.value + order
        return computed
    }

    /**
     * Predicts the **whole** tracked selection, a chunk at a time.
     *
     * ## Why this is separate from [refreshUpcoming]
     *
     * Because it is slow and that is unavoidable. Searching two days of passes for one satellite is
     * a few thousand SGP4 evaluations plus a root search — roughly a hundred times the cost of one
     * live position — so a selection of thirty thousand objects is minutes of CPU, not seconds. Run
     * on the startup path it would either block the app or be cancelled halfway; run here, in an
     * application-scoped background job, it simply finishes when it finishes, and the timeline
     * fills in underneath the user as it goes.
     *
     * The work is genuinely one-off: results are cached against the observer cell and the element
     * epoch, so this only runs again when the user moves to a new cell or the catalog is refreshed
     * — about once a day.
     *
     * [onProgress] is called after each satellite so the screen can say how far through it is.
     * Cancellation is cooperative and safe: everything computed so far is already stored.
     */
    override suspend fun precomputeSelection(window: Duration, onProgress: ((done: Int, total: Int) -> Unit)?): Int {
        val position = currentPosition() ?: return 0
        val selection = withContext(dispatchers.io) { orderedSelection() }
        if (selection.isEmpty()) return 0

        prune()

        var computed = 0
        // Accumulated locally and published in batches. Both of these were per-satellite and both
        // were quadratic: `_predictedIds.value + noradId` copies the whole set on every step, and
        // every `onProgress` call reaches a screen that re-derives its timeline. Over sixteen
        // thousand satellites that is a hundred and thirty million element copies and sixteen
        // thousand full re-renders — enough to make the Passes tab look like it had hung.
        val predicted = _predictedIds.value.toMutableSet()

        selection.forEachIndexed { index, noradId ->
            computed += passesFor(noradId, position, window).size
            predicted += noradId

            val done = index + 1
            if (done % PROGRESS_INTERVAL == 0 || done == selection.size) {
                _predictedIds.value = predicted.toSet()
                onProgress?.invoke(done, selection.size)
            }

            // Trimmed as we go rather than at the end. A full-catalog run produces passes faster
            // than anyone will read them, and letting the table reach its natural size before
            // cutting it back would mean briefly holding the hundreds of megabytes this exists to
            // avoid.
            if (index % TRIM_INTERVAL == TRIM_INTERVAL - 1) {
                withContext(dispatchers.io) { passCacheDao.trimTo(MAX_CACHED_PASSES) }
            }

            // Yield between satellites: this runs for minutes and must never be the reason a
            // frame is late.
            yield()
        }

        withContext(dispatchers.io) { passCacheDao.trimTo(MAX_CACHED_PASSES) }
        return computed
    }

    /**
     * Which satellites get predictions this run: the starter set, then a rotating slice of the
     * selection.
     *
     * The starter set leads unconditionally. It is what a new user is looking at, it is small, and
     * pushing it behind a rotation would mean a first launch whose timeline is a random four
     * hundred objects from the middle of the catalog.
     */
    private suspend fun predictionOrder(limit: Int): List<Int> = withContext(dispatchers.io) {
        val curated = satelliteDao.curatedIds()
        val selection = selectionRepository.selection().first().noradIds

        val order = LinkedHashSet<Int>(minOf(limit, curated.size + selection.size))
        curated.forEach { if (order.size < limit) order += it }
        if (order.size >= limit || selection.isEmpty()) return@withContext order.toList()

        val start = rotationCursor % selection.size
        var scanned = 0
        var cursor = start
        while (scanned < selection.size && order.size < limit) {
            order += selection[cursor]
            cursor = if (cursor + 1 >= selection.size) 0 else cursor + 1
            scanned++
        }
        rotationCursor = cursor
        order.toList()
    }

    /** The whole selection, starter set first, for [precomputeSelection]. */
    private suspend fun orderedSelection(): List<Int> {
        val curated = satelliteDao.curatedIds()
        val selection = selectionRepository.selection().first().noradIds
        val order = LinkedHashSet<Int>(curated.size + selection.size)
        order += curated
        selection.forEach { order += it }
        return order.toList()
    }

    /**
     * How many satellites the *fast* path predicts for, scaled to what the user has already told us
     * about how much work they want the app doing.
     */
    private suspend fun budgetedLimit(): Int = when (settings.current().trackingBudget) {
        com.developerstring.satride.core.datastore.TrackingBudget.BATTERY -> 120
        com.developerstring.satride.core.datastore.TrackingBudget.BALANCED -> 400
        com.developerstring.satride.core.datastore.TrackingBudget.PERFORMANCE -> 1_200
    }

    private suspend fun currentPosition(): GeoPos? = observerPositions.position().first()

    companion object {
        /** How far ahead a single satellite is searched when the user opens it. */
        val DEFAULT_WINDOW: Duration = 10.days

        /** How far ahead the precomputed slice of the selection is searched on startup. */
        val PRECOMPUTE_WINDOW: Duration = 2.days

        /**
         * The most passes the cache will hold.
         *
         * Each row carries a sampled arc, so this is roughly forty megabytes — enough for several
         * thousand satellites' worth of the next two days, and bounded whatever the user selects.
         * Anything trimmed is recomputed on demand in under a second if it is ever opened.
         */
        const val MAX_CACHED_PASSES: Int = 20_000

        /**
         * The most passes the timeline will carry at once.
         *
         * A chronological list stops being a useful shape long before this: with the whole catalog
         * tracked there are thousands of passes an hour, and no one reads past the first screen.
         * What makes the timeline useful at that scale is the filters — minimum elevation, has a
         * downlink, followed only — and those now run in SQL, so the cap applies to what survives
         * them rather than to the raw sky.
         */
        const val MAX_TIMELINE_PASSES: Int = 600

        /** How often the cache is trimmed during a long run. */
        private const val TRIM_INTERVAL: Int = 200

        /**
         * How often progress is reported during a long run.
         *
         * Every satellite was far too often: progress reaches a screen, and a screen that reacts
         * to it does real work. Every fiftieth is still several updates a second at the rate these
         * complete, which is faster than a person can read a changing number anyway.
         */
        private const val PROGRESS_INTERVAL: Int = 50

        /**
         * Slack on the "does the cache reach far enough" check.
         *
         * Without it the cache is rejected on every call: the window is measured from *now*, so
         * it advances by a few milliseconds between calls and a stored result computed a moment
         * ago always falls fractionally short.
         */
        private val RECOMPUTE_MARGIN: Duration = 1.days
    }
}
