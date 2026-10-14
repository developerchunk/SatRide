package com.developerstring.satride.feature.passes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.developerstring.satride.core.common.format
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing

/**
 * Narrowing the timeline.
 *
 * Every control here narrows what is already predicted, which is why the sheet is short.
 *
 * Elevation is stepped rather than a continuous slider. The stops are the numbers that mean
 * something to an operator (10° is the app's own default floor, 20° is usable, 40° is a good pass),
 * and a slider that lands on 37° invites precision that the choice does not have.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PassFilterSheet(
    filters: PassFilters,
    onFiltersChanged: (PassFilters) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Filter passes", style = MaterialTheme.typography.titleMedium)
                if (filters.activeCount > 0) {
                    SatRideButton(text = "Clear", onClick = onClear, style = SatRideButtonStyle.Secondary)
                }
            }

            FilterGroup(title = "Show") {
                Toggle(
                    label = "Followed only",
                    selected = filters.followedOnly,
                    onClick = { onFiltersChanged(filters.copy(followedOnly = !filters.followedOnly)) },
                )
                Toggle(
                    label = "Has a downlink",
                    selected = filters.radioCapableOnly,
                    accent = SatRideTheme.accents.radio,
                    onClick = { onFiltersChanged(filters.copy(radioCapableOnly = !filters.radioCapableOnly)) },
                )
            }

            FilterGroup(title = "Minimum peak elevation") {
                PassFilters.ELEVATION_STOPS.forEach { stop ->
                    Toggle(
                        label = if (stop == 0.0) "Any" else "${stop.format(0)}°",
                        selected = filters.minMaxElevationDeg == stop,
                        onClick = { onFiltersChanged(filters.copy(minMaxElevationDeg = stop)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) { content() }
    }
}

@Composable
private fun Toggle(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: androidx.compose.ui.graphics.Color = SatRideTheme.accents.tracking,
) {
    AccentChip(
        label = label,
        accent = if (selected) accent else SatRideTheme.accents.trackingDim,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
