package com.developerstring.satride.feature.satellites.tracking

import androidx.compose.runtime.Immutable
import com.developerstring.satride.core.data.RefreshState
import com.developerstring.satride.core.datastore.TrackingBudget
import com.developerstring.satride.core.datastore.TrackingMode
import com.developerstring.satride.core.model.SatClass

/**
 * One selectable satellite.
 *
 * Flat and tiny, because the list this feeds is the only screen in the app that addresses the
 * whole catalog: a page of these is built on every keystroke and on every checkbox tap, so a row
 * that had to look anything up would make typing feel heavy at exactly the moment the user is
 * trying to find one satellite among thirty thousand.
 */
@Immutable
data class TrackingRowUi(
    val noradId: Int,
    val name: String,
    val classType: SatClass,
    val tracked: Boolean,
    /** In the bundled starter set — worth marking, because it is the safe thing to keep. */
    val curated: Boolean,
    val launchYear: Int? = null,
)

/** A class chip with its share of the catalog. */
@Immutable
data class ClassChipUi(val classType: SatClass, val total: Int, val selected: Boolean)

@Immutable
data class TrackingUiState(
    val mode: TrackingMode = TrackingMode.CURATED,
    val budget: TrackingBudget = TrackingBudget.default,
    val query: String = "",
    val classChips: List<ClassChipUi> = emptyList(),
    val trackedOnly: Boolean = false,
    val withDownlinkOnly: Boolean = false,
    val rows: List<TrackingRowUi> = emptyList(),
    /** How many rows match the current filter, before paging. */
    val matching: Int = 0,
    /** How many objects the user has ticked. */
    val selected: Int = 0,
    /** How many objects exist locally. */
    val catalog: Int = 0,
    /** What the engine is actually keeping live, and what that costs per tick. */
    val live: Int = 0,
    val refreshedPerTick: Int = 0,
    val upNow: Int = 0,
    /** True while element sets are still being loaded for a newly enlarged selection. */
    val arming: Boolean = false,
    val armedFraction: Float = 1f,
    val loading: Boolean = true,
    /** Set while a bulk selection is being written, so the buttons can be disabled honestly. */
    val working: Boolean = false,
    val refresh: RefreshState = RefreshState.Idle,
    /** Whether the app has been told to download every catalog group rather than the five defaults. */
    val fullCatalogDownloaded: Boolean = false,

) {
    val hasMore: Boolean get() = rows.size < matching

    /**
     * Whether the current selection is large enough that the budget matters.
     *
     * The threshold is the smallest budget's live tier: below it, every selected satellite is
     * propagated every tick and there is nothing to explain.
     */
    val budgetMatters: Boolean get() = live > TrackingBudget.BATTERY.liveObjects
}

sealed interface TrackingEvent {
    data class QueryChanged(val query: String) : TrackingEvent

    data class ClassToggled(val classType: SatClass) : TrackingEvent
    data object TrackedOnlyToggled : TrackingEvent
    data object WithDownlinkOnlyToggled : TrackingEvent
    data class ModeSelected(val mode: TrackingMode) : TrackingEvent
    data class BudgetSelected(val budget: TrackingBudget) : TrackingEvent
    data class RowToggled(val noradId: Int) : TrackingEvent

    /** Selects or deselects everything matching the current search and filters. */
    data class MatchingToggled(val tracked: Boolean) : TrackingEvent

    /** "Show all possible satellites": everything in the catalog, and fetch the rest. */
    data object TrackEverything : TrackingEvent
    data object SelectStarterSet : TrackingEvent
    data object SelectWithDownlink : TrackingEvent
    data object ClearSelection : TrackingEvent

    /** Pulls every catalog group, so "everything" means everything rather than what we happen to have. */
    data object DownloadFullCatalog : TrackingEvent
    data object LoadMore : TrackingEvent
}

sealed interface TrackingEffect {
    data class ShowMessage(val text: String) : TrackingEffect
}
