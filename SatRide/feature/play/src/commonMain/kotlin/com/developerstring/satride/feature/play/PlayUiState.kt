package com.developerstring.satride.feature.play

import com.developerstring.satride.core.model.SatClass

/**
 * The Play tab's UI chrome state.
 *
 * Deliberately small: everything that changes per *frame* (camera, projected positions, the
 * simulated clock) lives outside this flow, because pushing 60 Hz data through a StateFlow
 * would recompose the whole screen every frame. This state changes at human speed — taps,
 * chips, once-a-second HUD numbers.
 */
data class PlayUiState(
    val loading: Boolean = true,
    /** True when the catalog produced zero usable element sets, so the globe would be bare. */
    val emptyCatalog: Boolean = false,
    /** Satellites on the globe: the selection, minus any without a usable element set. */
    val satelliteCount: Int = 0,
    /** What the user asked to track, so the difference can be explained rather than hidden. */
    val selectionCount: Int = 0,
    val group: PlayGroup = PlayGroup.ALL,
    val mode: PlayMode = PlayMode.REAL,
    val timeScale: TimeScale = TimeScale.REAL,
    val selected: SelectedSatellite? = null,
    val riding: Boolean = false,
) {
    /** Time gears belong to Play. In Real there is only one speed, and it is the real one. */
    val showTimeControls: Boolean get() = mode == PlayMode.PLAY
}

/**
 * What the globe is showing.
 *
 * The distinction is not cosmetic. In [REAL] the screen is an instrument: the Earth's rotation, the
 * day/night terminator and every satellite are where they actually are, this second, and time
 * cannot be touched. In [PLAY] the same scene becomes a toy — time can run at sixty or six hundred
 * times, orbits visibly sweep, and the terminator crawls across continents.
 *
 * Keeping them as separate modes rather than "speed 1× is real" matters because a user who has
 * fast-forwarded and come back has a clock that is *offset* from now, not merely running at the
 * right rate. Switching to Real re-anchors it, so Real always means now.
 */
enum class PlayMode(val label: String) {
    REAL("Real"),
    PLAY("Play"),
}

/** The followed satellite and its once-a-second HUD numbers. */
data class SelectedSatellite(
    val noradId: Int,
    val name: String,
    val classType: SatClass,
    val altitudeKm: Double = 0.0,
    val speedKmS: Double = 0.0,
    val periodMin: Double = 0.0,
    val latDeg: Double = 0.0,
    val lonDeg: Double = 0.0,
)

/** Constellation filter chips. Coarser than [SatClass]: rocket bodies fold into Other. */
enum class PlayGroup(val label: String) {
    ALL("All"),
    STATIONS("Stations"),
    WEATHER("Weather"),
    AMATEUR("Amateur"),
    NAVIGATION("Navigation"),
    CUBESAT("CubeSats"),
    OTHER("Other"),
    ;

    fun matches(classType: SatClass): Boolean = when (this) {
        ALL -> true
        STATIONS -> classType == SatClass.CREWED
        WEATHER -> classType == SatClass.WEATHER
        AMATEUR -> classType == SatClass.AMATEUR
        NAVIGATION -> classType == SatClass.NAVIGATION
        CUBESAT -> classType == SatClass.CUBESAT
        OTHER -> classType == SatClass.OTHER || classType == SatClass.ROCKET_BODY
    }
}

/** Simulated-time gears, available in [PlayMode.PLAY] only. */
enum class TimeScale(val label: String, val factor: Double) {
    REAL("1×", 1.0),
    FAST("60×", 60.0),
    HYPER("600×", 600.0),
}

sealed interface PlayUiEvent {
    /** Tap selection; a [noradId] of 0 (tap on empty space) clears it. */
    data class Select(val noradId: Int) : PlayUiEvent

    data class SetGroup(val group: PlayGroup) : PlayUiEvent

    data class SetTimeScale(val scale: TimeScale) : PlayUiEvent

    data class SetMode(val mode: PlayMode) : PlayUiEvent

    data object ToggleRide : PlayUiEvent

    data object Retry : PlayUiEvent
}
