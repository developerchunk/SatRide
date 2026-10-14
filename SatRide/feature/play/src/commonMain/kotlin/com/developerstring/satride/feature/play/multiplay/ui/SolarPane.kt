package com.developerstring.satride.feature.play.multiplay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.haptics.LocalSatRideHaptics
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.feature.play.GlobeAssets
import com.developerstring.satride.feature.play.multiplay.Emotes
import com.developerstring.satride.feature.play.multiplay.MultiPlayUiState
import com.developerstring.satride.feature.play.multiplay.MultiPlayViewModel
import com.developerstring.satride.feature.play.multiplay.SolarHud
import com.developerstring.satride.feature.play.multiplay.engine.GamePhase
import com.developerstring.satride.feature.play.multiplay.engine.Standing
import com.developerstring.satride.feature.play.multiplay.solar.PilotMode
import com.developerstring.satride.feature.play.multiplay.solar.PilotPhase
import com.developerstring.satride.feature.play.multiplay.solar.SolarRenderer
import com.developerstring.satride.feature.play.render.GlobeRenderer
import com.developerstring.satride.feature.play.solar.Body
import com.developerstring.satride.feature.play.solar.BodyKind
import org.koin.compose.koinInject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * The Solar System arena: a starship, the real planets, and a dial for the speed of light.
 *
 * Same landscape, full-screen layout as the race: stick under the left thumb, buttons under the
 * right. What changes is what the right hand does — BRAKE, AIM (auto direction) and AUTO
 * (autopilot) — and the console in the bottom centre, which is the speed-of-light slider. The
 * destinations run along the top so a trip is two taps: pick a world, press AUTO.
 */
@Composable
internal fun SolarPane(state: MultiPlayUiState, viewModel: MultiPlayViewModel) {
    val accents = SatRideTheme.accents
    val playerColor = Color(state.colorArgb)
    val hud = state.solarHud

    Box(Modifier.fillMaxSize().background(ArenaBlack)) {
        SolarCanvas(state = state, viewModel = viewModel, playerColor = playerColor, modifier = Modifier.fillMaxSize())

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            ExpeditionCluster(hud, Modifier.align(Alignment.TopStart).padding(12.dp))

            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp).widthIn(max = 560.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DestinationBar(hud, viewModel)
                SolarClock(hud, state.isRoomGame)
                hud.target?.let { target ->
                    Text(
                        text = target.fact,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
                state.toasts.forEach { toast ->
                    val positive = toast.points >= 0
                    Text(
                        text = if (toast.points != 0) {
                            "${toast.text}  ${if (positive) "+" else ""}${toast.points}"
                        } else {
                            toast.text
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (positive) accents.sunshine else accents.coral,
                    )
                }
            }

            PilotStandings(state.standings, hud, Modifier.align(Alignment.TopEnd).padding(12.dp))

            Column(
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RailButton(if (state.canPause) "❚❚" else "✕", if (state.canPause) "Pause" else "Leave") {
                    if (state.canPause) viewModel.setPaused(true) else viewModel.quitToHome()
                }
                RailButton("?", "Help") { viewModel.openHowToPlay() }
                RailButton("◎", "Centre") { viewModel.recentreLook() }
                RailButton("☺", "Emote") { viewModel.toggleEmotes() }
            }
            if (state.showEmotes) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 64.dp)
                        .clip(CircleShape)
                        .background(PanelFill)
                        .border(1.dp, PanelStroke, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Emotes.forEach { e ->
                        Text(
                            e,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.clip(CircleShape).clickable { viewModel.sendEmote(e) }.padding(4.dp),
                        )
                    }
                }
            }

            VirtualStick(
                onChange = { x, y -> viewModel.setStick(x, y) },
                accent = playerColor,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 10.dp),
            )

            WarpConsole(hud, viewModel, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))

            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SolarMap(hud, playerColor, Modifier.size(112.dp))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HoldButton(
                        label = "BRAKE",
                        accent = accents.lavender,
                        onPressChange = { viewModel.setBrake(it) },
                        size = 64.dp,
                    )
                    PilotButton(
                        label = "AIM",
                        hint = "auto direction",
                        accent = accents.artifact,
                        active = hud.pilotMode == PilotMode.AIM,
                        size = 78.dp,
                    ) {
                        viewModel.setPilotMode(if (hud.pilotMode == PilotMode.AIM) PilotMode.MANUAL else PilotMode.AIM)
                    }
                    PilotButton(
                        label = "AUTO",
                        hint = if (hud.pilotMode == PilotMode.AUTO) "pilot flying" else "autopilot",
                        accent = accents.sunshine,
                        active = hud.pilotMode == PilotMode.AUTO,
                        size = 96.dp,
                    ) {
                        viewModel.setPilotMode(
                            if (hud.pilotMode ==
                                PilotMode.AUTO
                            ) {
                                PilotMode.MANUAL
                            } else {
                                PilotMode.AUTO
                            },
                        )
                    }
                }
            }

            SolarPhaseOverlay(hud, state)
            // Not while the rules are up; the expedition is already held underneath them.
            if (state.paused && !state.showHowToPlay) SolarPauseMenu(state, viewModel)
        }
    }
}

