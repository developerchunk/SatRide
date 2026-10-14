package com.developerstring.satride.feature.arsky

import com.developerstring.satride.core.designsystem.component.SatelliteGlyphKind
import com.developerstring.satride.core.permissions.PermissionStatus
import com.developerstring.satride.core.sensors.CompassAccuracy
import kotlin.time.Duration

/**
 * One satellite, as the sky view needs it.
 *
 * Deliberately holds *nothing that changes every frame*. Live azimuth and elevation are read
 * straight from the tracking engine by the render loop, because pushing them through a
 * `StateFlow` at thirty hertz would recompose the whole screen thirty times a second to move a
 * dot. Everything here changes at human speed, or not at all.
 */
data class SkySatelliteUi(
    val noradId: Int,
    val name: String,
    val glyph: SatelliteGlyphKind,
    /** Has a receivable downlink — earns the amber halo that links the sky to the radio. */
    val hasDownlink: Boolean = false,
    val followed: Boolean = false,
    /** Lit by the sun, so potentially visible to the naked eye. */
    val sunlit: Boolean = false,
    /** ±5 minutes of arc, in degrees, sampled for drawing. Null while it is being computed. */
    val trail: SkyTrail? = null,
    /** Set only for a followed satellite that is below the horizon, to count it in. */
    val risesIn: Duration? = null,
) {
    /**
     * Rank for label decluttering, higher wins.
     *
     * Encodes what a user is actually looking for: the thing they asked to be guided to, then
     * anything they can tune a radio to, then anything at all. Elevation breaks ties inside a
     * band, applied by the renderer where it knows the live value.
     */
    val labelPriority: Int
        get() = when {
            followed -> 3000
            hasDownlink -> 2000
            else -> 1000
        }
}

/**
 * A sampled orbit arc, as parallel arrays.
 *
 * Arrays rather than a list of points because the renderer walks this every frame and a list of
 * objects would mean an iterator plus a pointer chase per sample. Both arrays are the same
 * length; index *i* is one moment on the arc.
 */
class SkyTrail(val azimuthDeg: FloatArray, val elevationDeg: FloatArray) {
    val size: Int get() = azimuthDeg.size

    /** Index of the sample nearest to now, so the trail can be faded away from the present. */
    var nowIndex: Int = size / 2
        internal set
}

/**
 * Where the attitude driving the sky comes from.
 *
 * This is *only* about attitude. Whether the camera feed is drawn behind the sky is a separate
 * switch — see [SkyUiState.cameraOn] — because "point the phone at the sky" and "show me what the
 * lens sees" are genuinely different wants. Holding the phone up and seeing satellites over a dark
 * sky is a real, useful mode: it works indoors, on a simulator, with the camera denied, and it is
 * the one to record a demo in.
 */
enum class SkyMode {
    /** Real device orientation. The camera feed behind it is optional. */
    AUGMENTED,

    /**
     * A dark sky and a virtual camera the user drags.
     *
     * Not a degraded mode: it is the fallback when permissions are missing, the only usable mode
     * indoors and on a simulator, and the one the demo video is recorded in.
     */
    PLANETARIUM,
}

data class SkyUiState(
    val satellites: List<SkySatelliteUi> = emptyList(),
    val mode: SkyMode = SkyMode.PLANETARIUM,
    /**
     * Whether the camera feed is actually being drawn behind the sky.
     *
     * True only when all three hold: the sky is in [SkyMode.AUGMENTED], the user has the camera
     * switched on, and permission has been granted. The UI reads this one flag rather than
     * re-deriving that conjunction in three places and getting it subtly different in each.
     */
    val cameraOn: Boolean = false,
    val cameraPermission: PermissionStatus = PermissionStatus.NOT_REQUESTED,
    val locationPermission: PermissionStatus = PermissionStatus.NOT_REQUESTED,
    val accuracy: CompassAccuracy = CompassAccuracy.UNCALIBRATED,
    val attitudeAvailable: Boolean = true,
    /** False until a location is known; nothing can be drawn without one. */
    val observerKnown: Boolean = false,
    /** The satellite the guide arrow and haptics are leading the user to. */
    val guideTargetId: Int? = null,
    /** The satellite whose detail sheet is open. */
    val selectedId: Int? = null,
    val calibrationCardOpen: Boolean = false,
    val timeTravel: TimeTravelUi = TimeTravelUi.DISABLED,
) {
    val isEmpty: Boolean get() = satellites.isEmpty()

    /** True when the camera could be shown but has not been offered yet. */
    val canOfferCamera: Boolean get() = cameraPermission.canRequest

    /** True when the camera switch is meaningful — it only applies to the augmented view. */
    val cameraSwitchAvailable: Boolean get() = mode == SkyMode.AUGMENTED

    val guideTarget: SkySatelliteUi? get() = satellites.firstOrNull { it.noradId == guideTargetId }

    val selected: SkySatelliteUi? get() = satellites.firstOrNull { it.noradId == selectedId }
}

/**
 * Time travel, P5.7 — behind a flag and the third cut line.
 *
 * The flag ships either way, so the state is always present and simply reports itself disabled.
 * That keeps the renderer free of conditionals about whether the feature exists.
 */
data class TimeTravelUi(
    val enabled: Boolean,
    /** How far from now the sky is being drawn. Zero is live. */
    val offset: Duration = Duration.ZERO,
    val scrubbing: Boolean = false,
) {
    val isLive: Boolean get() = offset == Duration.ZERO

    companion object {
        val DISABLED: TimeTravelUi = TimeTravelUi(enabled = false)
        val LIVE: TimeTravelUi = TimeTravelUi(enabled = true)
    }
}

sealed interface SkyEvent {
    /** Switched between the drawn sky and the augmented one. Nothing to do with the camera. */
    data object ModeToggled : SkyEvent

    /** The camera feed behind the augmented sky was switched on or off. */
    data object CameraToggled : SkyEvent
    data class SatelliteTapped(val noradId: Int) : SkyEvent
    data object SelectionDismissed : SkyEvent
    data class GuideRequested(val noradId: Int) : SkyEvent
    data object GuideCleared : SkyEvent
    data object CalibrationOpened : SkyEvent
    data object CalibrationDismissed : SkyEvent
    data object SearchRequested : SkyEvent
    data class TimeScrubbed(val offset: Duration) : SkyEvent
    data object TimeScrubEnded : SkyEvent
    data object TimeReset : SkyEvent
}

sealed interface SkyEffect {
    data object OpenSearch : SkyEffect
    data class ShowMessage(val text: String) : SkyEffect

    /**
     * The user asked for the camera and the app does not have it yet.
     *
     * Raised rather than handled in the view model because the permission launcher is a UI object:
     * only the composition can present the system dialog. Before this existed, tapping the camera
     * toggle without permission showed a message explaining that the camera was off and offered no
     * way to turn it on — a control that describes its own failure instead of fixing it.
     */
    data object RequestCameraPermission : SkyEffect
}
