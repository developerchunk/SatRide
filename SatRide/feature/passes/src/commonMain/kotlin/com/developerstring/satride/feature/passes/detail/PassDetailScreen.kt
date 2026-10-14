package com.developerstring.satride.feature.passes.detail

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.common.asCompassPoint
import com.developerstring.satride.core.common.asMegahertz
import com.developerstring.satride.core.common.format
import com.developerstring.satride.core.designsystem.art.KurzPlanet
import com.developerstring.satride.core.designsystem.art.floating
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.NumericSize
import com.developerstring.satride.core.designsystem.component.NumericText
import com.developerstring.satride.core.designsystem.component.PolarPassPlot
import com.developerstring.satride.core.designsystem.component.PolarPlotStyle
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.component.SatelliteGlyph
import com.developerstring.satride.core.designsystem.component.ScreenReveal
import com.developerstring.satride.core.designsystem.component.SectionHeader
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.core.permissions.rememberNotificationPermission
import com.developerstring.satride.feature.passes.accent
import com.developerstring.satride.feature.passes.asClockTime
import com.developerstring.satride.feature.passes.asCountdown
import com.developerstring.satride.feature.passes.asPassLength
import com.developerstring.satride.feature.passes.glyph
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * One pass, in full: the arc, the elevation curve, the Doppler preview, and the two things a
 * person actually does about a pass — be reminded of it, or go and look at it.
 */
