package com.developerstring.satride.feature.satellites.tracking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.data.CatalogQuery
import com.developerstring.satride.core.data.CatalogRefresher
import com.developerstring.satride.core.data.RefreshState
import com.developerstring.satride.core.data.SatelliteTracker
import com.developerstring.satride.core.data.TrackingSelectionRepository
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.core.datastore.TrackingBudget
import com.developerstring.satride.core.datastore.TrackingMode
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.network.GpGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Choosing what to track.
 *
 * The screen this drives is the answer to "let me pick from everything", and it is built around
 * the constraint that "everything" is tens of thousands of rows in a local database. Three things
 * follow from that, and each of them is why a piece of this class looks the way it does.
 *
 * **The list is a database page, not a filtered Kotlin list.** [rows] comes from a `LIMIT`/`OFFSET`
 * query, so the amount of work behind a keystroke is proportional to what is on screen rather than
 * to the catalog. Filtering thirty thousand rows in memory on every character typed is
 * comfortably enough to drop frames on any phone.
 *
 * **Bulk actions never round-trip through the list.** "Select everything matching" runs as one
 * `UPDATE` over the ids the same filter selects, not by reading rows into Kotlin and writing them
 * back. That is the difference between an instant action and a visible freeze.
 *
 * **Nothing live reaches this state at 1 Hz.** The engine's status is a `StateFlow` of a data
 * class, so it conflates by content on its own: it emits when a satellite rises, sets or finishes
 * arming, and stays silent through the ticks in between. Throttling it on a timer instead would
 * schedule a repeating delay forever, which — besides being unnecessary — makes every test of this
 * screen hang on a virtual clock that can never reach idle.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TrackingViewModel(
    private val selectionRepository: TrackingSelectionRepository,
    private val tracker: SatelliteTracker,
    private val refreshController: CatalogRefresher,
    private val settings: SettingsRepository,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val rawQuery = MutableStateFlow("")
    private val classes = MutableStateFlow<Set<SatClass>>(emptySet())
    private val trackedOnly = MutableStateFlow(false)
    private val withDownlinkOnly = MutableStateFlow(false)
    private val limit = MutableStateFlow(PAGE_SIZE)
    private val working = MutableStateFlow(false)

    private val effectChannel = Channel<TrackingEffect>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: Flow<TrackingEffect> = effectChannel.receiveAsFlow()

    private val query: StateFlow<CatalogQuery> = combine(
        rawQuery.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }.distinctUntilChanged(),
        classes,
        trackedOnly,
        withDownlinkOnly,
    ) { text, classes, tracked, downlink ->
        CatalogQuery(
            text = text,
            classes = classes,
            trackedOnly = tracked,
            withDownlinkOnly = downlink,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), CatalogQuery())

    /**
     * One page of results, re-queried when the filter or the page size changes.
     *
     * `flatMapLatest` rather than `combine`: a keystroke has to abandon the query the previous
     * keystroke started, or a fast typist queues a query per character and the list arrives in the
     * wrong order.
     */
    private val page = combine(query, limit) { query, limit -> query to limit }
        .flatMapLatest { (query, limit) ->
            selectionRepository.browse(query, limit = limit, offset = 0)
        }
        .map { satellites ->
            satellites.map {
                TrackingRowUi(
                    noradId = it.noradId,
                    name = it.name,
                    classType = it.classType,
                    tracked = it.tracked,
                    curated = it.curated,
                    launchYear = it.launchYear,
                )
            }
        }
        .flowOn(dispatchers.default)

    private val matching = query.flatMapLatest { selectionRepository.countMatching(it) }

    /** The engine's live numbers. Already conflated by content — see the class comment. */
    private val status: StateFlow<com.developerstring.satride.core.data.TrackingStatus> = tracker.status

    val state: StateFlow<TrackingUiState> = combine(
        combine(page, matching, working) { rows, matching, working -> Triple(rows, matching, working) },
        combine(
            selectionRepository.observeSelectedCount(),
            selectionRepository.observeCatalogCount(),
            selectionRepository.observeClassCounts(),
            classes,
        ) { selected, catalog, counts, active ->
            Counts(selected, catalog, chips(counts, active))
        },
        combine(settings.settings, status) { settings, status -> settings to status },
        combine(rawQuery, trackedOnly, withDownlinkOnly) { query, tracked, downlink ->
            Controls(query, tracked, downlink)
        },
        refreshController.state,
    ) { (rows, matchCount, isWorking), counts, (preferences, engine), controls, refresh ->
        TrackingUiState(
            mode = preferences.trackingMode,
            budget = preferences.trackingBudget,
            query = controls.query,
            classChips = counts.chips,
            trackedOnly = controls.trackedOnly,
            withDownlinkOnly = controls.withDownlinkOnly,
            rows = rows,
            matching = matchCount,
            selected = counts.selected,
            catalog = counts.catalog,
            live = engine.selected,
            refreshedPerTick = engine.refreshedPerTick,
            upNow = engine.up,
            arming = engine.preparing,
            armedFraction = engine.armedFraction,
            loading = false,
            working = isWorking,
            refresh = refresh,
            fullCatalogDownloaded = preferences.downloadFullCatalog,
        )
    }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), TrackingUiState())

    init {
        // The download button is on this screen, so its outcome belongs here too — including the
        // one that matters most, which is a catalog answering 403 because it is being asked for
        // too much. Without this the user pressed "download the full catalog", watched the number
        // stop short, and had nothing to explain it.
        viewModelScope.launch {
            // The already-visible outcome is skipped.
            //
            // `RefreshState` is a `StateFlow`, so a `Finished` from a refresh that ended minutes
            // ago is replayed to every new collector — which meant re-announcing the same result
            // every single time this screen was opened. The user sees a refresh reported once, by
            // whichever screen was open when it finished.
            val alreadySeen = refreshController.state.value
            refreshController.state
                .filterIsInstance<RefreshState.Finished>()
                .filter { it !== alreadySeen }
                .distinctUntilChanged()
                .collect { report(it.message) }
        }
    }

    fun onEvent(event: TrackingEvent) {
        when (event) {
            is TrackingEvent.QueryChanged -> {
                rawQuery.value = event.query
                // A new search starts at the top. Keeping the grown page size would make the first
                // keystroke after scrolling deep into the catalog re-query several hundred rows
                // that are about to be replaced anyway.
                limit.value = PAGE_SIZE
            }

            is TrackingEvent.ClassToggled -> {
                classes.value = classes.value.toggle(event.classType)
                limit.value = PAGE_SIZE
            }

            TrackingEvent.TrackedOnlyToggled -> {
                trackedOnly.value = !trackedOnly.value
                limit.value = PAGE_SIZE
            }

            TrackingEvent.WithDownlinkOnlyToggled -> {
                withDownlinkOnly.value = !withDownlinkOnly.value
                limit.value = PAGE_SIZE
            }

            TrackingEvent.LoadMore -> limit.value += PAGE_SIZE

            is TrackingEvent.ModeSelected -> inBackground { selectionRepository.setMode(event.mode) }

            is TrackingEvent.BudgetSelected -> inBackground { selectionRepository.setBudget(event.budget) }

            is TrackingEvent.RowToggled -> onRowToggled(event.noradId)

            is TrackingEvent.MatchingToggled -> onMatchingToggled(event.tracked)

            TrackingEvent.TrackEverything -> onTrackEverything()

            TrackingEvent.SelectStarterSet -> inBackground {
                val count = selectionRepository.selectCurated()
                selectionRepository.setMode(TrackingMode.SELECTED)
                report("Tracking the $count-satellite starter set.")
            }

            TrackingEvent.SelectWithDownlink -> inBackground {
                val count = selectionRepository.selectWithDownlink()
                selectionRepository.setMode(TrackingMode.SELECTED)
                report("Tracking $count satellites with a known downlink.")
            }

            TrackingEvent.ClearSelection -> inBackground {
                selectionRepository.clearSelection()
                report("Selection cleared.")
            }

            TrackingEvent.DownloadFullCatalog -> onDownloadFullCatalog()
        }
    }

    /**
     * Toggling one row.
     *
     * The row's own `tracked` flag is the source of truth rather than a set held here: the list is
     * a database page and the flag came from the same statement that produced the row, so it
     * cannot disagree with what the checkbox is drawing.
     */
    private fun onRowToggled(noradId: Int) {
        val row = state.value.rows.firstOrNull { it.noradId == noradId } ?: return
        viewModelScope.launch {
            selectionRepository.setTracked(listOf(noradId), tracked = !row.tracked)
            // Ticking a satellite is a statement about what to track, so it has to actually take
            // effect. Leaving the mode on "starter set" would store the choice and change nothing,
            // which is indistinguishable from a bug.
            if (!row.tracked && state.value.mode == TrackingMode.CURATED) {
                selectionRepository.setMode(TrackingMode.SELECTED)
            }
        }
    }

    private fun onMatchingToggled(tracked: Boolean) = inBackground {
        val count = selectionRepository.setTrackedForQuery(query.value, tracked)
        if (tracked && state.value.mode == TrackingMode.CURATED) {
            selectionRepository.setMode(TrackingMode.SELECTED)
        }
        report(if (tracked) "Added $count satellites." else "Removed $count satellites.")
    }

    /**
     * "Show all possible satellites."
     *
     * Two things at once, because the user asked for one: switch the engine to the whole catalog,
     * and start fetching the groups we do not have yet. Doing only the first would silently mean
     * "all nine hundred satellites we happen to have downloaded", which is not what the button
     * says.
     */
    private fun onTrackEverything() = inBackground {
        selectionRepository.setMode(TrackingMode.ALL)
        val catalog = state.value.catalog
        if (!settings.current().downloadFullCatalog || catalog < FULL_CATALOG_HINT) {
            settings.setDownloadFullCatalog(true)
            refreshController.refresh(GpGroup.everything)
            report("Tracking everything. Downloading the rest of the catalog now.")
        } else {
            report("Tracking all $catalog objects in the catalog.")
        }
    }

    private fun onDownloadFullCatalog() = inBackground {
        settings.setDownloadFullCatalog(true)
        refreshController.refresh(GpGroup.everything)
        report("Downloading every catalog group. This is a few megabytes and happens once a day.")
    }

    private fun chips(counts: Map<SatClass, Int>, active: Set<SatClass>): List<ClassChipUi> = SatClass.entries
        .map { ClassChipUi(classType = it, total = counts[it] ?: 0, selected = it in active) }
        // A class with nothing in it is noise, but one the user has already selected stays
        // visible — otherwise deselecting it would be impossible after a refresh emptied it.
        .filter { it.total > 0 || it.selected }

    private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

    private fun inBackground(block: suspend () -> Unit) {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            runCatching { block() }
                .onFailure { effectChannel.trySend(TrackingEffect.ShowMessage("That did not work. Try again.")) }
            working.value = false
        }
    }

    private fun report(message: String) {
        effectChannel.trySend(TrackingEffect.ShowMessage(message))
    }

    private data class Counts(val selected: Int, val catalog: Int, val chips: List<ClassChipUi>)

    private data class Controls(val query: String, val trackedOnly: Boolean, val withDownlinkOnly: Boolean)

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

        /**
         * How many rows a page holds.
         *
         * Chosen against how the list is scrolled rather than against how it is rendered: sixty is
         * two or three flings, which is far enough ahead that "load more" never arrives as a
         * visible pause, and small enough that a keystroke's query stays under a millisecond.
         */
        const val PAGE_SIZE = 60

        /**
         * Above this many stored objects, the full catalog has plainly already been fetched.
         *
         * The five default groups come to roughly two thousand objects, so anything well past that
         * means a full sync has happened — which lets "track everything" avoid starting a several
         * megabyte download somebody has already paid for today.
         */
        const val FULL_CATALOG_HINT = 6_000
    }
}
