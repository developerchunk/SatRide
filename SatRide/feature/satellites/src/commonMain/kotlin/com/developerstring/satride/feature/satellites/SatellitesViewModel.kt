package com.developerstring.satride.feature.satellites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.data.CatalogRefresher
import com.developerstring.satride.core.data.LiveSky
import com.developerstring.satride.core.data.PassRepository
import com.developerstring.satride.core.data.RefreshState
import com.developerstring.satride.core.data.SatelliteRepository
import com.developerstring.satride.core.data.SatelliteTracker
import com.developerstring.satride.core.data.TleRepository
import com.developerstring.satride.core.data.TrackingSelectionRepository
import com.developerstring.satride.core.data.TransmitterRepository
import com.developerstring.satride.core.datastore.TrackingMode
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Satellite
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
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * The catalog.
 *
 * This is the reference implementation of the app's screen pattern: one `StateFlow<UiState>`, one
 * `onEvent`, one effects channel. Later features copy the shape, so it is written to be copied —
 * every stream composed declaratively, nothing kicked off imperatively, and no state that the UI
 * can get out of sync with.
 *
 * ## How this stays cheap when the catalog is thirty thousand objects
 *
 * Three rules, and every one of them was a bug before it was a rule.
 *
 * **Live numbers do not travel through the state.** The row list carries identity and things that
 * change at human speed. The degree of elevation ticking once a second is read from [sky] by the
 * row composable itself, so the cost of live data is proportional to the dozen rows on screen
 * rather than to the size of the catalog. See `SatelliteRowUi`.
 *
 * **The ordering follows the answer, not the clock.** What is up comes first, so the sort order
 * genuinely does depend on live positions — but only through a boolean per satellite, and a
 * satellite takes minutes to cross the sky. So the list is re-filtered and re-sorted when
 * `LiveSky.orderingSignature` changes, which is when something has actually risen or set. A
 * per-second sort of thirty thousand rows becomes a sort every few minutes, and nothing is lost:
 * the live *degree* still updates every second, straight from the table, in the rows on screen.
 *
 * **The list is capped.** [MAX_ROWS] rows reach the UI, and the state says how many matched so the
 * footer can be honest about it. A list of thirty thousand `SatelliteRowUi` is a megabyte of
 * allocation to render a screen that shows twelve of them, and no one scrolls to row nine
 * thousand — they search. The tracking screen, which genuinely has to address every object in the
 * catalog, pages instead.
 *
 * All of it runs on [flowOn] the default dispatcher: `stateIn(viewModelScope, …)` collects on
 * `Dispatchers.Main.immediate`, and every `combine` transform upstream of it runs on the
 * collector. Without the hop the UI thread would stop to re-sort the catalog, invisibly while the
 * screen is idle and precisely when navigation needs those frames when it is not.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SatellitesViewModel(
    private val satelliteRepository: SatelliteRepository,
    transmitterRepository: TransmitterRepository,
    passRepository: PassRepository,
    tleRepository: TleRepository,
    private val trackingEngine: SatelliteTracker,
    private val selectionRepository: TrackingSelectionRepository,
    private val refreshController: CatalogRefresher,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val segment = MutableStateFlow(CatalogSegment.default)
    private val rawQuery = MutableStateFlow("")
    private val filters = MutableStateFlow(SatelliteFilters.NONE)
    private val filterSheetOpen = MutableStateFlow(false)

    private val effectChannel = Channel<SatellitesEffect>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: Flow<SatellitesEffect> = effectChannel.receiveAsFlow()

    /**
     * Live positions, handed to the UI unchanged.
     *
     * The one piece of state that bypasses `state` entirely. Row composables read it through a
     * `derivedStateOf` keyed on their own catalog number, so a tick recomposes only the rows whose
     * displayed integer actually changed — typically none of them, and never more than the handful
     * that are up.
     */
    val sky: StateFlow<LiveSky> = trackingEngine.sky

    /**
     * Debounced so a search does not run a query per keystroke.
     *
     * 300 ms is long enough to skip the intermediate states of ordinary typing and short enough
     * that the list feels like it is responding rather than catching up.
     */
    private val query = rawQuery
        .debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()

    private val radioIndex = transmitterRepository.observeAll()
        .map(RadioIndex::from)
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), RadioIndex.EMPTY)

    /** Next pass per satellite, from the cache the data layer already precomputed at startup. */
    private val nextPasses = passRepository.upcoming(PASS_WINDOW)
        .map { passes -> passes.groupBy { it.noradId }.mapValues { (_, list) -> list.minBy { p -> p.aos } } }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyMap())

    /**
     * The live picture, reduced to the changes the *ordering* actually depends on.
     *
     * The single change that lets the catalog list survive a full catalog. Everything downstream
     * re-filters and re-sorts, and it now does so only when a satellite has risen or set rather
     * than once a second — which over thirty thousand rows is the difference between a sort a
     * minute and thirty thousand sorts an hour, for an identical picture.
     *
     * Deduplicated on a digest rather than sampled on a timer; see `LiveSky.orderingSignature`.
     */
    private val orderingSky = trackingEngine.sky
        .distinctUntilChangedBy { it.orderingSignature() }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), LiveSky.EMPTY)

    /**
     * The active mode, mirrored so a tap can read it synchronously.
     *
     * Read here rather than from `state.value`: the state is a derived projection that does not
     * exist until something collects it, so a tap in the first frames after the screen opens would
     * read a default and switch a mode the user had not chosen.
     */
    private val trackingMode = selectionRepository.selection()
        .map { it.mode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), TrackingMode.CURATED)

    private val selectedIds = selectionRepository.observeSelectedIds()
        .map { it.toSet() }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptySet())

    /**
     * The rows, carrying the segment they came from.
     *
     * The segment travels *with* the list rather than through a parallel stream. Combined
     * separately, the two race: switching segments emits the new label beside the previous
     * segment's rows for a frame, which reads as the list flickering to the wrong contents and
     * back. Pairing them makes that state unrepresentable.
     */
    private val segmented: Flow<Pair<CatalogSegment, List<Satellite>>> =
        combine(segment, query) { segment, query -> segment to query }
            .flatMapLatest { (segment, query) ->
                // Search cuts across segments on purpose. Someone typing a name wants that
                // satellite, not to be told it is filed under a tab they are not looking at.
                val satellites =
                    if (query.isNotBlank()) satelliteRepository.search(query) else segment.source()
                satellites.map { segment to it }
            }

    val state: StateFlow<SatellitesUiState> = combine(
        combine(rawQuery, filters, filterSheetOpen, refreshController.state) { query, filters, sheetOpen, refresh ->
            Controls(query, filters, sheetOpen, refresh)
        },
        segmented,
        combine(orderingSky, selectedIds) { sky, selected -> sky to selected },
        combine(radioIndex, nextPasses) { index, passes -> index to passes },
        combine(
            tleRepository.freshness(),
            trackingEngine.observer,
            selectionRepository.observeSelectedCount(),
            selectionRepository.observeCatalogCount(),
        ) { fresh, observer, tracked, catalog ->
            Environment(fresh, observer.geoPos == null, tracked, catalog)
        },
    ) { controls, (activeSegment, satellites), (sky, selected), (index, passes), environment ->
        val matching = satellites.filter { it.matches(controls.filters, index, sky, selected) }
        val rows = matching
            .map { it.toRow(sky, index, passes, selected) }
            .sortedWith(rowOrder)
            .take(MAX_ROWS)

        SatellitesUiState(
            segment = activeSegment,
            query = controls.query,
            filters = controls.filters,
            rows = rows,
            scope = CatalogScopeUi(shown = rows.size, matched = matching.size),
            freshness = environment.freshness,
            filterSheetOpen = controls.filterSheetOpen,
            loading = false,
            refresh = controls.refresh,
            observerUnknown = environment.observerUnknown,
            trackedCount = environment.trackedCount,
            catalogCount = environment.catalogCount,
        )
    }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), SatellitesUiState())

    fun onEvent(event: SatellitesEvent) {
        when (event) {
            is SatellitesEvent.SegmentSelected -> segment.value = event.segment

            is SatellitesEvent.QueryChanged -> rawQuery.value = event.query

            is SatellitesEvent.FiltersChanged -> filters.value = event.filters

            SatellitesEvent.FilterSheetOpened -> filterSheetOpen.value = true

            SatellitesEvent.FilterSheetDismissed -> filterSheetOpen.value = false

            SatellitesEvent.FiltersCleared -> filters.value = SatelliteFilters.NONE

            is SatellitesEvent.RowClicked -> effectChannel.trySend(SatellitesEffect.OpenDetail(event.noradId))

            is SatellitesEvent.TrackToggled -> onTrackToggled(event.noradId)

            SatellitesEvent.ImportRequested -> effectChannel.trySend(SatellitesEffect.OpenImport)

            SatellitesEvent.ManageTrackingRequested ->
                effectChannel.trySend(SatellitesEffect.OpenManageTracking)

            SatellitesEvent.RefreshRequested -> refresh()
        }
    }

    /**
     * Adds or removes one satellite from the selection.
     *
     * Read from [selectedIds] rather than from `state.value`: the state is a derived projection
     * that does not exist until something collects it, so a tap in the first frames after the
     * screen opens would have read an empty selection and re-added a satellite the user was trying
     * to remove.
     */
    private fun onTrackToggled(noradId: Int) {
        val tracked = noradId in selectedIds.value
        viewModelScope.launch {
            selectionRepository.setTracked(listOf(noradId), tracked = !tracked)
            // Starring something is a statement about what to track, so it has to take effect.
            // Left in starter-set mode the tick would be stored and change nothing, which is
            // indistinguishable from a broken button. Only on the way *in*: unstarring the last
            // satellite should leave an empty selection, not silently jump the user back to the
            // starter set.
            if (!tracked && trackingMode.value == TrackingMode.CURATED) {
                selectionRepository.setMode(TrackingMode.SELECTED)
            }
        }
    }

    /**
     * Asks for a refresh and returns.
     *
     * The work belongs to an application-scoped controller, not to this view model: a refresh
     * started here has to survive the user switching tabs or opening a satellite, and
     * `viewModelScope` would cancel it the moment they navigated away.
     *
     * The outcome is reported by watching the controller's state, so it arrives even for a screen
     * that was not open when the refresh finished.
     */
    private fun refresh() = refreshController.refresh()

    init {
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
                .collect { effectChannel.trySend(SatellitesEffect.ShowMessage(it.message)) }
        }
    }

    private fun CatalogSegment.source(): Flow<List<Satellite>> = when (this) {
        // "Up now" is defined by the tracking engine rather than by a stored flag, because it is a
        // question about this second. It is the only segment whose membership changes minute to
        // minute, which is exactly why it is the default.
        CatalogSegment.UP_NOW -> combine(
            satelliteRepository.observeAll(),
            orderingSky,
        ) { all, sky ->
            all.filter { sky.isUp(it.noradId) }
        }

        // The *effective* selection, not the `tracked` column. In starter-set mode nothing is
        // ticked and in "everything" mode the column is irrelevant — a tab labelled "Tracked" that
        // was empty while the sky was full of satellites would be simply wrong.
        CatalogSegment.TRACKED -> combine(
            satelliteRepository.observeAll(),
            selectionRepository.selection(),
        ) { all, selection ->
            // Binary search against the sorted ids rather than building a `Set<Int>`: the selection
            // can be the whole catalog, and boxing thirty thousand integers into a hash set every
            // time this re-emits is the cost this screen was rebuilt to avoid.
            all.filter { it.noradId in selection }
        }

        CatalogSegment.POPULAR -> satelliteRepository.observeCurated()

        CatalogSegment.WEATHER -> satelliteRepository.observeByClass(SatClass.WEATHER)

        CatalogSegment.CREWED -> satelliteRepository.observeByClass(SatClass.CREWED)

        CatalogSegment.HAM -> combine(
            satelliteRepository.observeWithTransmitters(),
            radioIndex,
        ) { withTransmitters, index ->
            // Amateur and CubeSat objects that SatRide can actually demodulate. A satellite whose
            // only downlink is a mode we cannot decode is not a good first listen.
            withTransmitters.filter {
                it.classType in HAM_CLASSES && it.noradId in index.withListenableDownlink
            }
        }

        CatalogSegment.ALL -> satelliteRepository.observeAll()
    }

    private fun Satellite.matches(
        filters: SatelliteFilters,
        index: RadioIndex,
        sky: LiveSky,
        selected: Set<Int>,
    ): Boolean {
        if (filters.classes.isNotEmpty() && classType !in filters.classes) return false
        if (filters.hideDebris && classType == SatClass.ROCKET_BODY) return false
        if (filters.trackedOnly && noradId !in selected) return false
        if (filters.hasTransmitterOnly && noradId !in index.withDownlink) return false
        if (filters.listenableOnly && noradId !in index.withListenableDownlink) return false
        if (filters.bands.isNotEmpty() && filters.bands.none { noradId in index.byBand[it].orEmpty() }) return false
        if (filters.launchedFrom != null && (launchYear ?: Int.MIN_VALUE) < filters.launchedFrom) return false
        if (filters.launchedTo != null && (launchYear ?: Int.MAX_VALUE) > filters.launchedTo) return false
        if (filters.upNowOnly && !sky.isUp(noradId)) return false
        if (filters.minElevationDeg > 0.0) {
            val elevation = sky.elevationDegOf(noradId)
            if (elevation.isNaN() || elevation < filters.minElevationDeg) return false
        }
        return true
    }

    private fun Satellite.toRow(
        sky: LiveSky,
        index: RadioIndex,
        passes: Map<Int, Pass>,
        selected: Set<Int>,
    ): SatelliteRowUi {
        val nextPass = passes[noradId]
        // Coarse-grained to the *displayed* precision. The countdown is only ever shown in whole
        // minutes, and `isUp` is a boolean rather than a degree — so a row is equal to last pass's
        // row unless something a person could see has changed, and Compose skips it.
        return SatelliteRowUi(
            noradId = noradId,
            name = name,
            classType = classType,
            isUp = sky.isUp(noradId),
            launchYear = launchYear,
            nextPassIn = nextPass?.aos?.timeUntil(clock.now())?.inWholeMinutes?.minutes,
            nextPassMaxElevationDeg = nextPass?.maxElevationDeg,
            hasDownlink = noradId in index.withDownlink,
            sunlit = sky.isSunlit(noradId),
            tracked = noradId in selected,
        )
    }

    private data class Controls(
        val query: String,
        val filters: SatelliteFilters,
        val filterSheetOpen: Boolean,
        val refresh: RefreshState,
    )

    private data class Environment(
        val freshness: com.developerstring.satride.core.data.TleFreshness,
        val observerUnknown: Boolean,
        val trackedCount: Int,
        val catalogCount: Int,
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L

        /**
         * Kept alive briefly after the last collector so a rotation or a tab switch does not tear
         * down and rebuild every upstream query.
         */
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

        /**
         * How many rows reach the UI.
         *
         * The catalog screen is for browsing and searching, not for addressing every object in
         * orbit — the tracking screen does that, with paging. Three hundred is more than anyone
         * scrolls before they reach for the search field, and the footer says how many more
         * matched so the cap is never a silent one.
         */
        const val MAX_ROWS = 300

        val PASS_WINDOW = 2.days

        val HAM_CLASSES = setOf(SatClass.AMATEUR, SatClass.CUBESAT)

        /**
         * What is up comes first; then what is coming soonest; then everything else
         * alphabetically. A catalog sorted by name alone buries the one satellite that is
         * overhead right now somewhere under Z.
         *
         * Ordered by the boolean rather than by the degree, which is what lets the row list stay
         * unchanged between ordering passes — see the class comment.
         */
        val rowOrder: Comparator<SatelliteRowUi> = compareBy<SatelliteRowUi> { !it.isUp }
            .thenBy { it.nextPassIn ?: kotlin.time.Duration.INFINITE }
            .thenBy { it.name.lowercase() }
    }
}
