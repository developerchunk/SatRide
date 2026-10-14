package com.developerstring.satride.feature.satellites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.NumericSize
import com.developerstring.satride.core.designsystem.component.NumericText
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.core.model.SatClass
import kotlin.math.roundToInt

/**
 * Narrowing on top of a segment.
 *
 * Every control here is free and every one composes with every other, which is what makes the
 * sheet worth opening: the interesting queries are conjunctions — "amateur satellites, on UHF,
 * that I can actually decode, above twenty degrees right now" — and a sheet that gated half of
 * them left the user assembling that by hand in the search field, which is not possible.
 *
 * The order is deliberate: the narrowings that cut a full catalog down the most come first.
 * Somebody who has just switched on "track everything" opens this sheet to make thirty thousand
 * objects tractable, and "hide debris" alone removes most of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterSheet(
    filters: SatelliteFilters,
    onFiltersChanged: (SatelliteFilters) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Filters", style = MaterialTheme.typography.titleMedium)
                if (filters.activeCount > 0) {
                    SatRideButton(text = "Clear", onClick = onClear, style = SatRideButtonStyle.Secondary)
                }
            }

            FilterGroup(title = "Quick") {
                Toggle(
                    label = "Up now",
                    on = filters.upNowOnly,
                    accent = SatRideTheme.accents.tracking,
                ) { onFiltersChanged(filters.copy(upNowOnly = !filters.upNowOnly)) }

                Toggle(
                    label = "Tracked only",
                    on = filters.trackedOnly,
                    accent = SatRideTheme.accents.tracking,
                ) { onFiltersChanged(filters.copy(trackedOnly = !filters.trackedOnly)) }

                // Debris and spent stages are the majority of a full catalog by count and almost
                // never what anyone is looking for, so this is the single highest-value control on
                // the sheet once "track everything" is on.
                Toggle(
                    label = "Hide debris and stages",
                    on = filters.hideDebris,
                    accent = SatRideTheme.accents.warning,
                ) { onFiltersChanged(filters.copy(hideDebris = !filters.hideDebris)) }
            }

            FilterGroup(title = "Class") {
                SatClass.entries.forEach { satClass ->
                    val selected = satClass in filters.classes
                    AccentChip(
                        label = satClass.label,
                        accent = if (selected) satClass.accent() else SatRideTheme.accents.trackingDim,
                        modifier = Modifier.clickable {
                            onFiltersChanged(
                                filters.copy(
                                    classes = if (selected) filters.classes - satClass else filters.classes + satClass,
                                ),
                            )
                        },
                    )
                }
            }

            FilterGroup(title = "Radio") {
                Toggle(
                    label = "Has a downlink",
                    on = filters.hasTransmitterOnly,
                    accent = SatRideTheme.accents.radio,
                ) { onFiltersChanged(filters.copy(hasTransmitterOnly = !filters.hasTransmitterOnly)) }

                Toggle(
                    label = "SatRide can decode it",
                    on = filters.listenableOnly,
                    accent = SatRideTheme.accents.radio,
                ) { onFiltersChanged(filters.copy(listenableOnly = !filters.listenableOnly)) }
            }

            FilterGroup(title = "Band") {
                RadioBand.entries.forEach { band ->
                    val selected = band in filters.bands
                    AccentChip(
                        label = band.label,
                        accent = if (selected) SatRideTheme.accents.radio else SatRideTheme.accents.trackingDim,
                        modifier = Modifier.clickable {
                            onFiltersChanged(
                                filters.copy(
                                    bands = if (selected) filters.bands - band else filters.bands + band,
                                ),
                            )
                        },
                    )
                }
            }

            /*
             * Elevation as discrete stops rather than a continuous slider.
             *
             * Below about ten degrees a satellite is behind the trees and the buildings whatever
             * the number says, so a slider that offers 3° against 4° is false precision — and a
             * continuous value would also re-filter the whole catalog on every pixel of drag.
             */
            FilterGroup(title = "Minimum elevation now") {
                SatelliteFilters.ELEVATION_STOPS.forEach { stop ->
                    val selected = filters.minElevationDeg == stop
                    AccentChip(
                        label = if (stop <= 0.0) "Any" else "${stop.format(0)}°+",
                        accent = if (selected) SatRideTheme.accents.tracking else SatRideTheme.accents.trackingDim,
                        modifier = Modifier.clickable {
                            onFiltersChanged(filters.copy(minElevationDeg = stop))
                        },
                    )
                }
            }

            LaunchYearRange(filters = filters, onFiltersChanged = onFiltersChanged)
        }
    }
}

/**
 * Launch-year range.
 *
 * Worth its own control because the catalog spans nearly seventy years and age is the one property
 * that separates "a satellite doing something" from "a 1970s upper stage still up there". Bounded
 * at [EARLIEST_LAUNCH_YEAR] rather than at the real first launch so the slider's useful range is
 * not spent on decades with a handful of objects in them.
 */
@Composable
private fun LaunchYearRange(filters: SatelliteFilters, onFiltersChanged: (SatelliteFilters) -> Unit) {
    val from = (filters.launchedFrom ?: EARLIEST_LAUNCH_YEAR).toFloat()
    val to = (filters.launchedTo ?: LATEST_LAUNCH_YEAR).toFloat()

    Column {
        FilterLabel("Launched")
        NumericText(
            text = if (filters.launchedFrom == null && filters.launchedTo == null) {
                "any year"
            } else {
                "${from.roundToInt()} – ${to.roundToInt()}"
            },
            size = NumericSize.Small,
            color = SatRideTheme.accents.tracking,
        )
        RangeSlider(
            value = from..to,
            onValueChange = { range ->
                val low = range.start.roundToInt()
                val high = range.endInclusive.roundToInt()
                onFiltersChanged(
                    filters.copy(
                        // The extremes mean "unbounded" rather than "exactly 1960": a range that
                        // spans everything must not exclude satellites whose designator we could
                        // not parse a year out of.
                        launchedFrom = low.takeIf { it > EARLIEST_LAUNCH_YEAR },
                        launchedTo = high.takeIf { it < LATEST_LAUNCH_YEAR },
                    ),
                )
            },
            valueRange = EARLIEST_LAUNCH_YEAR.toFloat()..LATEST_LAUNCH_YEAR.toFloat(),
        )
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, accent: androidx.compose.ui.graphics.Color, onToggle: () -> Unit) {
    AccentChip(
        label = label,
        accent = if (on) accent else SatRideTheme.accents.trackingDim,
        modifier = Modifier.clickable(onClick = onToggle),
    )
}

@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    Column {
        FilterLabel(title)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) { content() }
    }
}

@Composable
private fun FilterLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Spacing.xs),
    )
}

private const val EARLIEST_LAUNCH_YEAR = 1960
private const val LATEST_LAUNCH_YEAR = 2030
