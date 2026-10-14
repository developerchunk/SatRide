package com.developerstring.satride.feature.passes

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.developerstring.satride.core.designsystem.component.SatelliteGlyphKind
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.model.SatClass
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Presentation vocabulary for the passes screens.
 *
 * The class → glyph/label/accent mapping is duplicated from `feature:satellites` rather than
 * shared, and that is the architecture's deliberate cost: features may not depend on features, and
 * `core:designsystem` may not know domain types. The alternative — a shared "feature-common" UI
 * module — buys one copy of seven lines and gives every feature a new dependency to reason about.
 * If a third feature needs these, that is the moment to reconsider, not now.
 */

internal val SatClass.label: String
    get() = when (this) {
        SatClass.CREWED -> "Crewed"
        SatClass.WEATHER -> "Weather"
        SatClass.AMATEUR -> "Amateur"
        SatClass.CUBESAT -> "CubeSat"
        SatClass.NAVIGATION -> "Navigation"
        SatClass.ROCKET_BODY -> "Rocket body"
        SatClass.OTHER -> "Object"
    }

@Composable
internal fun SatClass.accent(): Color = when (this) {
    SatClass.CREWED -> SatRideTheme.accents.success
    SatClass.WEATHER -> SatRideTheme.accents.tracking
    SatClass.AMATEUR, SatClass.CUBESAT -> SatRideTheme.accents.radio
    SatClass.NAVIGATION, SatClass.ROCKET_BODY, SatClass.OTHER -> SatRideTheme.accents.trackingDim
}

internal val SatClass.glyph: SatelliteGlyphKind
    get() = when (this) {
        SatClass.CREWED -> SatelliteGlyphKind.CREWED
        SatClass.WEATHER -> SatelliteGlyphKind.WEATHER
        SatClass.AMATEUR -> SatelliteGlyphKind.AMATEUR
        SatClass.CUBESAT -> SatelliteGlyphKind.CUBESAT
        SatClass.NAVIGATION -> SatelliteGlyphKind.NAVIGATION
        SatClass.ROCKET_BODY -> SatelliteGlyphKind.ROCKET_BODY
        SatClass.OTHER -> SatelliteGlyphKind.OTHER
    }

@Composable
internal fun PassGrade.accent(): Color = when (this) {
    PassGrade.GREAT -> SatRideTheme.accents.success
    PassGrade.OK -> SatRideTheme.accents.warning
    PassGrade.VISUAL_ONLY -> SatRideTheme.accents.trackingDim
}

/**
 * Local wall-clock time, "20:47".
 *
 * Twenty-four hour on every locale. A pass timeline is a schedule read in a hurry, and the
 * ambiguity of a missing am/pm on a list of times an hour apart is worse than the unfamiliarity of
 * a 24-hour clock to someone who does not use one.
 */
internal fun Instant.asClockTime(zone: TimeZone): String {
    val local = toLocalDateTime(zone)
    return "${local.hour.padded()}:${local.minute.padded()}"
}

/** How long a pass lasts, in the units a person would say it in: "9m 20s", "48s". */
internal fun Duration.asPassLength(): String {
    val seconds = inWholeSeconds
    if (seconds < 60) return "${seconds}s"
    return "${seconds / 60}m ${(seconds % 60).padded()}s"
}

/**
 * A countdown to AOS.
 *
 * Seconds appear only inside the last hour. A pass three days out that counts down by the second
 * is noise; one four minutes out that does not is missing the only information that matters.
 */
internal fun Duration.asCountdown(): String {
    if (isNegative()) return "now"
    val totalMinutes = inWholeMinutes
    return when {
        totalMinutes < 1 -> "${inWholeSeconds}s"
        totalMinutes < 60 -> "${totalMinutes}m ${(inWholeSeconds % 60).padded()}s"
        totalMinutes < 60 * 24 -> "${inWholeHours}h ${totalMinutes % 60}m"
        else -> "${inWholeDays}d ${inWholeHours % 24}h"
    }
}

/** "Today", "Tomorrow", then "Wed 12 Aug". */
internal fun LocalDate.asDayHeader(today: LocalDate): String = when (this) {
    today -> "Today"
    today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
    else -> DAY_HEADER_FORMAT.format(this)
}

private val DAY_HEADER_FORMAT = LocalDate.Format {
    dayOfWeek(kotlinx.datetime.format.DayOfWeekNames.ENGLISH_ABBREVIATED)
    char(' ')
    day()
    char(' ')
    monthName(MonthNames.ENGLISH_ABBREVIATED)
}

private fun Int.padded(): String = if (this < 10) "0$this" else toString()

private fun Long.padded(): String = if (this < 10) "0$this" else toString()
