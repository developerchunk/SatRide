package com.developerstring.satride.feature.settings

import androidx.compose.runtime.Immutable
import com.developerstring.satride.core.datastore.ObserverMode
import com.developerstring.satride.core.datastore.UnitSystem
import com.developerstring.satride.core.model.GeoPos
import kotlin.time.Duration

@Immutable
data class SettingsUiState(
    val loading: Boolean = true,
    val observerMode: ObserverMode = ObserverMode.AUTOMATIC,
    val manualPosition: GeoPos? = null,
    val units: UnitSystem = UnitSystem.METRIC,
    val nightMode: Boolean = false,
    val autoRefreshTles: Boolean = true,
    val tleRefreshInterval: Duration = Duration.ZERO,
    val minimumPassElevationDeg: Double = 10.0,
    /** Open while the user is typing a position by hand. */
    val manualEntryOpen: Boolean = false,
    val manualLatInput: String = "",
    val manualLonInput: String = "",
    val manualEntryError: String? = null,
    val appName: String = "SatRide",
    val appVersion: String = "",
) {
    /** Manual mode with nothing entered yet is a real state: the app has no position at all. */
    val manualPositionMissing: Boolean
        get() = observerMode == ObserverMode.MANUAL && manualPosition == null
}

sealed interface SettingsEvent {
    data class ObserverModeSelected(val mode: ObserverMode) : SettingsEvent
    data object ManualEntryOpened : SettingsEvent
    data object ManualEntryDismissed : SettingsEvent
    data class ManualLatChanged(val text: String) : SettingsEvent
    data class ManualLonChanged(val text: String) : SettingsEvent
    data object ManualEntrySubmitted : SettingsEvent
    data class UnitsSelected(val units: UnitSystem) : SettingsEvent
    data class NightModeToggled(val enabled: Boolean) : SettingsEvent
    data class AutoRefreshToggled(val enabled: Boolean) : SettingsEvent
    data class RefreshIntervalSelected(val interval: Duration) : SettingsEvent
    data class MinimumElevationSelected(val degrees: Double) : SettingsEvent
    data object WalkthroughReplayRequested : SettingsEvent
}

sealed interface SettingsEffect {
    data class ShowMessage(val text: String) : SettingsEffect
}
