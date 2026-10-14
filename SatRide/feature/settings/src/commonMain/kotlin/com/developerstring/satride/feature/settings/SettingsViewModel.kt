package com.developerstring.satride.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.datastore.ObserverMode
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.core.datastore.UnitSystem
import com.developerstring.satride.core.model.GeoPos
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Settings, which is mostly a thin lens over [SettingsRepository].
 *
 * Everything writes straight through to the store and comes back via its flow, rather than being
 * held locally and saved on exit. A settings screen that batches its writes has to decide what to
 * do when the process dies mid-edit, and the honest answer — lose them — is worse than the
 * alternative for values this small.
 *
 * The two exceptions are the manual-position text fields, which live here as strings: half-typed
 * coordinates are not a position, and writing them through would give the tracker `12.` to work
 * with.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val appName: String,
    private val appVersion: String,
) : ViewModel() {

    private val editor = MutableStateFlow(ManualEditor())

    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED, BufferOverflow.DROP_OLDEST)
    val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<SettingsUiState> = combine(settings.settings, editor) { stored, edit ->
        SettingsUiState(
            loading = false,
            observerMode = stored.observerMode,
            manualPosition = stored.manualPosition,
            units = stored.units,
            nightMode = stored.nightMode,
            autoRefreshTles = stored.autoRefreshTles,
            tleRefreshInterval = stored.tleRefreshInterval,
            minimumPassElevationDeg = stored.minimumPassElevationDeg,
            manualEntryOpen = edit.open,
            manualLatInput = edit.lat,
            manualLonInput = edit.lon,
            manualEntryError = edit.error,
            appName = appName,
            appVersion = appVersion,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.ObserverModeSelected -> viewModelScope.launch {
                // Switching to manual without a stored position would silently leave the app with
                // nowhere to compute from, so it opens the editor in the same move.
                if (event.mode == ObserverMode.MANUAL && settings.current().manualPosition == null) {
                    openEditor()
                } else {
                    settings.setObserverMode(event.mode)
                }
            }

            SettingsEvent.ManualEntryOpened -> viewModelScope.launch { openEditor() }

            SettingsEvent.ManualEntryDismissed -> editor.value = ManualEditor()

            is SettingsEvent.ManualLatChanged ->
                editor.value = editor.value.copy(lat = event.text, error = null)

            is SettingsEvent.ManualLonChanged ->
                editor.value = editor.value.copy(lon = event.text, error = null)

            SettingsEvent.ManualEntrySubmitted -> submitManualPosition()

            is SettingsEvent.UnitsSelected -> viewModelScope.launch { settings.setUnits(event.units) }

            is SettingsEvent.NightModeToggled -> viewModelScope.launch { settings.setNightMode(event.enabled) }

            is SettingsEvent.AutoRefreshToggled -> viewModelScope.launch {
                settings.setAutoRefreshTles(event.enabled)
            }

            is SettingsEvent.RefreshIntervalSelected -> viewModelScope.launch {
                settings.setTleRefreshInterval(event.interval)
            }

            is SettingsEvent.MinimumElevationSelected -> viewModelScope.launch {
                settings.setMinimumPassElevation(event.degrees)
            }

            SettingsEvent.WalkthroughReplayRequested -> viewModelScope.launch {
                settings.setWalkthroughComplete(false)
                effectChannel.trySend(
                    SettingsEffect.ShowMessage("The tour will run again next time you open the app."),
                )
            }
        }
    }

    private suspend fun openEditor() {
        val existing = settings.current().manualPosition
        editor.value = ManualEditor(
            open = true,
            lat = existing?.latDeg?.toString().orEmpty(),
            lon = existing?.lonDeg?.toString().orEmpty(),
        )
    }

    /**
     * Parses and stores a hand-entered position.
     *
     * Ranges are checked, not just the parse: a longitude of 200 parses perfectly well and then
     * puts the observer somewhere that does not exist, which surfaces much later as passes that
     * never happen.
     */
    private fun submitManualPosition() {
        val current = editor.value
        val lat = current.lat.trim().toDoubleOrNull()
        val lon = current.lon.trim().toDoubleOrNull()
        val error = when {
            lat == null || lon == null -> "Enter both a latitude and a longitude."
            lat !in -90.0..90.0 -> "Latitude has to be between -90 and 90."
            lon !in -180.0..180.0 -> "Longitude has to be between -180 and 180."
            else -> null
        }
        if (error != null || lat == null || lon == null) {
            editor.value = current.copy(error = error)
            return
        }
        viewModelScope.launch {
            // `setManualPosition` switches the mode as well as storing the coordinates — a stored
            // position that is not being used is the bug it exists to prevent.
            settings.setManualPosition(GeoPos(latDeg = lat, lonDeg = lon))
            editor.value = ManualEditor()
            effectChannel.trySend(SettingsEffect.ShowMessage("Observer position set."))
        }
    }

    private data class ManualEditor(
        val open: Boolean = false,
        val lat: String = "",
        val lon: String = "",
        val error: String? = null,
    )

    companion object {
        /**
         * The refresh intervals worth offering.
         *
         * A day is the default and the shortest sensible choice: SGP4 error grows roughly 1–3 km
         * per day in low orbit, so daily keeps the AR view well inside its own pointing accuracy,
         * and anything more frequent is load on volunteer-run servers for accuracy nobody can see.
         * Twelve hours exists only for the launch-chasing case.
         */
        val RefreshIntervals: List<Duration> = listOf(12.hours, 1.days, 3.days, 7.days)

        /** Minimum pass elevations. Below about 10° a pass is technically real and practically not. */
        val MinimumElevations: List<Double> = listOf(0.0, 5.0, 10.0, 20.0, 30.0)
    }
}
