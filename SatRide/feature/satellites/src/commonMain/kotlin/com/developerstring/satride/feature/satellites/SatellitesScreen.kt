package com.developerstring.satride.feature.satellites

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import com.developerstring.satride.core.data.LiveSky
import com.developerstring.satride.core.data.RefreshState
import com.developerstring.satride.core.data.TleFreshness
import com.developerstring.satride.core.designsystem.art.KurzSatellite
import com.developerstring.satride.core.designsystem.art.floating
import com.developerstring.satride.core.designsystem.art.rememberSceneCycle
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.FirstSyncLoading
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
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

/**
 * The catalog: browse, search, filter.
 *
 * The reference implementation of the app's screen pattern — state in, events out, no logic here.
 * Row composables read pre-computed fields and allocate nothing, because the whole list
 * recomposes once a second as live positions tick.
 */
@Composable
fun SatellitesScreen(
    modifier: Modifier = Modifier,
    viewModel: SatellitesViewModel = koinViewModel(),
    onOpenDetail: (Int) -> Unit = {},
    onOpenImport: () -> Unit = {},
    onOpenManageTracking: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Deliberately `collectAsStateWithLifecycle` into a `State` that is *not* read here.
    //
    // Reading `.value` at this level would recompose the whole catalog once a second, which is
    // exactly what the column-wise position table exists to prevent. The `State` is passed down
    // untouched and only read inside each row's `derivedStateOf`, so a tick recomposes the handful
    // of rows whose displayed degree actually changed and nothing else.
    val sky = viewModel.sky.collectAsStateWithLifecycle()

    CollectEffects(viewModel, onOpenDetail, onOpenImport, onOpenManageTracking, snackbarHostState)

    // The catalog is the heaviest first composition in the app — a search field, a segmented row
    // and up to several hundred measured rows, all due on the frame the tab swap is drawn. The
    // reveal moves that off the navigation frame; see `ScreenReveal`.
    ScreenReveal(modifier.fillMaxSize()) {
        SatellitesContent(state = state, sky = sky, onEvent = viewModel::onEvent)
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (state.filterSheetOpen) {
        FilterSheet(
            filters = state.filters,
            onFiltersChanged = { viewModel.onEvent(SatellitesEvent.FiltersChanged(it)) },
            onClear = { viewModel.onEvent(SatellitesEvent.FiltersCleared) },
            onDismiss = { viewModel.onEvent(SatellitesEvent.FilterSheetDismissed) },
        )
    }
}

@Composable
private fun CollectEffects(
    viewModel: SatellitesViewModel,
    onOpenDetail: (Int) -> Unit,
    onOpenImport: () -> Unit,
    onOpenManageTracking: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SatellitesEffect.OpenDetail -> onOpenDetail(effect.noradId)

                SatellitesEffect.OpenImport -> onOpenImport()

                SatellitesEffect.OpenManageTracking -> onOpenManageTracking()

                is SatellitesEffect.ShowMessage -> {
                    // Replaces whatever is showing: the newest refresh result is the only one
                    // worth reading, and queued stale messages would outlive their meaning.
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(effect.text)
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SatellitesContent(
    state: SatellitesUiState,
    sky: State<LiveSky>,
    onEvent: (SatellitesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // First launch only: the catalog has never been refreshed and the initial download is
    // running. A half-seeded list under that load re-lays-out ticking rows while SGP4 and DB
    // writes saturate the device — showing the loader instead is what keeps the frame smooth,
    // and the user meets the finished catalog once, not a trickle. Offline first launches fall
    // through when the refresh stops running, and the bundled catalog shows as usual.
    val running = state.refresh as? RefreshState.Running
    if (state.freshness == TleFreshness.Empty && running != null) {
        FirstSyncLoading(
            title = "Fetching the sky…",
            caption = "Downloading the satellite catalog and fresh orbits.\nThis only happens once.",
            detail = "${running.progress.label} — ${running.progress.completed}/${running.progress.total}",
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    Column(modifier = modifier.fillMaxSize().readableWidth()) {
        SearchRow(state = state, onEvent = onEvent)
        SegmentRow(state = state, onEvent = onEvent)
        TrackingBar(state = state, onEvent = onEvent)
        RefreshProgressBar(state.refresh)
        FreshnessBanner(state.freshness)

        when {
            // A shimmer of the rows that are coming, not a spinner. A spinner says "wait"; the
            // catalog is arriving in milliseconds and the shimmer says so, at the size the rows
            // will actually be, so nothing jumps when they land.
            state.loading -> ShimmerList(
                Modifier.fillMaxSize().padding(Spacing.lg),
                rows = 7,
            )

            state.isEmpty -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { onEvent(SatellitesEvent.RefreshRequested) },
            ) {
                // Scrollable so the pull gesture has something to grab even with no rows.
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { EmptyState(state) }
            }

            // Pull to refresh wraps the list and the empty state alike: "quiet sky" is exactly
            // where someone reaches for a refresh, so making the gesture unavailable there would
            // remove it from the one screen that most invites it.
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { onEvent(SatellitesEvent.RefreshRequested) },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // `navBarClearance` at the bottom: the tab bar floats over the list rather
                    // than reserving space, so the last row would otherwise sit under the pill.
                    contentPadding = PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.lg,
                        bottom = Spacing.navBarClearance,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(state.rows, key = { it.noradId }) { row ->
                        SatelliteRow(row = row, sky = sky, onEvent = onEvent)
                    }

                    if (state.scope.truncated) {
                        item(key = "catalog-footer") { TruncationFooter(state, onEvent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(state: SatellitesUiState, onEvent: (SatellitesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { onEvent(SatellitesEvent.QueryChanged(it)) },
            label = { Text("Search name or catalog number") },
            singleLine = true,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SatRideTheme.accents.tracking,
                unfocusedBorderColor = SatRideTheme.accents.hairline,
                focusedLabelColor = SatRideTheme.accents.tracking,
            ),
            modifier = Modifier.weight(1f),
        )
        SatRideButton(
            text = if (state.filters.activeCount > 0) "Filters ${state.filters.activeCount}" else "Filters",
            onClick = { onEvent(SatellitesEvent.FilterSheetOpened) },
            style = SatRideButtonStyle.Secondary,
        )
        CatalogOverflow(onEvent)
    }
}

/**
 * The catalog's overflow menu.
 *
 * Import lives here as well as in Settings, because the moment someone wants it is the moment they
 * have searched the catalog for a new launch and not found it — not later, from a settings screen
 * they would have to go looking for.
 */
@Composable
private fun CatalogOverflow(onEvent: (SatellitesEvent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        SatRideButton(text = "\u22EF", onClick = { expanded = true }, style = SatRideButtonStyle.Secondary)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Manage tracking") },
                onClick = {
                    expanded = false
                    onEvent(SatellitesEvent.ManageTrackingRequested)
                },
            )
            DropdownMenuItem(
                text = { Text("Import element sets") },
                onClick = {
                    expanded = false
                    onEvent(SatellitesEvent.ImportRequested)
                },
            )
        }
    }
}

@Composable
private fun SegmentRow(state: SatellitesUiState, onEvent: (SatellitesEvent) -> Unit) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        CatalogSegment.entries.forEach { segment ->
            val selected = segment == state.segment
            AccentChip(
                label = segment.label,
                accent = if (selected) SatRideTheme.accents.tracking else SatRideTheme.accents.trackingDim,
                modifier = Modifier.clickable { onEvent(SatellitesEvent.SegmentSelected(segment)) },
            )
        }
    }
}

/**
 * One catalog row.
 *
 * ## Where the live degree comes from
 *
 * Not from [row]. The row is rebuilt only when something a person could see has changed — a name,
 * whether the satellite is up, the whole minute a pass is due in — so it is stable for seconds at a
 * time and Compose skips it.
 *
 * The degree of elevation, which changes every second, is read here through a [derivedStateOf]
 * over the shared position table. The derived state only invalidates when the *rounded* value
 * changes, and only rows that are actually composed hold one — so the cost of live numbers is a
 * dozen integer comparisons per second, whatever the catalog's size. Reading the table at the
 * screen level instead would recompose every row every second, which over a full catalog is
 * roughly ten thousand times the work for the same picture.
 */
@Composable
private fun SatelliteRow(row: SatelliteRowUi, sky: State<LiveSky>, onEvent: (SatellitesEvent) -> Unit) {
    // Remembered per row id so the lambda identity is stable across recompositions, which is what
    // keeps the list from re-laying-out every row whenever the list is re-emitted.
    val handleClick = remember(row.noradId) { { onEvent(SatellitesEvent.RowClicked(row.noradId)) } }
    val handleTrack = remember(row.noradId) { { onEvent(SatellitesEvent.TrackToggled(row.noradId)) } }

    GlassPanel(modifier = Modifier.fillMaxWidth().clickable(onClick = handleClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SatelliteGlyph(
                kind = row.classType.glyph,
                tint = row.classType.accent(),
                modifier = Modifier.padding(end = Spacing.md),
            )

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.hasDownlink) {
                        // The amber dot is the bridge to the radio half of the app: it means
                        // "this one you can also hear".
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(SatRideTheme.accents.radio, CircleShape),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.classType.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = row.classType.accent(),
                    )
                    // Stable identity rather than a second ticking number: the catalog number is
                    // what a user searches by and quotes, and the launch year says something the
                    // name often does not.
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

            LiveReadout(row = row, sky = sky)

            TrackStar(tracked = row.tracked, onToggle = handleTrack)
        }
    }
}

/**
 * The right-hand column: a live elevation while the satellite is up, a countdown otherwise.
 *
 * Split out so the `derivedStateOf` read is confined to the smallest possible composable. Anything
 * above this in the tree recomposes at the speed of the row list, not at the speed of the sky.
 */
@Composable
private fun LiveReadout(row: SatelliteRowUi, sky: State<LiveSky>) {
    val noradId = row.noradId
    val elevation by remember(noradId) {
        derivedStateOf {
            val value = sky.value.elevationDegOf(noradId)
            // NaN means "not being tracked, or no position yet", which is a different thing from
            // "below the horizon" and has to stay distinguishable.
            if (value.isNaN()) null else value.roundToInt()
        }
    }

    Column(horizontalAlignment = Alignment.End) {
        val live = elevation
        if (live != null && live > 0) {
            NumericText(
                text = "$live°",
                size = NumericSize.Medium,
                color = SatRideTheme.accents.tracking,
            )
            Text(
                text = "above horizon",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val countdown = row.nextPassIn.asCountdown()
            if (countdown != null) {
                NumericText(text = "in $countdown", size = NumericSize.Small)
                row.nextPassMaxElevationDeg?.let {
                    NumericText(
                        text = "max ${it.format(0)}°",
                        size = NumericSize.Small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = if (live == null) "not tracked" else "below horizon",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The per-row tracking toggle.
 *
 * On the row rather than only on the tracking screen because that is where the intent forms: the
 * user has just searched for a satellite, found it, and wants it live. Making them remember its
 * name and go and find it again in a management screen is the kind of trip that stops people
 * customising anything at all.
 */
@Composable
private fun TrackStar(tracked: Boolean, onToggle: () -> Unit) {
    Text(
        text = if (tracked) "\u2605" else "\u2606",
        style = MaterialTheme.typography.titleLarge,
        color = if (tracked) SatRideTheme.accents.tracking else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = Spacing.sm)
            .clickable(onClick = onToggle)
            .padding(Spacing.xs),
    )
}

/**
 * How much of the catalog is not on screen, and the two ways out of it.
 *
 * Shown rather than silently capping the list. Somebody browsing thirty thousand objects has to be
 * told that this is three hundred of them, or they will conclude the app simply does not have the
 * satellite they are looking for.
 */
@Composable
private fun TruncationFooter(state: SatellitesUiState, onEvent: (SatellitesEvent) -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = "Showing ${state.scope.shown} of ${state.scope.matched} matches",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "Search by name or catalog number to narrow this down, or open tracking to " +
                    "work through the whole catalog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.sm))
            SatRideButton(
                text = "Manage tracking",
                onClick = { onEvent(SatellitesEvent.ManageTrackingRequested) },
                style = SatRideButtonStyle.Secondary,
            )
        }
    }
}

/**
 * A one-line summary of the tracking selection, above the list.
 *
 * The single most important number in the app once the catalog is unlocked: how many satellites
 * are live. It is a button as well as a read-out, because the answer is nearly always followed by
 * wanting to change it.
 */
@Composable
private fun TrackingBar(state: SatellitesUiState, onEvent: (SatellitesEvent) -> Unit) {
    if (state.catalogCount == 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${state.trackedCount} tracked of ${state.catalogCount} in the catalog",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Manage",
            style = MaterialTheme.typography.labelLarge,
            color = SatRideTheme.accents.tracking,
            modifier = Modifier
                .clickable { onEvent(SatellitesEvent.ManageTrackingRequested) }
                .padding(Spacing.xs),
        )
    }
}

/**
 * Empty states that say what happened and what to do, rather than showing nothing.
 *
 * "Up now" being empty is the common case and is not an error — it is a real answer about the
 * sky, and the next pass is the useful thing to offer alongside it.
 */
@Composable
private fun EmptyState(state: SatellitesUiState) {
    val (headline, detail) = when {
        state.query.isNotBlank() ->
            "Nothing matches \"${state.query}\"" to
                "Try a catalog number, or part of the name."

        state.observerUnknown ->
            "No location yet" to
                "SatRide needs a rough location to work out what is overhead. You can also set one by hand in Settings."

        state.segment == CatalogSegment.UP_NOW ->
            "Quiet sky" to
                "Nothing is above your horizon right now. Check Popular for what is coming up next."

        state.filters.activeCount > 0 -> "No matches" to "Your filters are narrower than this segment."

        else -> "Nothing here yet" to "The catalog is still loading."
    }

    Box(Modifier.fillMaxSize().padding(Spacing.xl), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KurzSatellite(
                blink = rememberSceneCycle(periodMillis = 2000, label = "catalogEmptyBeacon"),
                modifier = Modifier
                    .size(130.dp)
                    .floating(amplitude = 7.dp),
            )
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
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

/**
 * Real progress through a refresh, below the segment chips.
 *
 * The fraction is measured, not animated: the endpoint list is known before the first request, so
 * each step is a source that has actually answered. A bar that invents its own motion is worse
 * than none — it tells the user the app is working when it may be stuck, which is exactly when
 * they most need the truth.
 *
 * It names the source being fetched as well, because "CelesTrak · CubeSats" explains a pause that
 * a bare percentage does not.
 */
@Composable
private fun RefreshProgressBar(refresh: RefreshState) {
    val running = refresh as? RefreshState.Running ?: return
    val progress = running.progress

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Refreshing — ${progress.label}",
                style = MaterialTheme.typography.labelSmall,
                color = SatRideTheme.accents.tracking,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            NumericText(
                text = "${progress.completed}/${progress.total}",
                size = NumericSize.Small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { progress.fraction },
            color = SatRideTheme.accents.tracking,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
        )
    }
}

@Composable
private fun FreshnessBanner(freshness: TleFreshness) {
    // Silent while the data is fresh. A banner that is always on stops being read, and the point
    // of this one is that the user notices when pass times start drifting.
    val (text, accent) = when (freshness) {
        TleFreshness.Empty -> "Loading orbital data…" to SatRideTheme.accents.tracking

        is TleFreshness.Fresh -> return

        is TleFreshness.Aging -> "Orbital data ${freshness.age.inWholeDays} days old" to SatRideTheme.accents.warning

        is TleFreshness.Stale ->
            "Orbital data ${freshness.age.inWholeDays} days old — pass times may be off" to
                SatRideTheme.accents.danger
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs)) {
        AccentChip(label = text, accent = accent)
    }
}
