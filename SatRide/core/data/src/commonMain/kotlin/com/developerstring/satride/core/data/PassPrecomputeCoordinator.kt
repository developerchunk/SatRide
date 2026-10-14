package com.developerstring.satride.core.data

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.Geohash
import com.developerstring.satride.core.common.runCatchingCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * How much of the tracked selection the timeline currently covers.
 *
 * Surfaced because the honest answer changes over the first minutes after somebody selects a large
 * catalog, and a timeline that silently showed a slice of it would read as a broken feature rather
 * than an unfinished one.
 */
data class PassCoverage(
    /** Satellites whose passes have been predicted for the current position. */
    val predicted: Int = 0,
    /** Satellites in the tracked selection. */
    val tracked: Int = 0,
    val running: Boolean = false,
) {
    val complete: Boolean get() = tracked > 0 && predicted >= tracked

    val fraction: Float
        get() = if (tracked <= 0) 1f else (predicted.toFloat() / tracked).coerceIn(0f, 1f)
}

/**
 * Keeps the pass timeline level with the tracked selection and the observer's position.
 *
 * ## What it is for
 *
 * [CatalogSync] precomputes once, at startup — which silently computes nothing on a first launch,
 * because the location permission is granted *after* the splash and `refreshUpcoming` returns 0
 * when no position is known yet. The timeline then stayed empty until the next app start, because
 * the pass cache is keyed by observer geohash and nothing ever filled the new key. This watcher is
 * the missing half: whenever the observer's cache cell changes — first fix, a manually entered
 * position, or actually travelling somewhere — it fills the cache for the new cell and
 * re-reconciles reminders against the recomputed passes.
 *
 * Keyed on the geohash cell rather than the raw fix so a GPS jitter of a few metres does not
 * trigger a full recompute; the cell is exactly the granularity the cache itself uses, so a
 * recompute happens precisely when the cache key changes and not otherwise.
 *
 * ## Why it also watches the selection
 *
 * Because the selection is now the thing that decides what the timeline is *about*. Someone who
 * turns on "track everything" and finds the timeline still showing the bundled starter set has been
 * given a setting that appears to do nothing. So a change to the selection restarts the walk, and
 * [coverage] says how far through it is.
 *
 * The work is one-off in practice: results are cached against the observer cell and the element
 * epoch, so a completed walk stays complete until the user moves or the catalog is refreshed.
 */
class PassPrecomputeCoordinator(
    private val observerPositions: ObserverPositionProvider,
    private val passRepository: PassRepository,
    private val selectionRepository: TrackingSelectionRepository,
    private val alertCoordinator: PassAlertCoordinator,
    private val dispatchers: AppDispatchers,
) {

    private val logger = Logger.withTag("PassPrecompute")

    private val _coverage = MutableStateFlow(PassCoverage())
    val coverage: StateFlow<PassCoverage> = _coverage.asStateFlow()

    /**
     * Runs until cancelled; the app shell launches this once per process.
     *
     * `collectLatest` is what makes a change mid-walk safe: moving somewhere new, or enlarging the
     * selection, abandons the run in progress rather than queueing behind minutes of work that is
     * now predicting for the wrong place.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun watch(): Unit = withContext(dispatchers.io) {
        combine(
            observerPositions.position()
                .filterNotNull()
                .map { Geohash.encode(it.latDeg, it.lonDeg) }
                .distinctUntilChanged(),
            selectionRepository.selection().map { it.size }.distinctUntilChanged(),
        ) { cell, selectionSize -> cell to selectionSize }
            .collectLatest { (cell, selectionSize) ->
                _coverage.value = PassCoverage(predicted = 0, tracked = selectionSize, running = true)

                // The fast head first, so the screen has something on it within a second or two,
                // and reminders are reconciled against real predictions rather than an empty cache.
                val head = runCatchingCancellable {
                    passRepository.refreshUpcoming(DefaultPassRepository.PRECOMPUTE_WINDOW)
                }
                    .onFailure { logger.w(it) { "pass precompute for cell $cell failed" } }
                    .getOrDefault(0)

                runCatchingCancellable { alertCoordinator.reconcile() }
                    .onFailure { logger.w(it) { "reconciling alerts after observer change failed" } }

                logger.i { "observer cell $cell: $head passes ready, walking $selectionSize tracked" }

                // Then the rest of the selection, for as long as it takes.
                val total = runCatchingCancellable {
                    passRepository.precomputeSelection(DefaultPassRepository.PRECOMPUTE_WINDOW) { done, count ->
                        _coverage.value = PassCoverage(predicted = done, tracked = count, running = true)
                    }
                }
                    .onFailure { logger.w(it) { "full pass precompute for cell $cell failed" } }
                    .getOrDefault(0)

                _coverage.value = _coverage.value.copy(running = false)

                // Reconciled again: the walk may have produced a better prediction for a satellite
                // the user already has a reminder on, and a reminder is only as good as the
                // prediction behind it.
                runCatchingCancellable { alertCoordinator.reconcile() }
                    .onFailure { logger.w(it) { "reconciling alerts after the full walk failed" } }

                logger.i { "observer cell $cell: $total passes across the selection" }
            }
    }
}
