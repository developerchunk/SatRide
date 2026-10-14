package com.developerstring.satride.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.developerstring.satride.core.model.GeoPos
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Typed access to the user's settings.
 *
 * Every read goes through [settings], which never fails: a corrupt or unreadable preferences file
 * yields defaults rather than an exception. Settings are not worth crashing over, and a user whose
 * app will not start because a preference file was truncated has no way to recover.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<UserSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it.toUserSettings() }

    suspend fun current(): UserSettings = settings.first()

    suspend fun setOnboardingComplete(complete: Boolean) = edit {
        it[Keys.ONBOARDING_COMPLETE] = complete
    }

    suspend fun setPermissionsPrimed(primed: Boolean) = edit {
        it[Keys.PERMISSIONS_PRIMED] = primed
    }

    suspend fun setWalkthroughComplete(complete: Boolean) = edit {
        it[Keys.WALKTHROUGH_COMPLETE] = complete
    }

    suspend fun setObserverMode(mode: ObserverMode) = edit {
        it[Keys.OBSERVER_MODE] = mode.name
    }

    /**
     * Stores a manually entered observer position, and switches to manual mode.
     *
     * The two go together deliberately: setting a position without selecting the mode leaves a
     * value that is stored but silently ignored, which reads as a bug from the user's side.
     */
    suspend fun setManualPosition(position: GeoPos) = edit {
        it[Keys.OBSERVER_MODE] = ObserverMode.MANUAL.name
        it[Keys.MANUAL_LAT] = position.latDeg
        it[Keys.MANUAL_LON] = position.lonDeg
        it[Keys.MANUAL_ALT] = position.altM
    }

    suspend fun setUnits(units: UnitSystem) = edit { it[Keys.UNITS] = units.name }

    suspend fun setNightMode(enabled: Boolean) = edit { it[Keys.NIGHT_MODE] = enabled }

    suspend fun setAutoRefreshTles(enabled: Boolean) = edit { it[Keys.AUTO_REFRESH] = enabled }

    suspend fun setTleRefreshInterval(interval: Duration) = edit {
        it[Keys.REFRESH_INTERVAL_MS] = interval.inWholeMilliseconds
    }

    suspend fun setMinimumPassElevation(degrees: Double) = edit {
        it[Keys.MIN_PASS_ELEVATION] = degrees.coerceIn(0.0, 90.0)
    }

    /**
     * Chooses which satellites are live.
     *
     * Switching to [TrackingMode.ALL] also turns [UserSettings.downloadFullCatalog] on, because
     * the two are one intention. "Track everything" that quietly meant "everything among the nine
     * hundred objects in the six default groups" would be a lie told by omission — and the user
     * who asks for the whole sky has, by asking, accepted the download that makes it real.
     */
    suspend fun setTrackingMode(mode: TrackingMode) = edit {
        it[Keys.TRACKING_MODE] = mode.name
        if (mode == TrackingMode.ALL) it[Keys.FULL_CATALOG] = true
    }

    suspend fun setTrackingBudget(budget: TrackingBudget) = edit {
        it[Keys.TRACKING_BUDGET] = budget.name
    }

    suspend fun setDownloadFullCatalog(enabled: Boolean) = edit { it[Keys.FULL_CATALOG] = enabled }

    /** Counts one more hand-pasted import. */
    suspend fun recordCustomTleImport() = edit {
        it[Keys.CUSTOM_TLE_IMPORTS] = (it[Keys.CUSTOM_TLE_IMPORTS] ?: 0) + 1
    }

    suspend fun setGainMode(mode: GainMode) = edit { it[Keys.GAIN_MODE] = mode.name }

    suspend fun setManualGainTenthsDb(tenths: Int) = edit { it[Keys.MANUAL_GAIN] = tenths }

    suspend fun setFrequencyCorrectionPpm(ppm: Int) = edit { it[Keys.PPM] = ppm }

    suspend fun setBiasTeeEnabled(enabled: Boolean) = edit { it[Keys.BIAS_TEE] = enabled }

    /**
     * Records an `rtl_tcp` endpoint as most recently used.
     *
     * Re-selecting an existing entry moves it to the front rather than adding a duplicate, and the
     * list is capped so it stays a shortlist instead of a log.
     */
    suspend fun rememberRtlTcpEndpoint(endpoint: String) {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return
        edit { preferences ->
            val existing = preferences[Keys.RTL_TCP_HISTORY].orEmpty().split(HISTORY_SEPARATOR)
                .filter { it.isNotBlank() }
            val updated = (listOf(trimmed) + existing.filterNot { it == trimmed })
                .take(UserSettings.MAX_RTL_TCP_HISTORY)
            preferences[Keys.RTL_TCP_HISTORY] = updated.joinToString(HISTORY_SEPARATOR)
        }
    }

    suspend fun clearRtlTcpHistory() = edit { it.remove(Keys.RTL_TCP_HISTORY) }

    // --- Multi-Play ---

    suspend fun setMultiplayGuestName(name: String) = edit { it[Keys.MP_GUEST_NAME] = name }

    suspend fun setMultiplaySkin(skinName: String) = edit { it[Keys.MP_SKIN] = skinName }

    suspend fun setMultiplayColor(argb: Long) = edit { it[Keys.MP_COLOR] = argb }

    suspend fun setMultiplayMotionCamera(enabled: Boolean) = edit { it[Keys.MP_MOTION_CAMERA] = enabled }

    suspend fun setMultiplayTiltSteer(enabled: Boolean) = edit { it[Keys.MP_TILT_STEER] = enabled }

    /** Records a finished game: the best score per mode only ever rises, and the count goes up. */
    suspend fun recordMultiplayResult(mode: String, score: Int) = edit { preferences ->
        val best = parseScores(preferences[Keys.MP_BEST]).toMutableMap()
        if (score > (best[mode] ?: -1)) best[mode] = score
        preferences[Keys.MP_BEST] = best.entries.joinToString(HISTORY_SEPARATOR) { "${it.key}=${it.value}" }
        preferences[Keys.MP_GAMES] = (preferences[Keys.MP_GAMES] ?: 0) + 1
    }

    /** Remembers that a mode's how-to-play card has been shown. */
    suspend fun recordHowToPlaySeen(modeId: String) = edit { preferences ->
        val current = parseSet(preferences[Keys.MP_HOW_TO_PLAY]).toMutableSet()
        current += modeId
        preferences[Keys.MP_HOW_TO_PLAY] = current.joinToString(HISTORY_SEPARATOR)
    }

    suspend fun unlockMultiplayAchievements(ids: Collection<String>) = edit { preferences ->
        val current = parseSet(preferences[Keys.MP_ACHIEVEMENTS]).toMutableSet()
        current.addAll(ids)
        preferences[Keys.MP_ACHIEVEMENTS] = current.joinToString(HISTORY_SEPARATOR)
    }

    private fun parseScores(raw: String?): Map<String, Int> = raw
        ?.split(HISTORY_SEPARATOR)
        ?.mapNotNull { entry ->
            val parts = entry.split('=')
            val value = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            parts[0] to value
        }
        ?.toMap()
        .orEmpty()

    private fun parseSet(raw: String?): Set<String> =
        raw?.split(HISTORY_SEPARATOR)?.filter { it.isNotBlank() }?.toSet().orEmpty()

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun Preferences.toUserSettings(): UserSettings {
        val defaults = UserSettings()
        val latitude = this[Keys.MANUAL_LAT]
        val longitude = this[Keys.MANUAL_LON]
        return UserSettings(
            onboardingComplete = this[Keys.ONBOARDING_COMPLETE] ?: defaults.onboardingComplete,
            permissionsPrimed = this[Keys.PERMISSIONS_PRIMED] ?: defaults.permissionsPrimed,
            walkthroughComplete = this[Keys.WALKTHROUGH_COMPLETE] ?: defaults.walkthroughComplete,
            observerMode = this[Keys.OBSERVER_MODE]?.let { name ->
                ObserverMode.entries.firstOrNull { it.name == name }
            } ?: defaults.observerMode,
            // Both coordinates or neither: a half-written position is not a position.
            manualPosition = if (latitude != null && longitude != null) {
                GeoPos(latitude, longitude, this[Keys.MANUAL_ALT] ?: 0.0)
            } else {
                null
            },
            units = this[Keys.UNITS]?.let { name -> UnitSystem.entries.firstOrNull { it.name == name } }
                ?: defaults.units,
            nightMode = this[Keys.NIGHT_MODE] ?: defaults.nightMode,
            tleRefreshInterval = this[Keys.REFRESH_INTERVAL_MS]?.milliseconds ?: defaults.tleRefreshInterval,
            autoRefreshTles = this[Keys.AUTO_REFRESH] ?: defaults.autoRefreshTles,
            minimumPassElevationDeg = this[Keys.MIN_PASS_ELEVATION] ?: defaults.minimumPassElevationDeg,
            trackingMode = this[Keys.TRACKING_MODE]?.let { name ->
                TrackingMode.entries.firstOrNull { it.name == name }
            } ?: defaults.trackingMode,
            trackingBudget = this[Keys.TRACKING_BUDGET]?.let { name ->
                TrackingBudget.entries.firstOrNull { it.name == name }
            } ?: defaults.trackingBudget,
            downloadFullCatalog = this[Keys.FULL_CATALOG] ?: defaults.downloadFullCatalog,
            customTleImports = this[Keys.CUSTOM_TLE_IMPORTS] ?: defaults.customTleImports,
            gainMode = this[Keys.GAIN_MODE]?.let { name -> GainMode.entries.firstOrNull { it.name == name } }
                ?: defaults.gainMode,
            manualGainTenthsDb = this[Keys.MANUAL_GAIN] ?: defaults.manualGainTenthsDb,
            frequencyCorrectionPpm = this[Keys.PPM] ?: defaults.frequencyCorrectionPpm,
            biasTeeEnabled = this[Keys.BIAS_TEE] ?: defaults.biasTeeEnabled,
            rtlTcpHistory = this[Keys.RTL_TCP_HISTORY]
                ?.split(HISTORY_SEPARATOR)
                ?.filter { it.isNotBlank() }
                .orEmpty(),
            multiplayGuestName = this[Keys.MP_GUEST_NAME],
            multiplaySkin = this[Keys.MP_SKIN],
            multiplayColor = this[Keys.MP_COLOR] ?: defaults.multiplayColor,
            multiplayMotionCamera = this[Keys.MP_MOTION_CAMERA] ?: defaults.multiplayMotionCamera,
            multiplayTiltSteer = this[Keys.MP_TILT_STEER] ?: defaults.multiplayTiltSteer,
            multiplayBestScores = parseScores(this[Keys.MP_BEST]),
            multiplayAchievements = parseSet(this[Keys.MP_ACHIEVEMENTS]),
            multiplayHowToPlaySeen = parseSet(this[Keys.MP_HOW_TO_PLAY]),
            multiplayGamesPlayed = this[Keys.MP_GAMES] ?: defaults.multiplayGamesPlayed,
        )
    }

    private object Keys {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val PERMISSIONS_PRIMED = booleanPreferencesKey("permissions_primed")
        val WALKTHROUGH_COMPLETE = booleanPreferencesKey("walkthrough_complete")
        val OBSERVER_MODE = stringPreferencesKey("observer_mode")
        val MANUAL_LAT = doublePreferencesKey("manual_latitude")
        val MANUAL_LON = doublePreferencesKey("manual_longitude")
        val MANUAL_ALT = doublePreferencesKey("manual_altitude")
        val UNITS = stringPreferencesKey("units")
        val NIGHT_MODE = booleanPreferencesKey("night_mode")
        val REFRESH_INTERVAL_MS = longPreferencesKey("tle_refresh_interval_ms")
        val AUTO_REFRESH = booleanPreferencesKey("auto_refresh_tles")
        val MIN_PASS_ELEVATION = doublePreferencesKey("min_pass_elevation_deg")
        val CUSTOM_TLE_IMPORTS = intPreferencesKey("custom_tle_imports")
        val TRACKING_MODE = stringPreferencesKey("tracking_mode")
        val TRACKING_BUDGET = stringPreferencesKey("tracking_budget")
        val FULL_CATALOG = booleanPreferencesKey("download_full_catalog")
        val GAIN_MODE = stringPreferencesKey("gain_mode")
        val MANUAL_GAIN = intPreferencesKey("manual_gain_tenths_db")
        val PPM = intPreferencesKey("frequency_correction_ppm")
        val BIAS_TEE = booleanPreferencesKey("bias_tee_enabled")
        val RTL_TCP_HISTORY = stringPreferencesKey("rtl_tcp_history")
        val MP_GUEST_NAME = stringPreferencesKey("mp_guest_name")
        val MP_SKIN = stringPreferencesKey("mp_skin")
        val MP_COLOR = longPreferencesKey("mp_color")
        val MP_MOTION_CAMERA = booleanPreferencesKey("mp_motion_camera")
        val MP_TILT_STEER = booleanPreferencesKey("mp_tilt_steer")
        val MP_BEST = stringPreferencesKey("mp_best_scores")
        val MP_ACHIEVEMENTS = stringPreferencesKey("mp_achievements")
        val MP_HOW_TO_PLAY = stringPreferencesKey("mp_how_to_play_seen")
        val MP_GAMES = intPreferencesKey("mp_games_played")
    }

    private companion object {
        // A newline cannot appear in a host:port, so it cannot corrupt the encoding.
        const val HISTORY_SEPARATOR = "\n"
    }
}
