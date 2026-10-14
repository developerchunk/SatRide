package com.developerstring.satride.core.data

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.runCatchingCancellable
import com.developerstring.satride.core.network.GpGroup
import com.developerstring.satride.core.network.TleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * How far through a refresh we are.
 *
 * Genuinely measured, not an animation. The set of endpoints is known before the first request is
 * sent, so a step is a source that has actually answered. A progress bar that invents its own
 * motion is worse than none: it tells the user the app is working when it may be stuck.
 */
data class RefreshProgress(val completed: Int, val total: Int, val label: String) {
    val fraction: Float get() = if (total <= 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)

    companion object {
        /** The merge, then the transmitter catalog. */
        const val STEPS_AFTER_SOURCES: Int = 2
    }
}

sealed interface RefreshState {
    data object Idle : RefreshState

    data class Running(val progress: RefreshProgress) : RefreshState

    /** The last outcome, kept so a screen opened after the fact can still report it. */
    data class Finished(val result: RefreshResult?, val message: String) : RefreshState

    val isRunning: Boolean get() = this is Running
}

/**
 * Starting a refresh and watching it happen.
 *
 * An interface so a screen depends on the capability rather than on the machinery: the real
 * implementation owns an application-lifetime coroutine scope and live network repositories,
 * none of which a test of a progress bar should have to stand up.
 */
interface CatalogRefresher {
    val state: StateFlow<RefreshState>

    /**
     * Starts a refresh unless one is already running. Returns immediately.
     *
     * Passing no groups means "whatever the user's settings imply", which is the only correct
     * default once the full catalog is an option: a pull-to-refresh by somebody tracking thirty
     * thousand objects must not quietly re-fetch the six starter groups and call it done.
     */
    fun refresh(groups: Set<GpGroup>? = null)
}

/**
 * Owns catalog refreshes, for the whole application's lifetime.
 *
 * Deliberately not a view-model concern. A refresh started from the catalog screen has to survive
 * the user switching tabs or opening a satellite — a `viewModelScope` would cancel it the moment
 * they navigated away, so the work would be silently abandoned halfway and the user would come back
 * to a spinner that had achieved nothing.
 *
 * A single refresh runs at a time; a second request while one is in flight is ignored rather than
 * queued, because the answer would be identical.
 */
class CatalogRefreshController(
    private val tleRepository: TleRepository,
    private val transmitterRepository: TransmitterRepository,
    private val settings: com.developerstring.satride.core.datastore.SettingsRepository,
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
) : CatalogRefresher {

    private val logger = Logger.withTag("CatalogRefresh")

    private val _state = MutableStateFlow<RefreshState>(RefreshState.Idle)
    override val state: StateFlow<RefreshState> = _state.asStateFlow()

    private var job: Job? = null

    override fun refresh(groups: Set<GpGroup>?) {
        if (job?.isActive == true) return

        job = scope.launch(dispatchers.io) {
            val resolved = groups ?: CatalogScope.of(settings.current())
            // The real total, known before the first request: a bar that opens at 0/1 and jumps
            // to 11 is a small lie, and the whole point of showing measured progress is not to
            // tell those.
            val total = TleSource.defaults(resolved).size + RefreshProgress.STEPS_AFTER_SOURCES
            _state.value = RefreshState.Running(RefreshProgress(0, total, "Contacting catalogs"))

            val refresh = runCatchingCancellable {
                tleRepository.refresh(resolved) { progress ->
                    _state.value = RefreshState.Running(progress)
                }
            }.onFailure { logger.e(it) { "element-set refresh failed" } }.getOrNull()

            val transmitters = if (refresh != null && !refresh.isCompleteFailure) {
                _state.value = RefreshState.Running(
                    RefreshProgress(total - 1, total, "Transmitter catalog"),
                )
                transmitterRepository.refresh()
                    .onFailure { logger.w(it) { "transmitter refresh failed; keeping the stored catalog" } }
                    .getOrNull() ?: 0
            } else {
                0
            }

            val result = refresh?.copy(transmittersUpdated = transmitters)
            _state.value = RefreshState.Finished(result, result.describe())

            // The pass cache is keyed on element epoch, so a refreshed satellite misses it and
            // recomputes when its passes are next needed. Warming it here would add roughly nine
            // seconds of propagation to a refresh the user is watching, for no visible benefit.
        }
    }

    private fun RefreshResult?.describe(): String = when {
        this == null -> "Could not refresh. Everything still works from stored data."

        isCompleteFailure ->
            "Could not reach any catalog. Check your connection — everything still works from " +
                "stored data."

        // "Already current" is the ordinary answer, not a problem. CelesTrak updates every two
        // hours and the app checks more often than that, so most refreshes legitimately fetch
        // nothing — and saying so plainly is the difference between an app that looks healthy and
        // one that appears to fail all day.
        tlesUpdated == 0 && isFullyCurrent -> "Already up to date."

        isRateLimited ->
            "Updated $tlesUpdated satellites. A catalog asked us to slow down; it will catch up " +
                "on the next refresh."

        isPartial -> "Updated $tlesUpdated satellites. Some sources were unreachable."

        else -> "Updated $tlesUpdated satellites and $transmittersUpdated downlinks."
    }
}

/**
 * Which CelesTrak groups a refresh should pull, given what the user has asked to track.
 *
 * In one place because three callers need the same answer — startup sync, pull-to-refresh and the
 * tracking screen's explicit "download everything" — and a refresh that fetched a different set
 * from the one the selection needs would leave satellites permanently un-armed with no error
 * anywhere to explain it.
 */
object CatalogScope {
    fun of(settings: com.developerstring.satride.core.datastore.UserSettings): Set<GpGroup> = when {
        settings.downloadFullCatalog -> GpGroup.everything

        settings.trackingMode == com.developerstring.satride.core.datastore.TrackingMode.ALL ->
            GpGroup.everything

        else -> GpGroup.defaults
    }
}
