package com.developerstring.satride.feature.play

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.designsystem.component.SatRideIcon
import com.developerstring.satride.core.designsystem.component.SatRideIconGraphic
import com.developerstring.satride.core.designsystem.component.ScreenReveal
import com.developerstring.satride.core.designsystem.haptics.LocalSatRideHaptics
import com.developerstring.satride.core.designsystem.theme.MotionSpec
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.feature.play.render.GlobeCamera
import com.developerstring.satride.feature.play.render.GlobePalette
import com.developerstring.satride.feature.play.render.GlobeRenderer
import com.developerstring.satride.feature.play.solar.Body
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Bottom space reserved so the Play screen's controls clear the floating nav bar: the bar's pill
 * height plus its bottom margin. Added on top of the system navigation-bar inset.
 */
// The floating tab bar overlays this screen; the shared clearance is what keeps the globe
// controls out from under it.
private val NavBarClearance = Spacing.navBarClearance

/**
 * Play: a 3D Earth with the live constellation around it.
 *
 * Drag orbits the camera, pinch flies in and out, a tap selects a satellite, and Ride puts the
 * camera on that satellite's shoulder as it circles the planet — every position computed by the
 * same SGP4 engine the rest of the app tracks with, so the view *is* where the satellites are.
 */
@Composable
fun PlayScreen(
    onOpenDetail: (Int) -> Unit,
    onOpenMultiPlay: () -> Unit = {},
    onOpenSolarSystem: () -> Unit = {},
    viewModel: PlayViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The view model outlives this composition (tab state is restored, which is what makes coming
    // back to Play instant), so it has to be told when nobody is looking — otherwise its
    // propagation and HUD loops keep running behind every other tab for the rest of the session.
    DisposableEffect(viewModel) {
        viewModel.onVisibilityChanged(true)
        onDispose { viewModel.onVisibilityChanged(false) }
    }

    // The globe is a tessellated mesh, a texture upload and a frame loop, all of which would
    // otherwise be due on the frame the tab swap draws. `ScreenReveal` defers them by one frame
    // behind a plain space-black shell — which is what the globe sits on anyway, so the reveal is
    // invisible except as the absence of a stall.
    ScreenReveal(
        modifier = Modifier.fillMaxSize().background(SpaceBlack),
        skeleton = { Box(Modifier.fillMaxSize().background(SpaceBlack)) },
    ) {
        GlobeSurface(
            viewModel = viewModel,
            state = state,
            modifier = Modifier.fillMaxSize(),
        )

        // Top chrome: constellation filter chips and the catalog count.
        Column(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlayGroup.entries.forEach { group ->
                    PlayChip(
                        label = group.label,
                        selected = state.group == group,
                        onClick = { viewModel.onEvent(PlayUiEvent.SetGroup(group)) },
                    )
                }
            }
            if (!state.loading && state.satelliteCount > 0) {
                // The count is the field, not the selection, and the two differ when a satellite
                // has no usable element set. Saying "3,412 of 3,500 tracked" is the honest version
                // of a number that would otherwise look like a quiet loss of satellites.
                Text(
                    text = buildString {
                        append(state.satelliteCount.grouped())
                        append(" objects in orbit")
                        if (state.selectionCount > state.satelliteCount) {
                            append(" · ")
                            append(state.selectionCount.grouped())
                            append(" tracked")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End).padding(end = 16.dp, top = 6.dp),
                )
            }
            // The door to the arena. Chunky and sunshine-yellow, because it is the one control on
            // this screen that starts a *game* rather than adjusting the view.
            MultiPlayButton(
                onClick = onOpenMultiPlay,
                modifier = Modifier.align(Alignment.End).padding(end = 12.dp, top = 8.dp),
            )
            // The other game: a starship ride through the real solar system, same landscape arena.
            SolarSystemButton(
                onClick = onOpenSolarSystem,
                modifier = Modifier.align(Alignment.End).padding(end = 12.dp, top = 8.dp),
            )
        }

        // Bottom chrome: satellite card, hint, and the time gears. Lifted clear of the floating
        // nav bar (which overlays full-screen content) — the bar's pill + its bottom margin, on top
        // of the system gesture inset, so the mode/time controls are never hidden behind the glass.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = NavBarClearance),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val selected = state.selected
            if (selected != null) {
                // Whether the readout is showing. Ride mode folds it away by itself — the point of
                // riding is the view, and a card covering a third of it defeats that — and letting
                // go of the ride brings it back. Keyed on the satellite so picking a different one
                // always arrives expanded.
                var detailsExpanded by remember(selected.noradId) { mutableStateOf(!state.riding) }
                LaunchedEffect(state.riding) { detailsExpanded = !state.riding }

                SatelliteCard(
                    selected = selected,
                    riding = state.riding,
                    expanded = detailsExpanded,
                    onRide = { viewModel.onEvent(PlayUiEvent.ToggleRide) },
                    onDetail = { onOpenDetail(selected.noradId) },
                    onDismiss = {
                        // While riding, dismissing the card means putting it away, not dropping the
                        // satellite — ejecting mid-flight is never what the close button meant here,
                        // and it was the one control on the card that did nothing useful in ride.
                        if (state.riding) detailsExpanded = false else viewModel.onEvent(PlayUiEvent.Select(0))
                    },
                    onExpand = { detailsExpanded = true },
                )
            } else if (!state.loading && !state.emptyCatalog) {
                Text(
                    text = "Tap a satellite · drag to orbit · pinch to fly",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            // Mode first, gears second and only in Play. The gears are meaningless in Real — there
            // is one speed there and it is the real one — and showing them greyed out would invite
            // the question of why.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayMode.entries.forEach { mode ->
                    PlayChip(
                        label = mode.label,
                        selected = state.mode == mode,
                        onClick = { viewModel.onEvent(PlayUiEvent.SetMode(mode)) },
                    )
                }
            }
            if (state.showTimeControls) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeScale.entries.forEach { scale ->
                        PlayChip(
                            label = scale.label,
                            selected = state.timeScale == scale,
                            onClick = { viewModel.onEvent(PlayUiEvent.SetTimeScale(scale)) },
                        )
                    }
                }
            }
        }

        if (state.loading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = "Preparing the constellation…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (state.emptyCatalog) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "No orbital elements yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Refresh the catalog once and the sky fills in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlayChip(label = "Retry", selected = true, onClick = { viewModel.onEvent(PlayUiEvent.Retry) })
            }
        }
    }
}