/** The canvas: frame loop, look gestures, keyboard, the renderer. */
@Composable
private fun SolarCanvas(
    state: MultiPlayUiState,
    viewModel: MultiPlayViewModel,
    playerColor: Color,
    modifier: Modifier,
) {
    val globeRenderer = remember { GlobeRenderer() }
    val renderer = remember { SolarRenderer(globeRenderer) }
    val assets: GlobeAssets = koinInject()
    val hud = state.solarHud

    val textMeasurer = rememberTextMeasurer()
    val tagStyle = MaterialTheme.typography.labelSmall.copy(color = Color.White, fontWeight = FontWeight.Bold)
    val bodyStyle = MaterialTheme.typography.labelSmall.copy(
        color = Color.White.copy(alpha = 0.8f),
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
    val emoteStyle = MaterialTheme.typography.titleLarge.copy(color = Color.White)
    val labels: Map<String, TextLayoutResult> = remember(state.standings.map { it.uid to it.name }, tagStyle) {
        buildMap {
            state.standings.forEach { s -> put(s.uid, textMeasurer.measure(AnnotatedString(s.name), tagStyle)) }
            Body.entries.forEach { b ->
                put(b.id, textMeasurer.measure(AnnotatedString(b.label.uppercase()), bodyStyle))
            }
        }
    }
    val emoteLabels = remember(emoteStyle) {
        Emotes.associateWith { textMeasurer.measure(AnnotatedString(it), emoteStyle) }
    }
    val targetLabel = remember(hud.target, hud.distanceText, hud.etaText, tagStyle) {
        hud.target?.let { t ->
            val eta = if (hud.etaText.isNotEmpty() && hud.pilotPhase != PilotPhase.ORBIT) " · ${hud.etaText}" else ""
            textMeasurer.measure(AnnotatedString("${t.label.uppercase()} · ${hud.distanceText}$eta"), tagStyle)
        }
    }

    val spaceBrush = remember {
        Brush.radialGradient(
            0f to Color(0xFF10183A),
            0.62f to Color(0xFF080F28),
            1f to Color(0xFF04081A),
        )
    }

    val frameTick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(viewModel) {
        while (true) {
            withFrameNanos { nanos ->
                viewModel.onFrame(nanos)
                frameTick.longValue = nanos
            }
        }
    }

    val keys = remember { SolarKeyboardState() }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Canvas(
        modifier = modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event -> keys.handle(event, viewModel) }
            .pointerInput(viewModel) {
                var lastTapMs = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var previous = down.position
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.firstOrNull { it.pressed }
                        if (pressed == null) {
                            viewModel.lookRelease()
                            if (!moved) {
                                val up = event.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis
                                if (up - lastTapMs < 320L) viewModel.recentreLook()
                                lastTapMs = up
                            }
                            break
                        }
                        val dx = pressed.position.x - previous.x
                        val dy = pressed.position.y - previous.y
                        if (!moved && hypot(dx, dy) > viewConfiguration.touchSlop) moved = true
                        if (moved) viewModel.lookDrag(dx, dy, size.width.toFloat())
                        previous = pressed.position
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            },
    ) {
        val nanos = frameTick.longValue
        drawRect(spaceBrush)
        val session = viewModel.solar ?: return@Canvas
        renderer.labels = labels
        renderer.emoteLabels = emoteLabels
        renderer.targetLabel = targetLabel
        renderer.render(
            scope = this,
            session = session,
            pose = viewModel.pose,
            assets = assets,
            simTime = viewModel.simTime(),
            frameNanos = nanos,
            nowMs = viewModel.wallMs(),
            localColor = playerColor,
        )
    }
}

