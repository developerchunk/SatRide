package com.developerstring.satride.feature.satellites

import androidx.compose.runtime.Immutable
import com.developerstring.satride.core.data.RefreshState
import com.developerstring.satride.core.data.TleFreshness
import com.developerstring.satride.core.model.SatClass
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One catalog row.
 *
 * ## What is deliberately not here
 *
 * Live elevation. It used to be, rounded to the displayed degree, and that was the right design
 * while the catalog was a few hundred objects: the state was deduplicated by `==` before it
 * reached Compose, so a row whose fields had not changed was never recomposed.
 *
 * It stops being the right design at ten thousand rows. Even when nothing recomposes, *building*
 * the list costs an allocation per row per tick, and the comparison that proves nothing changed
 * costs a walk over every row — once a second, on a background thread that is also running SGP4.
 *
 * So a row now carries only what changes at human speed: a name, a class, whether the satellite is
 * up at all, when it next rises. The degree itself is read straight from `LiveSky` by the row
 * composable, through a `derivedStateOf` that only fires when the integer changes — so the cost of
 * live numbers is proportional to the dozen rows on screen rather than to the size of the catalog.
 */
@Immutable
data class SatelliteRowUi(
    val noradId: Int,
    val name: String,
    val classType: SatClass,
    /**
     * Above the horizon as of the last ordering pass.
     *
     * Coarse on purpose — a boolean changes a few times a minute where a degree changes every
     * second, and this is what the sort and the chip both actually need.
     */
    val isUp: Boolean = false,
    /**
     * Launch year, from the international designator.
     *
     * Shown instead of azimuth. A bearing is only actionable while physically pointing something,
     * which is the sky view's job, and a second number ticking once a second in every row is noise
     * in a list people scan. The launch year does not change and says something about the object.
     */
    val launchYear: Int? = null,
    val nextPassIn: Duration? = null,
    val nextPassMaxElevationDeg: Double? = null,
    /** Has a receivable downlink — the amber dot that bridges to the radio half of the app. */
    val hasDownlink: Boolean = false,
    val sunlit: Boolean = false,
    /** In the user's tracking selection, so the star in the row is filled. */
    val tracked: Boolean = false,
)

/** What the catalog is drawing from, and how much of it is being shown. */
@Immutable
data class CatalogScopeUi(
    /** Rows in the state, after the display cap. */
    val shown: Int = 0,
    /** Rows the current segment, query and filters actually match. */
    val matched: Int = 0,
) {
    /** True when the cap is hiding results, which the footer has to say out loud. */
    val truncated: Boolean get() = matched > shown
}

@Immutable
data class SatellitesUiState(
    val segment: CatalogSegment = CatalogSegment.default,
    val query: String = "",
    val filters: SatelliteFilters = SatelliteFilters.NONE,
    val rows: List<SatelliteRowUi> = emptyList(),
    val scope: CatalogScopeUi = CatalogScopeUi(),
    val freshness: TleFreshness = TleFreshness.Empty,
    val filterSheetOpen: Boolean = false,
    val loading: Boolean = true,
    /** Live refresh state, driving both the pull indicator and the progress bar. */
    val refresh: RefreshState = RefreshState.Idle,
    /** Set while the observer position is unknown, so "nothing is up" can be explained honestly. */
    val observerUnknown: Boolean = false,
    /** How many objects the user is tracking, for the header and the manage-tracking button. */
    val trackedCount: Int = 0,
    /** How many objects are in the catalog at all. */
    val catalogCount: Int = 0,
) {
    val isEmpty: Boolean get() = !loading && rows.isEmpty()

    val refreshing: Boolean get() = refresh.isRunning
}

sealed interface SatellitesEvent {
    data class SegmentSelected(val segment: CatalogSegment) : SatellitesEvent
    data class QueryChanged(val query: String) : SatellitesEvent
    data class FiltersChanged(val filters: SatelliteFilters) : SatellitesEvent
    data object FilterSheetOpened : SatellitesEvent
    data object FilterSheetDismissed : SatellitesEvent
    data object FiltersCleared : SatellitesEvent
    data class RowClicked(val noradId: Int) : SatellitesEvent

    /** The star on a row: adds or removes one satellite from the tracking selection. */
    data class TrackToggled(val noradId: Int) : SatellitesEvent
    data object ImportRequested : SatellitesEvent
    data object ManageTrackingRequested : SatellitesEvent
    data object RefreshRequested : SatellitesEvent
}

sealed interface SatellitesEffect {
    data class OpenDetail(val noradId: Int) : SatellitesEffect
    data object OpenImport : SatellitesEffect
    data object OpenManageTracking : SatellitesEffect

    /**
     * Says how a refresh went.
     *
     * A pull-to-refresh that silently stops spinning leaves the user unsure whether anything
     * happened — especially when the answer is "nothing, because you are offline".
     */
    data class ShowMessage(val text: String) : SatellitesEffect
}

/** Formats a countdown the way a person reads one; null when there is no pass to count to. */
internal fun Duration?.asCountdown(): String? {
    if (this == null || isNegative()) return null
    val totalMinutes = inWholeMinutes
    return when {
        totalMinutes < 1 -> "now"
        totalMinutes < 60 -> "${totalMinutes}m"
        totalMinutes < 60 * 24 -> "${inWholeHours}h ${totalMinutes % 60}m"
        else -> "${inWholeDays}d"
    }
}

internal fun Instant.timeUntil(now: Instant): Duration = this - now