/** The canvas: frame loop, gestures, and the renderer itself. */
@Composable
private fun GlobeSurface(viewModel: PlayViewModel, state: PlayUiState, modifier: Modifier = Modifier) {
    val renderer = remember { GlobeRenderer() }
    val camera = remember {
        GlobeCamera().apply {
            // Open facing the sunlit hemisphere: the subsolar point in Earth-fixed longitude.
            val now = viewModel.gameClock.now()
            val sun = com.developerstring.satride.core.orbit.frames.Frames.sunPositionTeme(now).normalized()
            val gmst = com.developerstring.satride.core.orbit.time.AstroTime.gmstRadians(now)
            val sunLon = kotlin.math.atan2(
                -kotlin.math.sin(gmst) * sun.x + kotlin.math.cos(gmst) * sun.y,
                kotlin.math.cos(gmst) * sun.x + kotlin.math.sin(gmst) * sun.y,
            ) * 180.0 / kotlin.math.PI
            orientTo(yawDegrees = sunLon, pitchDegrees = 16.0)
        }
    }
    val riding by rememberUpdatedState(state.riding)

    val accents = SatRideTheme.accents
    val palette = remember(accents) {
        GlobePalette(
            station = accents.tracking,
            weather = accents.radio,
            amateur = accents.success,
            navigation = accents.artifact,
            cubesat = Color(0xFF8FA8FF),
            other = Color(0xFF9BB0CC),
            selection = Color(0xF0FFFFFF),
        )
    }

    // The selected name, measured once per selection change, drawn as a bubble by the renderer.
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge.copy(
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
    )
    val selectedLabel = remember(state.selected?.name, labelStyle) {
        state.selected?.name?.let { textMeasurer.measure(AnnotatedString(it), labelStyle) }
    }
    // The Sun, Moon and planets sit in the sky at their real directions; their names are measured once.
    val skyStyle = MaterialTheme.typography.labelSmall.copy(color = Color.White.copy(alpha = 0.75f))
    val skyLabels = remember(skyStyle) {
        Body.entries.filter { it != Body.EARTH }.associate {
            it.id to
                textMeasurer.measure(AnnotatedString(it.label), skyStyle)
        }
    }

    // Created once: with an unspecified center the gradient resolves (and caches its shader)
    // against the draw size, so this costs one fill per frame, not a shader rebuild per frame.
    val spaceBrush = remember {
        Brush.radialGradient(
            0f to Color(0xFF16244E),
            0.62f to Color(0xFF0A122E),
            1f to Color(0xFF060B1E),
        )
    }

    // The frame clock: advances fling inertia and invalidates the draw. Nothing else in the
    // screen recomposes for it — only the canvas redraws.
    val frameTick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { nanos ->
                if (lastNanos != 0L) camera.settle((nanos - lastNanos) / 1e9f)
                lastNanos = nanos
                frameTick.longValue = nanos
            }
        }
    }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown()
                camera.stopFling()
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var pointerCount = 1
                var previousCentroid = down.position
                var previousSpan = 0f
                var moved = false
                var lastPosition = down.position

                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        if (!moved) {
                            val id = renderer.pickNearest(lastPosition.x, lastPosition.y, 32.dp.toPx())
                            viewModel.onEvent(PlayUiEvent.Select(id))
                        } else if (pointerCount == 1) {
                            val velocity = tracker.calculateVelocity()
                            camera.fling(velocity.x, velocity.y, size.width.toFloat(), riding)
                        }
                        break
                    }

                    var centroidX = 0f
                    var centroidY = 0f
                    pressed.forEach { change ->
                        centroidX += change.position.x
                        centroidY += change.position.y
                    }
                    val centroid = Offset(centroidX / pressed.size, centroidY / pressed.size)

                    if (pressed.size != pointerCount) {
                        // A finger landed or lifted: the centroid jumps, so re-anchor rather
                        // than reading the jump as a violent pan.
                        previousCentroid = centroid
                        previousSpan = 0f
                        pointerCount = pressed.size
                    }

                    if (pressed.size >= 2) {
                        val span = hypot(
                            pressed[0].position.x - pressed[1].position.x,
                            pressed[0].position.y - pressed[1].position.y,
                        )
                        if (previousSpan > 8f && span > 8f) camera.zoom(span / previousSpan, riding)
                        previousSpan = span
                    }

                    val pan = centroid - previousCentroid
                    if (!moved && hypot(pan.x, pan.y) > slop) moved = true
                    if (moved && (abs(pan.x) > 0f || abs(pan.y) > 0f)) {
                        camera.drag(pan.x, pan.y, size.width.toFloat(), riding)
                    }
                    previousCentroid = centroid

                    if (pressed.size == 1) {
                        tracker.addPosition(pressed[0].uptimeMillis, pressed[0].position)
                        lastPosition = pressed[0].position
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        },
    ) {
        val nanos = frameTick.longValue // Subscribes this draw to the frame clock.

        // Deep-space backdrop: a soft indigo glow pooled behind the planet, not flat black.
        drawRect(spaceBrush)

        renderer.setTextures(viewModel.dayTexture, viewModel.nightTexture)
        renderer.vectorEarth = viewModel.vectorEarth
        renderer.cityLights = viewModel.cityLights
        renderer.orbitRing = viewModel.orbitRing
        renderer.skyLabels = skyLabels
        renderer.render(
            scope = this,
            field = viewModel.field,
            simTime = viewModel.gameClock.now(),
            group = state.group,
            selectedNoradId = state.selected?.noradId ?: 0,
            riding = state.riding,
            camera = camera,
            palette = palette,
            frameNanos = nanos,
            selectedLabel = selectedLabel,
        )
    }
}

