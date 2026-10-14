package com.developerstring.satride.feature.passes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.common.asCompassPoint
import com.developerstring.satride.core.common.format
import com.developerstring.satride.core.data.PassCoverage
import com.developerstring.satride.core.designsystem.art.KurzMoon
import com.developerstring.satride.core.designsystem.art.floating
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.FirstSyncLoading
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.NumericSize
import com.developerstring.satride.core.designsystem.component.NumericText
import com.developerstring.satride.core.designsystem.component.PolarPassPlot
import com.developerstring.satride.core.designsystem.component.PolarPlotStyle
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.component.SatelliteGlyph
import com.developerstring.satride.core.designsystem.component.ScreenReveal
import com.developerstring.satride.core.designsystem.component.SkyPoint
import com.developerstring.satride.core.designsystem.component.readableWidth
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.core.permissions.rememberNotificationPermission
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel

/**
 * The pass timeline: a vertical schedule of what is coming over.
 *
 * The retention mechanic of the whole app. Someone opens SatRide once out of curiosity; they come
 * back because this screen told them there is a 78° ISS pass at nine tonight.
 */
@Composable
fun PassesScreen(
    modifier: Modifier = Modifier,
    viewModel: PassesViewModel = koinViewModel(),
    onOpenPass: (noradId: Int, aosEpochMillis: Long) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val coverage = viewModel.coverage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val notifications = rememberNotificationPermission()

    // Remembered across the permission round trip so the reminder the user actually asked for is
    // the one that gets armed when they say yes — rather than making them tap [Remind me] twice.
    var pendingReminder by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PassesEffect.OpenPass -> onOpenPass(effect.noradId, effect.aosEpochMillis)

                is PassesEffect.ShowMessage -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(effect.text)
                }

                PassesEffect.RequestNotificationPermission -> notifications.request()
            }
        }
    }

    LaunchedEffect(notifications.status) {
        val pending = pendingReminder ?: return@LaunchedEffect
        if (notifications.status.settled) {
            pendingReminder = null
            viewModel.onNotificationPermissionResult(notifications.status.isGranted, pending)
        }
    }

    // Shell on the navigation frame, timeline on the next; see `ScreenReveal`.
    ScreenReveal(modifier.fillMaxSize()) {
        PassesContent(
            state = state,
            // Passed as a `State` that nothing above the banner reads. Progress ticks several
            // times a second while a large selection is being predicted, and reading it here would
            // rebuild the whole timeline each time — which is what made this screen appear to lock
            // up the first time somebody tracked the full catalog.
            coverage = coverage,
            onEvent = { event ->
                if (event is PassesEvent.RemindToggled) pendingReminder = event.id
                viewModel.onEvent(event)
            },
        )
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (state.filterSheetOpen) {
        PassFilterSheet(
            filters = state.filters,
            onFiltersChanged = { viewModel.onEvent(PassesEvent.FiltersChanged(it)) },
            onClear = { viewModel.onEvent(PassesEvent.FiltersCleared) },
            onDismiss = { viewModel.onEvent(PassesEvent.FilterSheetDismissed) },
        )
    }
}