/** Desktop keys: the stick on WASD/arrows, brake on X, the dial on [ ], targets on T, pilots on F/G. */
private class SolarKeyboardState {
    private var left = false
    private var right = false
    private var up = false
    private var down = false

    fun handle(event: KeyEvent, viewModel: MultiPlayViewModel): Boolean {
        val pressed = when (event.type) {
            KeyEventType.KeyDown -> true
            KeyEventType.KeyUp -> false
            else -> return false
        }
        when (event.key) {
            Key.A, Key.DirectionLeft -> left = pressed

            Key.D, Key.DirectionRight -> right = pressed

            Key.W, Key.DirectionUp -> up = pressed

            Key.S, Key.DirectionDown -> down = pressed

            Key.X, Key.CtrlLeft, Key.CtrlRight -> {
                viewModel.setBrake(pressed)
                return true
            }

            Key.LeftBracket, Key.Q -> {
                if (pressed) viewModel.nudgeWarp(-1)
                return true
            }

            Key.RightBracket, Key.E -> {
                if (pressed) viewModel.nudgeWarp(1)
                return true
            }

            Key.T, Key.Tab -> {
                if (pressed) viewModel.cycleTarget(1)
                return true
            }

            Key.F -> {
                if (pressed) viewModel.setPilotMode(PilotMode.AUTO)
                return true
            }

            Key.G -> {
                if (pressed) viewModel.setPilotMode(PilotMode.AIM)
                return true
            }

            Key.C -> {
                if (pressed) viewModel.recentreLook()
                return true
            }

            Key.Escape, Key.P -> {
                if (pressed) viewModel.setPaused(true)
                return true
            }

            else -> return false
        }
        val x = (if (right) 1f else 0f) - (if (left) 1f else 0f)
        val y = (if (down) 1f else 0f) - (if (up) 1f else 0f)
        viewModel.setStick(x, y)
        return true
    }
}

// --- HUD pieces --------------------------------------------------------------------------------

@Composable
private fun ExpeditionCluster(hud: SolarHud, modifier: Modifier) {
    val accents = SatRideTheme.accents
    Column(modifier = modifier.padding(start = 44.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "P${hud.place}",
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp),
                fontWeight = FontWeight.ExtraBold,
                color = if (hud.place == 1) accents.sunshine else Color.White,
            )
            Text(
                text = "/ ${hud.participants}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            text = "WORLDS ${hud.visited.size}/${Body.entries.size}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (hud.grandTour) accents.sunshine else Color.White,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Body.entries.forEach { b ->
                val seen = b in hud.visited
                Text(
                    text = b.glyph,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (seen) Color(b.colorArgb) else Color.White.copy(alpha = 0.25f),
                )
            }
        }
        Text(
            text = "near ${hud.nearest.label} · ${hud.altitudeText}",
            style = MaterialTheme.typography.labelMedium,
            color = accents.tracking,
        )
        Text(
            "${hud.score} pts",
            style = MaterialTheme.typography.labelLarge,
            color = accents.sunshine,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The worlds as chips: glyph, name, how far. The selected one carries its own colour. */
@Composable
private fun DestinationBar(hud: SolarHud, viewModel: MultiPlayViewModel) {
    val haptics = LocalSatRideHaptics.current
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Body.entries.forEach { body ->
            val selected = body == hud.target
            val color = Color(body.colorArgb)
            val seen = body in hud.visited
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) color.copy(alpha = 0.28f) else PanelFill)
                    .border(
                        if (selected) 1.5.dp else 1.dp,
                        if (selected) color else PanelStroke,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable {
                        haptics.tap()
                        viewModel.selectTarget(body)
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(body.glyph, style = MaterialTheme.typography.labelMedium, color = color)
                    Text(
                        text = body.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = Color.White,
                    )
                    if (seen) {
                        Text(
                            "✓",
                            style = MaterialTheme.typography.labelSmall,
                            color = SatRideTheme.accents.success,
                        )
                    }
                }
                Text(
                    text = hud.bodyDistances[body] ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }
        }
    }
}