@Composable
private fun SatelliteCard(
    selected: SelectedSatellite,
    riding: Boolean,
    expanded: Boolean,
    onRide: () -> Unit,
    onDetail: () -> Unit,
    onDismiss: () -> Unit,
    onExpand: () -> Unit,
) {
    // One card that grows and shrinks, not two cards that swap. `AnimatedContent` with a
    // `SizeTransform` animates the container between the two layouts' measured sizes, so the
    // surface visibly stretches open and folds shut while its contents cross-fade inside it.
    // `clip = false` lets the outgoing content spill past the shrinking bounds for the few frames
    // it is still visible, which is what stops the fold reading as a hard crop.
    AnimatedContent(
        targetState = expanded,
        transitionSpec = {
            val enter = fadeIn(MotionSpec.standard()) + scaleIn(MotionSpec.standard(), initialScale = 0.94f)
            val exit = fadeOut(MotionSpec.fast()) + scaleOut(MotionSpec.fast(), targetScale = 0.94f)
            enter togetherWith exit using SizeTransform(clip = false) { _, _ -> MotionSpec.standard() }
        },
        label = "satelliteCard",
    ) { isExpanded ->
        if (isExpanded) {
            ExpandedSatelliteCard(
                selected = selected,
                riding = riding,
                onRide = onRide,
                onDetail = onDetail,
                onDismiss = onDismiss,
            )
        } else {
            CollapsedSatelliteCard(selected = selected, onExpand = onExpand)
        }
    }
}

/**
 * The ride-mode handle: the satellite's name and a way back to the readout, and nothing else.
 *
 * Wraps its content rather than filling the width, so what is left on screen during a ride is a
 * small tab rather than a full-width bar with one line of text in it. The whole tab is the target;
 * the chevron says which way it opens rather than being the only thing that can be hit.
 */
