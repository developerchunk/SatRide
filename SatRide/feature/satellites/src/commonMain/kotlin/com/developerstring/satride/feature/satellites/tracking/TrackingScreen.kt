package com.developerstring.satride.feature.satellites.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.data.RefreshState
import com.developerstring.satride.core.datastore.TrackingBudget
import com.developerstring.satride.core.datastore.TrackingMode
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.NumericSize
import com.developerstring.satride.core.designsystem.component.NumericText
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.component.SatelliteGlyph
import com.developerstring.satride.core.designsystem.component.ScreenReveal
import com.developerstring.satride.core.designsystem.component.ShimmerList
import com.developerstring.satride.core.designsystem.component.readableWidth
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.feature.satellites.accent
import com.developerstring.satride.feature.satellites.glyph
import com.developerstring.satride.feature.satellites.label
import org.koin.compose.viewmodel.koinViewModel

/**
 * Choosing what SatRide tracks.
 *
 * ## What this screen is for
 *
 * Everything in the app — the sky view, the catalog's live degrees, the pass timeline — is driven
 * by one set of satellites, and this is where that set is decided. Three routes through it, in the
 * order people take them:
 *
 * 1. **Pick a mode.** Starter set, your own selection, or everything. One tap, and for most people
 *    that is the whole visit.
 * 2. **Bulk-select.** "Everything with a downlink", or search "starlink" and add all 7,000 matches
 *    at once. This is where the screen earns its existence over a list of checkboxes.
 * 3. **Tick individual satellites.** Paged, searchable, and unbounded — every object the app has.
 *
 * ## Why the header shows numbers
 *
 * Because the cost is real and the user is the one paying it. "12,043 selected · 400 live · 37 up
 * now" says exactly what the app is doing, and it is what makes the budget control meaningful
 * rather than a mysterious slider. An app that quietly drops nine tenths of what it was asked to
 * track is worse than one that says so and offers to track more.
 */
