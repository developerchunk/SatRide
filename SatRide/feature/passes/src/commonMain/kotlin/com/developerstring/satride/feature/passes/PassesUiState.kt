package com.developerstring.satride.feature.passes

import androidx.compose.runtime.Immutable
import com.developerstring.satride.core.designsystem.component.SkyPoint
import com.developerstring.satride.core.model.SatClass
import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * How good a pass is *for this user*, combining geometry with what the satellite transmits.
 *
 * Distinct from the model's `PassQuality`, which knows only elevation. A 70° pass of a rocket body
 * is a spectacular thing to look at and completely useless to a receiver, and a timeline that
 * badges it green would be lying to the half of the audience that came for the radio.
 */
enum class PassGrade {
    /** High, and there is something to hear. */
    GREAT,

    /** Worth going outside for. */
    OK,

    /** Low, or nothing transmitting: watch it, do not tune it. */
    VISUAL_ONLY,
    ;

    val label: String
        get() = when (this) {
            GREAT -> "Great"
            OK -> "OK"
            VISUAL_ONLY -> "Visual"
        }

    companion object {
        /**
         * The heuristic from the design doc: elevation decides, a downlink promotes.
         *
         * Forty degrees is where a pass stops being fought for — below it a handheld antenna is
         * working against the horizon, buildings and its own pattern.
         */
        fun of(maxElevationDeg: Double, hasActiveTransmitter: Boolean): PassGrade = when {
            maxElevationDeg >= 40.0 && hasActiveTransmitter -> GREAT
            maxElevationDeg >= 20.0 -> OK
            else -> VISUAL_ONLY
        }
    }
}

/**
 * One pass in the timeline.
 *
 * Flat and precomputed, like the catalog's row: the list re-renders once a second as countdowns
 * tick, so the composable reads fields and does no work. The polar thumbnail's arc is downsampled
 * here rather than in the draw call, because a cached pass carries forty samples and a 56 dp plot
 * cannot show more than a dozen.
 */
@Immutable
data class PassRowUi(
    val id: String,
    val noradId: Int,
    val name: String,
    val classType: SatClass,
    val aos: Instant,
    val los: Instant,
    val maxElevationDeg: Double,
    val aosAzimuthDeg: Double,
    val losAzimuthDeg: Double,
    val duration: Duration,
    val grade: PassGrade,
    val hasDownlink: Boolean,
    val arc: List<SkyPoint>,
    /** Counts down to AOS; negative while the pass is in progress. */
    val startsIn: Duration,
    val hasAlert: Boolean,
    val isFollowed: Boolean,
) {
    val inProgress: Boolean get() = startsIn.isNegative() || startsIn == Duration.ZERO
}

/** A day's worth of passes, with the header the list shows above them. */
@Immutable
data class PassDayUi(val date: LocalDate, val label: String, val passes: List<PassRowUi>)

/** What the user has narrowed the timeline to. */
data class PassFilters(
    val followedOnly: Boolean = false,
    val minMaxElevationDeg: Double = 0.0,
    val radioCapableOnly: Boolean = false,
) {
    val activeCount: Int
        get() = listOf(followedOnly, minMaxElevationDeg > 0.0, radioCapableOnly).count { it }

    companion object {
        val NONE = PassFilters()

        /** The slider's stops. Ten degrees is where the app's own default minimum sits. */
        val ELEVATION_STOPS = listOf(0.0, 10.0, 20.0, 40.0, 60.0)
    }
}

/**
 * How far ahead the timeline looks.
 *
 * Every window is available to everyone. The longer ones cost real computation — a week across the
 * tracked selection is seconds of propagation, which is why the screen shows a "computing" state
 * while it runs — but that is a reason to be honest about the wait, not a reason to sell it.
 */
enum class PassWindow(val duration: Duration, val label: String) {
    DAY(24.hours, "24 hours"),
    THREE_DAYS(3.days, "3 days"),
    WEEK(7.days, "7 days"),
    ;

    companion object {
        val default = DAY
    }
}

@Immutable
data class PassesUiState(
    val window: PassWindow = PassWindow.default,
    val days: List<PassDayUi> = emptyList(),
    val filters: PassFilters = PassFilters.NONE,
    val filterSheetOpen: Boolean = false,
    val loading: Boolean = true,
    /** Set while the seven-day search is running: it is seconds of propagation, not a query. */
    val computing: Boolean = false,
    /** No observer position — the honest reason for an empty timeline, and a different one. */
    val observerUnknown: Boolean = false,
    /** True when passes exist but the filters hide all of them. */
    val filteredToNothing: Boolean = false,
    val alertsSupported: Boolean = true,
) {
    val totalPasses: Int get() = days.sumOf { it.passes.size }

    val isEmpty: Boolean get() = !loading && totalPasses == 0

    /** The next pass anywhere in the window — the one the empty-ish states point at. */
    val next: PassRowUi? get() = days.firstNotNullOfOrNull { it.passes.firstOrNull() }
}

sealed interface PassesEvent {
    data class WindowSelected(val window: PassWindow) : PassesEvent
    data class FiltersChanged(val filters: PassFilters) : PassesEvent
    data object FiltersCleared : PassesEvent
    data object FilterSheetOpened : PassesEvent
    data object FilterSheetDismissed : PassesEvent
    data class PassClicked(val id: String) : PassesEvent
    data class RemindToggled(val id: String) : PassesEvent
}

sealed interface PassesEffect {
    data class OpenPass(val noradId: Int, val aosEpochMillis: Long) : PassesEffect
    data class ShowMessage(val text: String) : PassesEffect

    /** Ask the platform for notification permission before the first reminder is armed. */
    data object RequestNotificationPermission : PassesEffect
}

/**
 * The identity of a pass in the UI.
 *
 * Satellite plus AOS, which is what makes it navigable: the pass detail route carries the same two
 * numbers, so a deep link or a restored back stack finds the pass again by looking it up rather
 * than by holding a reference to a cache row that may have been pruned in the meantime.
 */
internal fun passId(noradId: Int, aos: Instant): String = "$noradId@${aos.toEpochMilliseconds()}"
