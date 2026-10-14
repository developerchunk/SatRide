package com.developerstring.satride.core.data

import com.developerstring.satride.core.datastore.ObserverMode
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.sensors.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Where the user is.
 *
 * An interface because there are two genuinely different answers — a position the user typed and
 * one the device reports — and the pass repository should not care which it got.
 */
fun interface ObserverPositionProvider {
    /** Emits `null` while no position is known, so callers can show that state honestly. */
    fun position(): Flow<GeoPos?>
}

/**
 * Joins the user's settings to the device's location sensing.
 *
 * The two halves live in different modules on purpose: `core:sensors` knows how to get a fix but
 * nothing about preferences, and `core:datastore` knows the preference but nothing about
 * hardware. This is the small piece that connects them, and it is the only place that decides
 * which one wins.
 *
 * Manual always wins while manual mode is selected. Someone who typed in a location meant it —
 * they may be planning tonight's session for a dark-sky site an hour's drive away, and silently
 * overriding that with the device's own fix would make the setting look broken.
 */
class SettingsBackedObserverPositionProvider(
    private val locationProvider: LocationProvider,
    settings: SettingsRepository,
    scope: CoroutineScope,
) : ObserverPositionProvider {

    init {
        scope.launch {
            settings.settings
                .map { current ->
                    // Only in manual mode. Keeping the stored coordinates while automatic mode is
                    // selected would let a position the user set months ago outrank the fix from
                    // where they are standing now.
                    if (current.observerMode == ObserverMode.MANUAL) current.manualPosition else null
                }
                .distinctUntilChanged()
                .collect { locationProvider.setManual(it) }
        }
    }

    override fun position(): Flow<GeoPos?> = locationProvider.position.map { it.geoPos }.distinctUntilChanged()
}