@Composable
fun TrackingScreen(modifier: Modifier = Modifier, viewModel: TrackingViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TrackingEffect.ShowMessage -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(effect.text)
                }
            }
        }
    }

    ScreenReveal(modifier.fillMaxSize()) {
        TrackingContent(state = state, onEvent = viewModel::onEvent)
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun TrackingContent(state: TrackingUiState, onEvent: (TrackingEvent) -> Unit, modifier: Modifier = Modifier) {
    if (state.loading) {
        ShimmerList(modifier.fillMaxSize().padding(Spacing.lg), rows = 8)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().readableWidth(),
        contentPadding = PaddingValues(
            start = Spacing.lg,
            end = Spacing.lg,
            top = Spacing.lg,
            bottom = Spacing.navBarClearance,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "summary") { SummaryPanel(state, onEvent) }
        item(key = "modes") { ModePanel(state, onEvent) }
        if (state.budgetMatters) item(key = "budget") { BudgetPanel(state, onEvent) }
        item(key = "bulk") { BulkPanel(state, onEvent) }
        item(key = "search") { SearchField(state, onEvent) }
        item(key = "chips") { FilterChips(state, onEvent) }
        item(key = "results") { ResultsHeader(state, onEvent) }

        // Keyed by catalog number so a checkbox tap re-uses the row it changed rather than
        // rebuilding the page — which over sixty rows is the difference between an instant tick
        // and a visible flicker down the whole list.
        items(state.rows, key = { it.noradId }) { row ->
            TrackingRow(row = row, onToggle = { onEvent(TrackingEvent.RowToggled(row.noradId)) })
        }

        if (state.hasMore) {
            item(key = "more") {
                // Load-more as an explicit control rather than an infinite scroll. The catalog has
                // no natural end, and a list that keeps growing under the thumb makes it
                // impossible to reach anything below it — including, on this screen, nothing.
                SatRideButton(
                    text = "Show more (${state.matching - state.rows.size} left)",
                    onClick = { onEvent(TrackingEvent.LoadMore) },
                    style = SatRideButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The numbers, and what they cost.
 *
 * "Live" is deliberately distinct from "selected". Selecting twenty thousand satellites does not
 * mean twenty thousand orbits are integrated every second — the engine keeps a budgeted tier at
 * full rate and rotates the rest — and hiding that would make the app look either dishonest or
 * broken depending on which the user worked out first.
 */
@Composable
private fun SummaryPanel(state: TrackingUiState, onEvent: (TrackingEvent) -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat(label = "Tracking", value = state.live)
                Stat(label = "Selected", value = state.selected)
                Stat(label = "Up now", value = state.upNow, accent = SatRideTheme.accents.tracking)
                Stat(label = "In catalog", value = state.catalog)
            }

            if (state.arming) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "Preparing orbits…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { state.armedFraction },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                )
            }

            val running = state.refresh as? RefreshState.Running
            if (running != null) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "${running.progress.label} — " +
                        "${running.progress.completed}/${running.progress.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { running.progress.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                )
            }

            if (!state.fullCatalogDownloaded) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "Only the five everyday groups have been downloaded. Fetch the rest to " +
                        "choose from every catalogued object.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
                SatRideButton(
                    text = "Download the full catalog",
                    onClick = { onEvent(TrackingEvent.DownloadFullCatalog) },
                    style = SatRideButtonStyle.Secondary,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, accent: androidx.compose.ui.graphics.Color? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        NumericText(
            text = value.grouped(),
            size = NumericSize.Medium,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModePanel(state: TrackingUiState, onEvent: (TrackingEvent) -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("What to track", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.sm))

            ModeRow(
                title = "Starter set",
                detail = "The satellites worth looking at first. Works offline, costs nothing.",
                selected = state.mode == TrackingMode.CURATED,
                onClick = { onEvent(TrackingEvent.ModeSelected(TrackingMode.CURATED)) },
            )
            ModeRow(
                title = "My selection",
                detail = "${state.selected.grouped()} satellites you have ticked below.",
                selected = state.mode == TrackingMode.SELECTED,
                onClick = { onEvent(TrackingEvent.ModeSelected(TrackingMode.SELECTED)) },
            )
            ModeRow(
                title = "Everything",
                detail = "Every object in the catalog — ${state.catalog.grouped()} of them.",
                selected = state.mode == TrackingMode.ALL,
                onClick = { onEvent(TrackingEvent.ModeSelected(TrackingMode.ALL)) },
            )

            Spacer(Modifier.height(Spacing.sm))
            SatRideButton(
                text = "Show all possible satellites",
                onClick = { onEvent(TrackingEvent.TrackEverything) },
                enabled = !state.working,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ModeRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .background(
                    if (selected) SatRideTheme.accents.tracking else SatRideTheme.accents.hairline,
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * How much work per second the user is willing to spend.
 *
 * Only shown once the selection is big enough for it to change anything — see
 * `TrackingUiState.budgetMatters`. A control that does nothing is worse than a missing one,
 * because the user will try it and conclude the app ignores them.
 */
@Composable
private fun BudgetPanel(state: TrackingUiState, onEvent: (TrackingEvent) -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("Live detail", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Satellites above the horizon are always live. With ${state.live.grouped()} " +
                    "selected, this sets how many of the rest stay current second by second — " +
                    "${state.refreshedPerTick.grouped()} right now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TrackingBudget.entries.forEach { budget ->
                    AccentChip(
                        label = budget.label,
                        accent = if (budget == state.budget) {
                            SatRideTheme.accents.tracking
                        } else {
                            SatRideTheme.accents.trackingDim
                        },
                        modifier = Modifier.clickable { onEvent(TrackingEvent.BudgetSelected(budget)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BulkPanel(state: TrackingUiState, onEvent: (TrackingEvent) -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("Quick selections", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                AccentChip(
                    label = "Starter set",
                    accent = SatRideTheme.accents.tracking,
                    modifier = Modifier.clickable(enabled = !state.working) {
                        onEvent(TrackingEvent.SelectStarterSet)
                    },
                )
                AccentChip(
                    label = "Everything with a downlink",
                    accent = SatRideTheme.accents.radio,
                    modifier = Modifier.clickable(enabled = !state.working) {
                        onEvent(TrackingEvent.SelectWithDownlink)
                    },
                )
                AccentChip(
                    label = "Clear all",
                    accent = SatRideTheme.accents.warning,
                    modifier = Modifier.clickable(enabled = !state.working) {
                        onEvent(TrackingEvent.ClearSelection)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchField(state: TrackingUiState, onEvent: (TrackingEvent) -> Unit) {
    OutlinedTextField(
        value = state.query,
        onValueChange = { onEvent(TrackingEvent.QueryChanged(it)) },
        label = { Text("Search name or catalog number") },
        singleLine = true,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SatRideTheme.accents.tracking,
            unfocusedBorderColor = SatRideTheme.accents.hairline,
            focusedLabelColor = SatRideTheme.accents.tracking,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FilterChips(state: TrackingUiState, onEvent: (TrackingEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        AccentChip(
            label = "Selected only",
            accent = if (state.trackedOnly) SatRideTheme.accents.tracking else SatRideTheme.accents.trackingDim,
            modifier = Modifier.clickable { onEvent(TrackingEvent.TrackedOnlyToggled) },
        )
        AccentChip(
            label = "Has a downlink",
            accent = if (state.withDownlinkOnly) SatRideTheme.accents.radio else SatRideTheme.accents.trackingDim,
            modifier = Modifier.clickable { onEvent(TrackingEvent.WithDownlinkOnlyToggled) },
        )
        state.classChips.forEach { chip ->
            AccentChip(
                label = "${chip.classType.label} ${chip.total.grouped()}",
                accent = if (chip.selected) chip.classType.accent() else SatRideTheme.accents.trackingDim,
                modifier = Modifier.clickable { onEvent(TrackingEvent.ClassToggled(chip.classType)) },
            )
        }
    }
}

/**
 * The count, and the two actions that operate on it.
 *
 * "Add all 7,431" is the single most useful control on the screen: it turns a search into a
 * selection in one tap, which is the only tractable way to pick a constellation out of a catalog
 * this size. It runs as one statement over the matching ids rather than through the visible page,
 * so it means all of them and not the sixty on screen.
 */
@Composable
private fun ResultsHeader(state: TrackingUiState, onEvent: (TrackingEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${state.matching.grouped()} matches",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SatRideButton(
                text = "Add all",
                onClick = { onEvent(TrackingEvent.MatchingToggled(tracked = true)) },
                enabled = !state.working && state.matching > 0,
                style = SatRideButtonStyle.Secondary,
            )
            SatRideButton(
                text = "Remove all",
                onClick = { onEvent(TrackingEvent.MatchingToggled(tracked = false)) },
                enabled = !state.working && state.matching > 0,
                style = SatRideButtonStyle.Secondary,
            )
        }
    }
}

@Composable
private fun TrackingRow(row: TrackingRowUi, onToggle: () -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (row.tracked) "☑" else "☐",
                style = MaterialTheme.typography.titleLarge,
                color = if (row.tracked) {
                    SatRideTheme.accents.tracking
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(Spacing.md))
            SatelliteGlyph(
                kind = row.classType.glyph,
                tint = row.classType.accent(),
                modifier = Modifier.padding(end = Spacing.md),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        text = row.classType.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = row.classType.accent(),
                    )
                    NumericText(
                        text = buildString {
                            append('#')
                            append(row.noradId)
                            row.launchYear?.let {
                                append("  ")
                                append(it)
                            }
                        },
                        size = NumericSize.Small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (row.curated) {
                AccentChip(label = "Starter", accent = SatRideTheme.accents.trackingDim)
            }
        }
    }
}

/**
 * Thousands separators, without a platform formatter.
 *
 * Kotlin/Multiplatform has no common number formatting and this screen is full of five-digit
 * counts, which are genuinely hard to read unseparated — "12043" reads as noise where "12,043"
 * reads as a number.
 */
private fun Int.grouped(): String {
    val digits = toString()
    if (digits.length <= 4) return digits
    return digits.reversed().chunked(3).joinToString(",").reversed()
}