@Composable
internal fun PassesContent(
    state: PassesUiState,
    coverage: State<PassCoverage> = remember { mutableStateOf(PassCoverage()) },
    onEvent: (PassesEvent) -> Unit,
    modifier: Modifier = Modifier,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    // Nothing computed yet AND the search is running — the first-launch case. The pass search
    // is seconds of SGP4 across the curated set; composing a timeline that fills in row by row
    // under that load is what read as lag. Show the loader, then reveal the finished week
    // once. When passes already exist (every later visit), the thin progress bar below keeps
    // the list interactive instead.
    if (state.computing && state.days.isEmpty() && !state.loading) {
        FirstSyncLoading(
            title = "Working out the week…",
            caption = "Computing every pass over your location for the days ahead.",
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    Column(modifier = modifier.fillMaxSize().readableWidth()) {
        WindowRow(state = state, onEvent = onEvent)

        if (state.computing) {
            // Real work, not a spinner for its own sake: a seven-day search is seconds of
            // propagation across the tracked selection.
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
                Text(
                    text = "Working out the week…",
                    style = MaterialTheme.typography.labelSmall,
                    color = SatRideTheme.accents.tracking,
                )
                LinearProgressIndicator(
                    color = SatRideTheme.accents.tracking,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                )
            }
        }

        CoverageBanner(coverage)

        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

            state.isEmpty -> PassesEmptyState(state)

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // `navBarClearance` at the bottom: the tab bar floats over this list instead of
                // reserving space, so without it the last pass of the day is unreadable under the
                // pill however far you scroll.
                contentPadding = PaddingValues(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.sm,
                    bottom = Spacing.navBarClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                state.days.forEach { day ->
                    item(key = "day-${day.date}") { DayHeader(day) }
                    items(day.passes, key = { it.id }) { row ->
                        PassCard(row = row, alertsSupported = state.alertsSupported, onEvent = onEvent, zone = timeZone)
                    }
                }
            }
        }
    }
}

/**
 * How much of the tracked selection the timeline covers, while it is still filling.
 *
 * Only shown mid-walk. Predicting a large selection is minutes of background work, and a timeline
 * that quietly grew from a few hundred passes to several thousand with nothing to explain it reads
 * as a bug — or worse, as an app that had been hiding passes. Once the walk is done the banner
 * disappears, because a complete answer needs no caveat.
 */
@Composable
private fun CoverageBanner(coverage: State<PassCoverage>) {
    // The only composable in the tree that reads the progress, which is the whole point: a tick
    // recomposes this line and nothing else.
    val value = coverage.value
    if (!value.running || value.tracked <= 0 || value.complete) return

    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs)) {
        Text(
            text = "Predicting passes — ${value.predicted.grouped()} of ${value.tracked.grouped()} " +
                "tracked satellites",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            // The bar animates itself between ticks, so a coarse progress report still reads as
            // continuous motion rather than as a bar that jumps.
            progress = { value.fraction },
            color = SatRideTheme.accents.tracking,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
        )
    }
}

/**
 * Thousands separators, without a platform formatter.
 *
 * Kotlin/Multiplatform has no common number formatting, and these counts run to five digits once
 * the whole catalog is tracked.
 */
private fun Int.grouped(): String {
    val digits = toString()
    if (digits.length <= 4) return digits
    return digits.reversed().chunked(3).joinToString(",").reversed()
}

@Composable
private fun WindowRow(state: PassesUiState, onEvent: (PassesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PassWindow.entries.forEach { window ->
            val selected = window == state.window
            AccentChip(
                label = window.label,
                accent = if (selected) SatRideTheme.accents.tracking else SatRideTheme.accents.trackingDim,
                modifier = Modifier.clickable { onEvent(PassesEvent.WindowSelected(window)) },
            )
        }

        Spacer(Modifier.weight(1f))

        SatRideButton(
            text = if (state.filters.activeCount > 0) "Filters ${state.filters.activeCount}" else "Filters",
            onClick = { onEvent(PassesEvent.FilterSheetOpened) },
            style = SatRideButtonStyle.Secondary,
        )
    }
}

