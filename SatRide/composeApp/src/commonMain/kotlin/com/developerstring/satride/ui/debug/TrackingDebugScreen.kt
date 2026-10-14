package com.developerstring.satride.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.NumericSize
import com.developerstring.satride.core.designsystem.component.NumericText
import com.developerstring.satride.core.designsystem.component.SectionHeader
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.core.model.ObserverPosition
import com.developerstring.satride.core.sensors.CompassAccuracy
import org.koin.compose.viewmodel.koinViewModel

/**
 * Live view of the sensors and the tracking engine.
 *
 * Exists to be checked against reality: hold the phone next to a known reference tracker and the
 * numbers should agree. Frame conventions, declination signs and interpolation wrap-around can
 * all pass their unit tests and still be visibly wrong on a device, and this is the screen that
 * shows it. Not reachable from a shipping build (P13 strips the route with the design gallery).
 */
@Composable
fun TrackingDebugScreen(modifier: Modifier = Modifier, viewModel: TrackingDebugViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        SectionHeader(
            title = "Tracking",
            trailing = {
                Text(
                    text = "${state.trackedCount} tracked",
                    style = MaterialTheme.typography.labelMedium,
                    color = SatRideTheme.accents.tracking,
                )
            },
        )

        GlassPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
        ) {
            LabelledValue("Observer", state.observer.describe())
            LabelledValue(
                "Pointing",
                state.pointing?.let {
                    "az ${it.azimuthDeg.format(1)}°  el ${it.elevationDeg.format(1)}°"
                } ?: if (state.compassAvailable) "waiting for compass" else "no compass on this device",
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                AccentChip(
                    label = "Compass ${state.accuracy.name.lowercase()}",
                    accent = state.accuracy.accent(),
                )
            }
            if (state.accuracy.needsCalibration && state.compassAvailable) {
                Text(
                    text = "Move away from metal and trace a figure of eight.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }

        SectionHeader(title = "Highest first")

        LazyColumn(
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(state.rows, key = { it.noradId }) { row ->
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = row.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (row.sunlit) {
                            AccentChip(label = "sunlit", accent = SatRideTheme.accents.warning)
                        }
                    }
                    NumericText(
                        text = "az ${row.azimuthDeg.format(1)}°  el ${row.elevationDeg.format(1)}°  " +
                            "${row.rangeKm.format(0)} km",
                        size = NumericSize.Small,
                    )
                    NumericText(
                        text = "ṙ ${row.rangeRateKmS.format(3)} km/s",
                        size = NumericSize.Small,
                    )
                    NumericText(
                        text = "Δf ${viewModel.dopplerHzAt(row.rangeRateKmS)} Hz @ 145.800 MHz",
                        size = NumericSize.Small,
                        color = SatRideTheme.accents.radio,
                    )
                    row.separationDeg?.let {
                        // Plain Text, not NumericText: this is a sentence, and NumericText pins
                        // itself to a single line so the tail was being clipped on device.
                        Text(
                            text = "${it.format(1)}° from where the phone points",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun ObserverPosition.describe(): String = when (this) {
    is ObserverPosition.Automatic ->
        "${position.latDeg.format(4)}, ${position.lonDeg.format(4)} " +
            (accuracyM?.let { "±${it.format(0)} m" } ?: "(device)")

    is ObserverPosition.Manual -> "${position.latDeg.format(4)}, ${position.lonDeg.format(4)} (manual)"

    ObserverPosition.Unknown -> "unknown"
}

@Composable
private fun CompassAccuracy.accent() = when (this) {
    CompassAccuracy.HIGH -> SatRideTheme.accents.success
    CompassAccuracy.MEDIUM -> SatRideTheme.accents.warning
    CompassAccuracy.LOW, CompassAccuracy.UNCALIBRATED -> SatRideTheme.accents.danger
}

/** Fixed-decimal formatting; Compose Multiplatform common code has no `String.format`. */
private fun Double.format(decimals: Int): String {
    var scale = 1L
    repeat(decimals) { scale *= 10L }
    val scaled = kotlin.math.round(kotlin.math.abs(this) * scale).toLong()
    val sign = if (this < 0.0 && scaled != 0L) "-" else ""
    val whole = scaled / scale
    if (decimals == 0) return "$sign$whole"
    return "$sign$whole.${(scaled % scale).toString().padStart(decimals, '0')}"
}