@Composable
fun PassDetailScreen(
    noradId: Int,
    aosEpochMillis: Long,
    modifier: Modifier = Modifier,
    viewModel: PassDetailViewModel = koinViewModel { parametersOf(noradId, aosEpochMillis) },
    onOpenSky: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val notifications = rememberNotificationPermission()
    var awaitingPermission by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PassDetailEffect.OpenSky -> onOpenSky()

                is PassDetailEffect.ShowMessage -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(effect.text)
                }

                PassDetailEffect.RequestNotificationPermission -> {
                    awaitingPermission = true
                    notifications.request()
                }
            }
        }
    }

    LaunchedEffect(notifications.status) {
        if (awaitingPermission && notifications.status.settled) {
            awaitingPermission = false
            viewModel.onNotificationPermissionResult(notifications.status.isGranted)
        }
    }

    // Shell on the navigation frame, the polar plot and Doppler curve on the next; see
    // `ScreenReveal`.
    ScreenReveal(modifier.fillMaxSize()) {
        PassDetailContent(state = state, onEvent = viewModel::onEvent)
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun PassDetailContent(
    state: PassDetailUiState,
    onEvent: (PassDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
    zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    if (state.loading) {
        Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (state.notFound || state.pass == null) {
        PassGoneState(state, modifier)
        return
    }

    val pass = state.pass

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            GlassPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SatelliteGlyph(
                        kind = state.classType.glyph,
                        tint = state.classType.accent(),
                        size = 24.dp,
                        modifier = Modifier.padding(end = Spacing.md),
                    )
                    Text(
                        text = state.satelliteName,
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
                    AccentChip(label = state.grade.label, accent = state.grade.accent())
                    if (state.inProgress) {
                        AccentChip(label = "Up now", accent = SatRideTheme.accents.success)
                    } else {
                        NumericText(text = "in ${state.startsIn.asCountdown()}", size = NumericSize.Small)
                    }
                }
            }
        }

        item {
            GlassPanel(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth(), Alignment.Center) {
                    PolarPassPlot(
                        arc = state.arc,
                        style = PolarPlotStyle.FULL,
                        live = state.live,
                        accent = state.grade.accent(),
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Stat("Rises", pass.aos.asClockTime(zone), pass.aosAzimuthDeg.asCompassPoint())
                    Stat("Peak", "${pass.maxElevationDeg.format(0)}°", pass.tca.asClockTime(zone))
                    Stat("Sets", pass.los.asClockTime(zone), pass.losAzimuthDeg.asCompassPoint())
                    Stat("For", pass.duration.asPassLength(), "")
                }
            }
        }

        item {
            GlassPanel(Modifier.fillMaxWidth()) {
                Text("Elevation", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Height above the horizon, AOS to LOS.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                CurveChart(
                    points = state.elevationCurve,
                    minValue = 0f,
                    maxValue = 90f,
                    accent = SatRideTheme.accents.tracking,
                    baselineAt = 0f,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }

        if (state.dopplerCurve.isNotEmpty()) {
            item { DopplerCard(state) }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (state.alertsSupported) {
                    SatRideButton(
                        text = if (state.hasAlert) "Reminding" else "Remind me",
                        onClick = { onEvent(PassDetailEvent.RemindToggled) },
                        style = if (state.hasAlert) SatRideButtonStyle.Primary else SatRideButtonStyle.Secondary,
                    )
                }
                SatRideButton(
                    text = "Open in Sky",
                    onClick = { onEvent(PassDetailEvent.OpenInSkyClicked) },
                )
            }
        }

        if (state.downlinks.isNotEmpty()) {
            item { SectionHeader(title = "Downlinks") }
            items(state.downlinks, key = { it.id }) { downlink ->
                GlassPanel(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = downlink.description,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = downlink.modeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        NumericText(
                            text = "${downlink.downlinkHz.asMegahertz()} MHz",
                            size = NumericSize.Medium,
                            color = if (downlink.isActive) {
                                SatRideTheme.accents.radio
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The Doppler preview.
 *
 * Shown as a shape with one number rather than as a full chart with axes: what the user needs to
 * take from it is "this pass swings ±10 kHz and most of it happens in the ninety seconds around
 * culmination", and the S-curve says that at a glance. P9 turns this into the live correction.
 */
@Composable
private fun DopplerCard(state: PassDetailUiState) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Doppler", style = MaterialTheme.typography.titleSmall)
                state.dopplerTransmitter?.let {
                    Text(
                        text = "${it.downlinkHz.asMegahertz()} MHz · ${it.modeLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            NumericText(
                text = "±${(state.dopplerPeakHz / 1000.0).format(1)} kHz",
                size = NumericSize.Medium,
                color = SatRideTheme.accents.radio,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        CurveChart(
            points = state.dopplerCurve,
            minValue = -1f,
            maxValue = 1f,
            accent = SatRideTheme.accents.radio,
            baselineAt = 0f,
            modifier = Modifier.fillMaxWidth().height(80.dp),
        )
    }
}

/**
 * A curve against pass time.
 *
 * One composable for both charts, because they are the same drawing with a different range: a
 * polyline over a normalised x, with a baseline where zero falls. Elevation is clamped to 0–90 and
 * Doppler to ±1 of its own peak, so neither chart has to auto-scale — the shape is the message and
 * an axis that rescales itself makes two passes impossible to compare by eye.
 */
@Composable
private fun CurveChart(
    points: List<CurvePoint>,
    minValue: Float,
    maxValue: Float,
    accent: Color,
    baselineAt: Float,
    modifier: Modifier = Modifier,
) {
    val hairline = SatRideTheme.accents.hairline
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val range = (maxValue - minValue).takeIf { it != 0f } ?: return@Canvas

        fun y(value: Float): Float = size.height * (1f - (value.coerceIn(minValue, maxValue) - minValue) / range)

        drawLine(
            color = hairline,
            start = androidx.compose.ui.geometry.Offset(0f, y(baselineAt)),
            end = androidx.compose.ui.geometry.Offset(size.width, y(baselineAt)),
            strokeWidth = 1f,
        )

        val path = Path()
        points.forEachIndexed { index, point ->
            val x = point.fraction * size.width
            val yy = y(point.value)
            if (index == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        drawPath(path = path, color = accent, style = Stroke(width = size.height * 0.025f))
    }
}

@Composable
private fun Stat(label: String, value: String, detail: String) {
    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NumericText(text = value, size = NumericSize.Medium)
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The pass is not there any more.
 *
 * A real outcome, not an error: element sets are refreshed daily, and a marginal pass can fall
 * below the user's minimum elevation on the new ones. Saying so is better than an empty screen
 * that looks like a failed load.
 */
@Composable
private fun PassGoneState(state: PassDetailUiState, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(Spacing.xl), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KurzPlanet(
                Modifier
                    .size(120.dp)
                    .floating(amplitude = 6.dp),
            )
            Spacer(Modifier.height(Spacing.lg))
            Text("That pass is gone", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "${state.satelliteName}'s orbit was refreshed and this pass is no longer " +
                    "predicted. The timeline has the current ones.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