@Composable
private fun CollapsedSatelliteCard(selected: SelectedSatellite, onExpand: () -> Unit) {
    val accents = SatRideTheme.accents
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(1.5.dp, accents.hairline, shape)
            .clickable(onClick = onExpand)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = "Show details for " + selected.name },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = selected.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        // The chevron points the way the card will move: up, to open.
        SatRideIconGraphic(
            icon = SatRideIcon.Chevron,
            size = 18.dp,
            tint = accents.tracking,
            modifier = Modifier.graphicsLayer { rotationZ = 180f },
        )
    }
}

/** The full readout: name, live figures, and the ride and detail actions. */
@Composable
private fun ExpandedSatelliteCard(
    selected: SelectedSatellite,
    riding: Boolean,
    onRide: () -> Unit,
    onDetail: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accents = SatRideTheme.accents
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(1.5.dp, accents.hairline, RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selected.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = selected.classType.playLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = accents.tracking,
                )
            }
            // While riding this folds the card away; otherwise it drops the selection. Same
            // position, same gesture, and in both cases it means "I am done with this panel" —
            // what differs is that during a ride the satellite is not the panel. Previously it
            // cleared the selection in both, which the ride explicitly ignores, so it was the one
            // control on the card that did nothing at all.
            if (riding) {
                SatRideIconGraphic(
                    icon = SatRideIcon.Chevron,
                    size = 20.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(8.dp)
                        .semantics { contentDescription = "Hide details" },
                )
            } else {
                Text(
                    text = "✕",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(8.dp)
                        .semantics { contentDescription = "Clear selection" },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("ALT", "${selected.altitudeKm.roundToInt()} km")
            Stat("SPEED", "${oneDecimal(selected.speedKmS)} km/s")
            Stat("PERIOD", "${selected.periodMin.roundToInt()} min")
            Stat("OVER", formatSubPoint(selected.latDeg, selected.lonDeg))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayChip(
                label = if (riding) "Exit ride" else "Ride this satellite",
                selected = true,
                onClick = onRide,
            )
            PlayChip(label = "Details", selected = false, onClick = onDetail)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * The Multi-Play entry: a pressable pill with a tiny craft glyph, sized for a thumb.
 *
 * Deliberately not a `PlayChip`: those pick a *setting*, and this opens a whole other screen in
 * landscape. It borrows the design system's 3D-press button so it reads as the same family of
 * control the onboarding uses.
 */
@Composable
private fun MultiPlayButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accents = SatRideTheme.accents
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        com.developerstring.satride.core.designsystem.component.SatRideButton(
            text = "🚀  Multi-Play",
            onClick = onClick,
            accent = accents.sunshine,
        )
    }
}

@Composable
private fun SolarSystemButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    com.developerstring.satride.core.designsystem.component.SatRideButton(
        text = "🪐  Solar System",
        onClick = onClick,
        accent = SatRideTheme.accents.lavender,
        modifier = modifier,
    )
}

@Composable
private fun PlayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accents = SatRideTheme.accents
    val haptics = LocalSatRideHaptics.current
    // Selected chips fill solid in the illustrated style: game controls read as pressed toys,
    // not tinted glass.
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) accents.tracking else MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
            )
            .border(
                width = 1.5.dp,
                color = if (selected) accents.tracking else accents.hairline,
                shape = CircleShape,
            )
            .clickable {
                if (!selected) haptics.tap()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) accents.shadow else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun SatClass.playLabel(): String = when (this) {
    SatClass.CREWED -> "Station · Crewed"
    SatClass.WEATHER -> "Weather"
    SatClass.AMATEUR -> "Amateur radio"
    SatClass.CUBESAT -> "CubeSat"
    SatClass.NAVIGATION -> "Navigation"
    SatClass.ROCKET_BODY -> "Rocket body"
    SatClass.OTHER -> "Satellite"
}

private fun oneDecimal(value: Double): String {
    val scaled = (value * 10).roundToInt()
    return "${scaled / 10}.${abs(scaled % 10)}"
}

private fun formatSubPoint(latDeg: Double, lonDeg: Double): String {
    val ns = if (latDeg >= 0) "N" else "S"
    val ew = if (lonDeg >= 0) "E" else "W"
    return "${abs(latDeg).roundToInt()}°$ns ${abs(lonDeg).roundToInt()}°$ew"
}

/** Deep space indigo — the vignette in the canvas fades down to exactly this. */
private val SpaceBlack = Color(0xFF060B1E)

/**
 * Thousands separators, without a platform formatter.
 *
 * Kotlin/Multiplatform has no common number formatting and the globe's count runs to five digits
 * once the whole catalog is tracked — "28412" reads as noise where "28,412" reads as a number.
 */
private fun Int.grouped(): String {
    val digits = toString()
    if (digits.length <= 4) return digits
    return digits.reversed().chunked(3).joinToString(",").reversed()
}