@Composable
private fun DayHeader(day: PassDayUi) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md, bottom = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = day.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = SatRideTheme.accents.tracking,
        )
        Text(
            text = if (day.passes.size == 1) "1 pass" else "${day.passes.size} passes",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One pass.
 *
 * The polar thumbnail is the point of the card: max elevation is a number people have to learn to
 * read, and the shape of the arc is one they can read immediately — through the middle is
 * overhead, hugging the rim is a scrape along the horizon.
 */
@Composable
private fun PassCard(row: PassRowUi, alertsSupported: Boolean, onEvent: (PassesEvent) -> Unit, zone: TimeZone) {
    val open = remember(row.id) { { onEvent(PassesEvent.PassClicked(row.id)) } }

    GlassPanel(modifier = Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolarPassPlot(
                arc = row.arc,
                style = PolarPlotStyle.THUMBNAIL,
                accent = row.grade.accent(),
                live = if (row.inProgress) row.livePoint() else null,
            )

            Spacer(Modifier.width(Spacing.md))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SatelliteGlyph(
                        kind = row.classType.glyph,
                        tint = row.classType.accent(),
                        modifier = Modifier.padding(end = Spacing.sm),
                    )
                    if (row.hasDownlink) {
                        Box(Modifier.size(7.dp).background(SatRideTheme.accents.radio, CircleShape))
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(Spacing.xs))

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    NumericText(text = row.aos.asClockTime(zone), size = NumericSize.Medium)
                    NumericText(
                        text = "max ${row.maxElevationDeg.format(0)}°",
                        size = NumericSize.Small,
                        color = row.grade.accent(),
                    )
                    NumericText(
                        text = row.duration.asPassLength(),
                        size = NumericSize.Small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = "rises ${row.aosAzimuthDeg.asCompassPoint()}, sets ${row.losAzimuthDeg.asCompassPoint()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (row.inProgress) {
                    AccentChip(label = "Up now", accent = SatRideTheme.accents.success)
                } else {
                    NumericText(text = "in ${row.startsIn.asCountdown()}", size = NumericSize.Small)
                }
                AccentChip(label = row.grade.label, accent = row.grade.accent())

                if (alertsSupported) {
                    SatRideButton(
                        text = if (row.hasAlert) "Reminding" else "Remind me",
                        onClick = { onEvent(PassesEvent.RemindToggled(row.id)) },
                        style = if (row.hasAlert) SatRideButtonStyle.Primary else SatRideButtonStyle.Secondary,
                    )
                }
            }
        }
    }
}

/**
 * Where the satellite is during a pass in progress.
 *
 * Interpolated along the stored arc rather than propagated: the arc is already in memory, the plot
 * is 56 dp across, and running SGP4 once a second per visible card to move a dot by a pixel would
 * be the most expensive thing on the screen by a wide margin.
 */
internal fun PassRowUi.livePoint(): SkyPoint? {
    if (arc.isEmpty()) return null
    val total = (los - aos).inWholeSeconds.takeIf { it > 0 } ?: return null
    val elapsed = (-startsIn).inWholeSeconds.coerceIn(0, total)
    val index = ((elapsed.toDouble() / total) * (arc.size - 1)).toInt().coerceIn(0, arc.size - 1)
    return arc[index]
}

/**
 * Empty states that explain themselves.
 *
 * "Quiet sky" is a real answer about the sky, not an error — but it is only the *right* answer when
 * the app actually knows where the user is and has looked. The other two cases say what is
 * actually wrong instead of dressing a missing position up as an uneventful evening.
 */
@Composable
private fun PassesEmptyState(state: PassesUiState) {
    val (headline, detail) = when {
        state.observerUnknown ->
            "No location yet" to
                "SatRide needs a rough location to work out what comes over you. You can set one by " +
                "hand in Settings."

        state.filteredToNothing ->
            "Nothing matches" to "There are passes in this window, but your filters are narrower than all of them."

        state.window == PassWindow.DAY ->
            "Quiet sky" to "Nothing worth going outside for in the next 24 hours. Try the seven-day view."

        else -> "Quiet sky" to "Nothing above your minimum elevation this week."
    }

    Box(Modifier.fillMaxSize().padding(Spacing.xl), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // A quiet sky is an answer about the sky, so it gets a sky: the moon drifting by.
            KurzMoon(
                Modifier
                    .size(120.dp)
                    .floating(amplitude = 6.dp),
            )
            Spacer(Modifier.height(Spacing.lg))
            Text(headline, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
