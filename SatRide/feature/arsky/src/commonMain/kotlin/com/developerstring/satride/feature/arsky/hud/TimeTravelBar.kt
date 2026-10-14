package com.developerstring.satride.feature.arsky.hud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.feature.arsky.SkyEvent
import com.developerstring.satride.feature.arsky.TimeTravelUi
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Scrub the sky forwards and backwards in time.
 *
 * P5.7, behind a flag and the phase's third cut line. The flag ships either way, so this file
 * exists whether or not the feature is switched on — a cut that removes a screen is a much larger
 * change than a cut that flips a boolean, and the point of naming a cut line in advance is to make
 * taking it cheap.
 *
 * The design follows Stardroid's `TimeTravelClock` (Apache-2.0, attributed in
 * `docs/plan/07-references.md`): a symmetric offset around now, and a prominent way back to live
 * — because the one thing that reliably confuses people about time travel is not noticing they
 * are still in it.
 */
@Composable
internal fun TimeTravelBar(state: TimeTravelUi, onEvent: (SkyEvent) -> Unit, modifier: Modifier = Modifier) {
    if (!state.enabled) return

    GlassPanel(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.offset.asOffsetLabel(),
                style = MaterialTheme.typography.titleSmall,
                color = if (state.isLive) SatRideTheme.accents.success else SatRideTheme.accents.warning,
            )
            AccentChip(
                label = "LIVE",
                accent = if (state.isLive) SatRideTheme.accents.success else SatRideTheme.accents.warning,
                modifier = Modifier.clickable(enabled = !state.isLive) { onEvent(SkyEvent.TimeReset) },
            )
        }

        Slider(
            value = state.offset.inWholeMinutes.toFloat(),
            onValueChange = { onEvent(SkyEvent.TimeScrubbed(it.roundToInt().minutes)) },
            onValueChangeFinished = { onEvent(SkyEvent.TimeScrubEnded) },
            valueRange = -MAX_MINUTES..MAX_MINUTES,
        )
    }
}

/** ±24 hours, matching the phase file. */
private const val MAX_MINUTES = 24f * 60f

private fun Duration.asOffsetLabel(): String {
    if (this == Duration.ZERO) return "Live"
    val minutes = abs(inWholeMinutes)
    val sign = if (isNegative()) "−" else "+"
    return when {
        minutes < 60 -> "$sign${minutes}m"
        minutes % 60 == 0L -> "$sign${minutes / 60}h"
        else -> "$sign${minutes / 60}h ${minutes % 60}m"
    }
}
