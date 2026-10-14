package com.developerstring.satride.feature.satellites.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.NumericSize
import com.developerstring.satride.core.designsystem.component.NumericText
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.component.SatelliteGlyph
import com.developerstring.satride.core.designsystem.component.ScreenReveal
import com.developerstring.satride.core.designsystem.component.SectionHeader
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.feature.satellites.accent
import com.developerstring.satride.feature.satellites.asCompassPoint
import com.developerstring.satride.feature.satellites.asCountdown
import com.developerstring.satride.feature.satellites.asMegahertz
import com.developerstring.satride.feature.satellites.format
import com.developerstring.satride.feature.satellites.glyph
import com.developerstring.satride.feature.satellites.label
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * One satellite: what it is, where it is right now, when it comes over, and what it transmits.
 *
 * The live block is the point of the screen — the numbers tick once a second, which is what makes
 * a catalog entry feel like an instrument rather than a database row.
 */
@Composable
fun SatelliteDetailScreen(
    noradId: Int,
    modifier: Modifier = Modifier,
    viewModel: SatelliteDetailViewModel = koinViewModel { parametersOf(noradId) },
    onOpenPass: (noradId: Int, aosEpochMillis: Long) -> Unit = { _, _ -> },
    onMessage: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SatelliteDetailEffect.OpenPass -> onOpenPass(effect.noradId, effect.aosEpochMillis)

                SatelliteDetailEffect.RadioUnavailable ->
                    onMessage("The radio arrives in a later build — tuning is not wired up yet.")
            }
        }
    }

    // Shell on the navigation frame, the readout on the next; see `ScreenReveal`.
    ScreenReveal(modifier.fillMaxSize()) {
        SatelliteDetailContent(state = state, onEvent = viewModel::onEvent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SatelliteDetailContent(
    state: SatelliteDetailUiState,
    onEvent: (SatelliteDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item { Header(state, onEvent) }
        item { LiveBlock(state) }
        if (state.isIss) {
            item { IssBlock(state) }
        }
        item { NextPassCard(state, onEvent) }

        if (state.transmitters.isNotEmpty()) {
            item { SectionHeader(title = "Downlinks") }
            items(state.transmitters, key = { it.id }) { transmitter ->
                TransmitterCard(
                    transmitter = transmitter,
                    radioAvailable = state.radioAvailable,
                    onTune = { onEvent(SatelliteDetailEvent.TuneClicked(transmitter.id)) },
                )
            }
        } else {
            item {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Text("No known downlinks", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "SatNOGS has no active transmitter on record for this object, so " +
                            "there is nothing to tune.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(state: SatelliteDetailUiState, onEvent: (SatelliteDetailEvent) -> Unit) {
    val satellite = state.satellite
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            satellite?.classType?.let {
                SatelliteGlyph(
                    kind = it.glyph,
                    tint = it.accent(),
                    size = 26.dp,
                    modifier = Modifier.padding(end = Spacing.md),
                )
            }
            Text(
                text = satellite?.name ?: "Object ${state.noradId}",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            satellite?.classType?.let { AccentChip(label = it.label, accent = it.accent()) }
            NumericText(
                text = "#${state.noradId}",
                size = NumericSize.Small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The launch *year*, from the international designator. `launchDate` is a full date
            // that no catalog we read publishes, so it was always blank here.
            satellite?.launchYear?.let {
                Text(
                    text = "launched $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SatRideButton(
            text = if (state.isFollowed) "Following" else "Follow",
            onClick = { onEvent(SatelliteDetailEvent.FollowToggled) },
            style = if (state.isFollowed) SatRideButtonStyle.Primary else SatRideButtonStyle.Secondary,
            modifier = Modifier.padding(top = Spacing.md),
        )
    }
}

@Composable
private fun LiveBlock(state: SatelliteDetailUiState) {
    val snapshot = state.snapshot
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Right now", style = MaterialTheme.typography.titleSmall)
            when {
                snapshot == null -> AccentChip(label = "no fix", accent = SatRideTheme.accents.trackingDim)
                state.isUp -> AccentChip(label = "above horizon", accent = SatRideTheme.accents.success)
                else -> AccentChip(label = "below horizon", accent = SatRideTheme.accents.trackingDim)
            }
        }

        if (snapshot == null) {
            Text(
                text = "Waiting for a position. SatRide needs a rough location to say where this is " +
                    "from where you are.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            return@GlassPanel
        }

        Column(
            modifier = Modifier.padding(top = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            LiveRow("Elevation", "${snapshot.topo.elevationDeg.format(1)}°")
            LiveRow(
                "Azimuth",
                "${snapshot.topo.azimuthDeg.format(1)}° ${snapshot.topo.azimuthDeg.asCompassPoint()}",
            )
            LiveRow("Range", "${snapshot.topo.rangeKm.format(0)} km")
            LiveRow("Speed", "${snapshot.speedKmS.format(2)} km/s")
            LiveRow("Altitude", "${(snapshot.subPoint.altM / 1000.0).format(0)} km")
            LiveRow("Footprint", "${snapshot.footprintRadiusKm.format(0)} km radius")
        }

        // FlowRow, not Row: both chips together are wider than a phone, and a plain row pushes
        // the second one off the edge rather than wrapping it. Visible in the AR detail sheet,
        // where "elements 0d old" sat half outside the screen behind the sunlit chip.
        FlowRow(
            modifier = Modifier.padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (snapshot.sunlit) {
                AccentChip(label = "Sunlit — may be visible", accent = SatRideTheme.accents.warning)
            }
            // Per-satellite element age, which is what actually governs accuracy for the object in
            // front of the user. The catalog-wide banner reports the last refresh instead.
            state.elementAge?.let { age ->
                AccentChip(
                    label = "elements ${age.inWholeDays}d old",
                    accent = if (age.inWholeDays >=
                        7
                    ) {
                        SatRideTheme.accents.danger
                    } else {
                        SatRideTheme.accents.trackingDim
                    },
                )
            }
        }
    }
}

@Composable
private fun LiveRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NumericText(text = value, size = NumericSize.Medium, color = SatRideTheme.accents.tracking)
    }
}

@Composable
private fun NextPassCard(state: SatelliteDetailUiState, onEvent: (SatelliteDetailEvent) -> Unit) {
    val pass = state.nextPass
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = pass != null) { onEvent(SatelliteDetailEvent.NextPassClicked) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (pass?.isAlwaysVisible ==
                    true
                ) {
                    "Visibility"
                } else {
                    "Next pass"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            pass?.quality?.let { AccentChip(label = it.label, accent = it.accent()) }
        }

        // A geostationary satellite never sets, so a pass search hands back one "pass" covering
        // the whole window. Showing that as a fourteen-thousand-minute pass starting now would be
        // technically true and completely useless.
        if (pass != null && pass.isAlwaysVisible) {
            Text(
                text = "Always above your horizon — this satellite holds station in the sky, so " +
                    "there is nothing to wait for.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            return@GlassPanel
        }

        if (pass == null) {
            Text(
                text = "No pass in the next ten days from your location.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            return@GlassPanel
        }

        Column(
            modifier = Modifier.padding(top = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            LiveRow("Starts in", state.nextPassIn.asCountdown() ?: "now")
            LiveRow("Max elevation", "${pass.maxElevationDeg.format(0)}°")
            LiveRow("Duration", "${pass.duration.inWholeMinutes} min")
            LiveRow(
                "Rises / sets",
                "${pass.aosAzimuthDeg.asCompassPoint()} → ${pass.losAzimuthDeg.asCompassPoint()}",
            )
        }
    }
}

/**
 * The ISS gets a block of its own because it is why most people open a satellite tracker, and
 * because its SSTV weekends are the single most rewarding thing a beginner can receive.
 */
@Composable
private fun IssBlock(state: SatelliteDetailUiState) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Text("International Space Station", style = MaterialTheme.typography.titleSmall)

        val event = state.issEvent
        if (event != null) {
            AccentChip(
                label = "Live now: ${event.title}",
                accent = SatRideTheme.accents.artifact,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            if (event.description.isNotBlank()) {
                Text(
                    text = event.description,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            event.frequencyHz?.let {
                NumericText(
                    text = "${it.asMegahertz()} MHz ${event.mode}",
                    size = NumericSize.Small,
                    color = SatRideTheme.accents.radio,
                )
            }
        } else {
            Text(
                text = "ARISS runs slow-scan television weekends and school contacts from the " +
                    "station a few times a year. When one is running, it shows up here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun TransmitterCard(transmitter: TransmitterRowUi, radioAvailable: Boolean, onTune: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                NumericText(
                    text = "${transmitter.downlinkHz.asMegahertz()} MHz",
                    size = NumericSize.Large,
                    color = SatRideTheme.accents.radio,
                )
                Text(
                    text = transmitter.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    AccentChip(
                        label = transmitter.modeLabel,
                        accent = if (transmitter.isDecodable) {
                            SatRideTheme.accents.artifact
                        } else {
                            SatRideTheme.accents.trackingDim
                        },
                    )
                    transmitter.baud?.let {
                        AccentChip(label = "$it baud", accent = SatRideTheme.accents.trackingDim)
                    }
                    if (transmitter.isInverting) {
                        AccentChip(label = "inverting", accent = SatRideTheme.accents.trackingDim)
                    }
                }
            }
            // Enabled even before the radio exists. A disabled control on a phone is a dead
            // control — there is no hover, so there is nowhere to put the explanation. Tapping it
            // says when tuning arrives, which is both more useful and more discoverable than
            // greying it out.
            SatRideButton(
                text = if (radioAvailable) "Tune" else "Tune…",
                onClick = onTune,
                style = SatRideButtonStyle.Secondary,
                accent = if (radioAvailable) SatRideTheme.accents.radio else SatRideTheme.accents.radioDim,
            )
        }
    }
}