@Composable
private fun SolarClock(hud: SolarHud, isRoom: Boolean) {
    val remaining = hud.secondsRemaining.toInt()
    val text = when (hud.phase) {
        GamePhase.WAITING -> "WAITING"
        GamePhase.COUNTDOWN -> "READY"
        GamePhase.RUNNING, GamePhase.FINISHED -> "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}"
    }
    Text(
        text = if (isRoom) "$text · room" else text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.ExtraBold,
        color = if (hud.phase == GamePhase.RUNNING && remaining < 30) SatRideTheme.accents.coral else Color.White,
    )
}

@Composable
private fun PilotStandings(standings: List<Standing>, hud: SolarHud, modifier: Modifier) {
    val accents = SatRideTheme.accents
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(PanelFill)
            .border(1.dp, PanelStroke, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        standings.take(5).forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (i == 0) accents.sunshine else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.width(12.dp),
                )
                Box(Modifier.size(7.dp).background(Color(s.colorArgb), CircleShape))
                Text(
                    s.name.take(12),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (s.isLocal) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (s.isLocal) Color.White else Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    modifier = Modifier.width(74.dp),
                )
                Text(
                    text = if (s.isLocal) "${s.laps} worlds" else (hud.pilotDistances[s.uid] ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    modifier = Modifier.width(64.dp),
                )
                Text("${s.score}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
            }
        }
        if (standings.size > 5 && hud.place > 5) {
            Text(
                "· · ·  you P${hud.place}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * The speed-of-light console: current speed, the destination's ETA, and the dial.
 *
 * The dial is logarithmic — a thumb's width per decade from 30 km/s to a thousand times light —
 * because no linear slider can hold both "orbit the Moon" and "Neptune before lunch".
 */
@Composable
private fun WarpConsole(hud: SolarHud, viewModel: MultiPlayViewModel, modifier: Modifier) {
    val accents = SatRideTheme.accents
    var dragDial by remember { mutableStateOf<Double?>(null) }
    val dial = dragDial ?: hud.warpDial
    Column(
        modifier = modifier.widthIn(max = 320.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = hud.speedText,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp),
                fontWeight = FontWeight.ExtraBold,
                color = if (hud.governed) accents.coral else Color.White,
            )
            if (hud.target != null && hud.etaText.isNotEmpty() && hud.pilotPhase != PilotPhase.ORBIT) {
                Text(
                    text = "ETA ${hud.etaText}",
                    style = MaterialTheme.typography.labelLarge,
                    color = accents.tracking,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }
        Text(
            text = "SPEED OF LIGHT × ${hud.warpText}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accents.sunshine,
        )
        WarpDial(
            dial = dial,
            accent = accents.sunshine,
            onDrag = { d ->
                dragDial = d
                viewModel.setWarpDial(d)
            },
            onRelease = { dragDial = null },
            modifier = Modifier.width(280.dp).height(34.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallPill("AUTO SPEED", selected = hud.autoSpeed) { viewModel.setAutoSpeed(!hud.autoSpeed) }
            SmallPill("GOVERNOR", selected = hud.governor) { viewModel.setGovernor(!hud.governor) }
        }
        Text(
            text = pilotStatus(hud),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = when (hud.pilotMode) {
                PilotMode.AUTO -> accents.sunshine
                PilotMode.AIM -> accents.artifact
                PilotMode.MANUAL -> if (hud.governed) accents.coral else Color.White.copy(alpha = 0.6f)
            },
        )
    }
}

private fun pilotStatus(hud: SolarHud): String {
    val target = hud.target?.label?.uppercase() ?: ""
    return when (hud.pilotMode) {
        PilotMode.MANUAL -> if (hud.governed) {
            "GOVERNOR · approaching ${hud.nearest.label}"
        } else {
            "MANUAL · the stick is yours"
        }

        PilotMode.AIM -> "AUTO DIRECTION · nose on $target"

        PilotMode.AUTO -> when (hud.pilotPhase) {
            PilotPhase.IDLE -> "AUTOPILOT"
            PilotPhase.TURNING -> "AUTOPILOT · turning to $target"
            PilotPhase.CRUISE -> "AUTOPILOT · cruising to $target"
            PilotPhase.BRAKING -> "AUTOPILOT · braking for $target"
            PilotPhase.ORBIT -> "IN ORBIT · $target — pick the next world"
        }
    }
}

@Composable
private fun WarpDial(
    dial: Double,
    accent: Color,
    onDrag: (Double) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalSatRideHaptics.current
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                haptics.tap()
                onDrag((down.position.x / size.width).toDouble().coerceIn(0.0, 1.0))
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.firstOrNull { it.pressed }
                    if (pressed == null) {
                        onRelease()
                        break
                    }
                    onDrag((pressed.position.x / size.width).toDouble().coerceIn(0.0, 1.0))
                    event.changes.forEach { it.consume() }
                }
            }
        },
    ) {
        val trackH = 8.dp.toPx()
        val cy = size.height / 2f
        val r = trackH / 2f
        drawRoundRect(
            Color.White.copy(alpha = 0.12f),
            topLeft = Offset(0f, cy - r),
            size = Size(size.width, trackH),
            cornerRadius = CornerRadius(r, r),
        )
        val x = (dial * size.width).toFloat()
        drawRoundRect(
            Brush.horizontalGradient(listOf(accent.copy(alpha = 0.35f), accent), 0f, x.coerceAtLeast(1f)),
            topLeft = Offset(0f, cy - r),
            size = Size(x, trackH),
            cornerRadius = CornerRadius(r, r),
        )
        // One tick per decade: 0.0001c … 1000c is seven steps.
        for (k in 0..7) {
            val tx = size.width * k / 7f
            val major = k == 4 // 1c
            drawLine(
                Color.White.copy(alpha = if (major) 0.9f else 0.35f),
                Offset(tx, cy - (if (major) 11f else 7f).dp.toPx()),
                Offset(tx, cy - r - 2.dp.toPx()),
                strokeWidth = (if (major) 2f else 1f).dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawCircle(Color.White, 10.dp.toPx(), Offset(x, cy))
        drawCircle(accent, 7.dp.toPx(), Offset(x, cy))
    }
}

/** A round toggle for AIM and AUTO: lit while engaged. */
@Composable
private fun PilotButton(
    label: String,
    hint: String,
    accent: Color,
    active: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val haptics = LocalSatRideHaptics.current
    Box(
        modifier = Modifier
            .size(size)
            .background(if (active) accent else accent.copy(alpha = 0.2f), CircleShape)
            .border(2.5.dp, if (active) Color.White.copy(alpha = 0.9f) else accent, CircleShape)
            .clip(CircleShape)
            .clickable {
                haptics.press()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (active) Color(0xFF0B0E2A) else Color.White,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) Color(0xFF0B0E2A).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * The map: the Sun in the middle, orbits as rings on a logarithmic radius (so Mercury and
 * Neptune both fit), every world and every ship where it really is this second.
 */
@Composable
private fun SolarMap(hud: SolarHud, playerColor: Color, modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val outer = size.width / 2f - 4.dp.toPx()
        drawCircle(PanelFill, size.width / 2f, c)
        drawCircle(Color.White.copy(alpha = 0.25f), size.width / 2f - 1.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        fun radiusFor(au: Double): Float {
            val t = ((ln(au.coerceAtLeast(0.2)) - ln(0.2)) / (ln(48.0) - ln(0.2))).coerceIn(0.0, 1.0)
            return (0.06f + 0.94f * t.toFloat()) * outer
        }
        fun at(dot: com.developerstring.satride.feature.play.multiplay.MapDot): Offset {
            val a = dot.eclipticLongitudeDeg * PI / 180.0
            val r = radiusFor(dot.radiusAu)
            return Offset(c.x + (cos(a) * r).toFloat(), c.y - (sin(a) * r).toFloat())
        }
        hud.mapDots.filter { it.isBody && it.label != Body.MOON.label }.forEach { body ->
            drawCircle(
                Color(body.colorArgb).copy(alpha = 0.22f),
                radiusFor(body.radiusAu),
                c,
                style = Stroke(1.dp.toPx()),
            )
        }
        // The Sun.
        drawCircle(Color(0xFFFFD04D).copy(alpha = 0.5f), 5.dp.toPx(), c)
        drawCircle(Color(0xFFFFF3C4), 2.5.dp.toPx(), c)
        hud.mapDots.forEach { dot ->
            if (dot.isLocal) return@forEach
            val p = at(dot)
            val r = if (dot.isBody) 2.6.dp.toPx() else 2.2.dp.toPx()
            drawCircle(Color(dot.colorArgb), r, p)
            if (!dot.isBody) drawCircle(Color.White.copy(alpha = 0.8f), r * 0.4f, p)
        }
        hud.mapDots.firstOrNull { it.isLocal }?.let { me ->
            val p = at(me)
            drawCircle(Color.White, 5.dp.toPx(), p)
            drawCircle(playerColor, 3.5.dp.toPx(), p)
        }
    }
}

/** Countdown and end banners over the centre of the arena. */
@Composable
private fun SolarPhaseOverlay(hud: SolarHud, state: MultiPlayUiState) {
    val accents = SatRideTheme.accents
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (hud.phase) {
            GamePhase.WAITING, GamePhase.COUNTDOWN -> {
                val seconds = hud.secondsToStart
                val text = when {
                    hud.phase == GamePhase.WAITING -> "GET READY"
                    seconds > 3.0 -> "GET READY"
                    seconds > 0.05 -> "${seconds.toInt() + 1}"
                    else -> "LAUNCH!"
                }
                val scale by animateFloatAsState(if (text.length <= 2) 1.25f else 1f, label = "countdown")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp),
                        fontWeight = FontWeight.ExtraBold,
                        color = if (text == "LAUNCH!") accents.sunshine else Color.White,
                        modifier = Modifier.scale(scale),
                    )
                    Text(
                        text = "You are in orbit around Earth, today. Pick a world up top and press AUTO — " +
                            "or take the stick. The console sets your speed of light.",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Text(
                        text = if (state.motionAvailable) {
                            "Stick steers · BRAKE slows · AIM points you · AUTO flies you · double-tap to recentre"
                        } else {
                            "WASD steers · X brakes · [ ] set the speed of light · T next world · F autopilot · G aim"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
            }

            GamePhase.FINISHED -> Banner("EXPEDITION OVER", "Writing the log…", Color.White)

            GamePhase.RUNNING -> Unit
        }
        AnimatedVisibility(
            visible = hud.phase == GamePhase.RUNNING && hud.elapsedSeconds < 1.2,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                "LAUNCH!",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp),
                fontWeight = FontWeight.ExtraBold,
                color = accents.sunshine,
            )
        }
        if (hud.phase == GamePhase.RUNNING && hud.grandTour && hud.elapsedSeconds > 1.2) {
            Spacer(Modifier.height(0.dp))
        }
    }
}

@Composable
private fun SolarPauseMenu(state: MultiPlayUiState, viewModel: MultiPlayViewModel) {
    val hud = state.solarHud
    Box(Modifier.fillMaxSize().background(Color(0xAA060B1E)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(PanelFill)
                .border(1.dp, PanelStroke, RoundedCornerShape(22.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "PAUSED",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
            ToggleRow(
                label = "Approach governor",
                hint = "Never closer than a few seconds from a surface at your speed",
                checked = hud.governor,
                enabled = true,
            ) { viewModel.setGovernor(it) }
            ToggleRow(
                label = "Auto speed",
                hint = "Each trip takes about half a minute; the dial follows",
                checked = hud.autoSpeed,
                enabled = true,
            ) { viewModel.setAutoSpeed(it) }
            ToggleRow(
                label = "Motion camera",
                hint = if (state.tiltCalibrated) "Hold the phone how you like and recentre" else "Tilt to look around",
                checked = state.motionCamera && state.motionAvailable,
                enabled = state.motionAvailable,
            ) { viewModel.setMotionCamera(it) }
            ToggleRow(
                label = "Tilt to steer",
                hint = "Roll the phone like a wheel (manual flight only)",
                checked = state.tiltSteer && state.motionAvailable,
                enabled = state.motionAvailable,
            ) { viewModel.setTiltSteer(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SatRideButton(text = "Resume", onClick = { viewModel.setPaused(false) }, modifier = Modifier.weight(1f))
                SatRideButton(text = "Recentre", onClick = {
                    viewModel.recentreLook()
                }, style = SatRideButtonStyle.Secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SatRideButton(
                    text = "End expedition",
                    onClick = { viewModel.endExpedition() },
                    style = SatRideButtonStyle.Secondary,
                    accent = SatRideTheme.accents.sunshine,
                    modifier = Modifier.weight(1f),
                )
                SatRideButton(
                    text = "Quit",
                    onClick = { viewModel.quitToHome() },
                    style = SatRideButtonStyle.Secondary,
                    accent = SatRideTheme.accents.coral,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Suppress("unused")
private val BodyKind.chipAlpha: Float get() = if (this == BodyKind.STAR) 1f else 0.9f
