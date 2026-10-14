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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
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
import com.developerstring.satride.feature.play.multiplay.HudSnapshot
import com.developerstring.satride.feature.play.multiplay.MultiPlayUiState
import com.developerstring.satride.feature.play.multiplay.MultiPlayViewModel
import com.developerstring.satride.feature.play.multiplay.engine.GameMode
import com.developerstring.satride.feature.play.multiplay.engine.GamePhase
import com.developerstring.satride.feature.play.multiplay.engine.Standing
import com.developerstring.satride.feature.play.multiplay.render.ArenaRenderer
import com.developerstring.satride.feature.play.render.GlobeRenderer
import org.koin.compose.koinInject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The race itself: the canvas underneath, the HUD around the edges, the stick and buttons where
 * the thumbs are.
 *
 * Layout is fixed for landscape thumbs. Left thumb: the stick. Right thumb: boost, drift and
 * brake, stacked where a thumb rests. Everything informational stays in the top third and the
 * bottom centre, out from under the hands.
 */
@Composable
internal fun ArenaPane(state: MultiPlayUiState, viewModel: MultiPlayViewModel) {
    val accents = SatRideTheme.accents
    val playerColor = Color(state.colorArgb)

    Box(Modifier.fillMaxSize().background(ArenaBlack)) {
        ArenaCanvas(state = state, viewModel = viewModel, playerColor = playerColor, modifier = Modifier.fillMaxSize())

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Top-left: position, lap and gate — or shields, or the tag status.
            StatusCluster(state.hud, state, Modifier.align(Alignment.TopStart).padding(12.dp))

            // Top-centre: the clock and the toasts beneath it.
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Clock(state.hud, state.isRoomGame)
                if (state.hud.nearestSatellite.isNotEmpty() && state.hud.nearestSatelliteKm < 600.0) {
                    Text(
                        text = "📡 ${state.hud.nearestSatellite} · ${state.hud.nearestSatelliteKm.roundToInt()} km",
                        style = MaterialTheme.typography.labelSmall,
                        color = accents.radio,
                    )
                }
                state.toasts.forEach { toast ->
                    val positive = toast.points >= 0
                    Text(
                        text = if (toast.points !=
                            0
                        ) {
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

            // Top-right: the mini standings.
            MiniStandings(state.standings, state.hud, Modifier.align(Alignment.TopEnd).padding(12.dp))

            // Left rail, under the status cluster rather than centred on the edge: centred, it
            // sat exactly where the steering pad is, and the pad won.
            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 96.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
                        .align(Alignment.TopStart)
                        .padding(start = 64.dp, top = 240.dp)
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

            // Bottom-left: the stick, with its name under it until it has been used once.
            //
            // The label is not decoration. The stick used to be drawn at six per cent white over
            // whatever the renderer put behind it, which against the lit limb of the Earth is
            // invisible — so the game's primary control could not be found at all. It is now
            // opaque (see `VirtualStick`), and it says what it is until the player has touched it.
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VirtualStick(
                    onChange = { x, y -> viewModel.setStick(x, y) },
                    accent = playerColor,
                    size = 132.dp,
                    idleLabel = "STEER",
                )
            }

            // Bottom-centre: speed, boost and drift.
            SpeedCluster(state.hud, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))

            // Bottom-right: the buttons and the minimap above them.
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Minimap(state.hud, state.standings, playerColor, Modifier.size(96.dp))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HoldButton(
                        label = "BRAKE",
                        accent = accents.lavender,
                        onPressChange = { viewModel.setBrake(it) },
                        size = 64.dp,
                    )
                    HoldButton(
                        label = "DRIFT",
                        accent = accents.artifact,
                        onPressChange = { viewModel.setDrift(it) },
                        // Only while it is doing something. "hold + steer" printed on the button
                        // was a manual page glued to a control — that is the rules card's job now,
                        // and a button that explains itself forever reads as a button nobody
                        // expects you to understand.
                        hint = if (state.hud.drifting) "charging" else null,
                        active = state.hud.drifting,
                        size = 84.dp,
                    )
                    TapButton(
                        label = "BOOST",
                        accent = accents.sunshine,
                        onTap = { viewModel.tapBoost() },
                        charges = state.hud.boostCharges,
                        enabled = state.hud.boostCharges > 0,
                        size = 96.dp,
                    )
                }
            }

            PhaseOverlay(state.hud, state)
            // Not while the rules are up: the game is held underneath them, so a pause menu would
            // be a second modal saying the same thing.
            if (state.paused && !state.showHowToPlay) PauseMenu(state, viewModel)
        }
    }
}

/** The canvas: frame loop, look gestures, the renderer. */
@Composable
private fun ArenaCanvas(
    state: MultiPlayUiState,
    viewModel: MultiPlayViewModel,
    playerColor: Color,
    modifier: Modifier,
) {
    val globeRenderer = remember { GlobeRenderer() }
    val arena = remember { ArenaRenderer(globeRenderer) }
    val assets: GlobeAssets = koinInject()

    val textMeasurer = rememberTextMeasurer()
    val tagStyle = MaterialTheme.typography.labelSmall.copy(color = Color.White, fontWeight = FontWeight.Bold)
    val emoteStyle = MaterialTheme.typography.titleLarge.copy(color = Color.White)
    val catalogNames = viewModel.session?.extraNames
    val labels: Map<String, TextLayoutResult> =
        remember(state.standings.map { it.uid to it.name }, catalogNames, tagStyle) {
            buildMap {
                state.standings.forEach { s -> put(s.uid, textMeasurer.measure(AnnotatedString(s.name), tagStyle)) }
                catalogNames?.forEach { name ->
                    if (name !in
                        this
                    ) {
                        put(name, textMeasurer.measure(AnnotatedString(name), tagStyle))
                    }
                }
            }
        }
    val emoteLabels = remember(emoteStyle) {
        Emotes.associateWith { textMeasurer.measure(AnnotatedString(it), emoteStyle) }
    }

    val spaceBrush = remember {
        Brush.radialGradient(
            0f to Color(0xFF16244E),
            0.62f to Color(0xFF0A122E),
            1f to Color(0xFF060B1E),
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

    // Keyboard, for desktop: the same inputs the stick and buttons produce. A touch phone never
    // sends key events, so this costs nothing there.
    val keys = remember { KeyboardState() }
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
                                val now = down.uptimeMillis
                                val up = event.changes.firstOrNull()?.uptimeMillis ?: now
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
        val session = viewModel.session ?: return@Canvas
        arena.labels = labels
        arena.emoteLabels = emoteLabels
        arena.render(
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

/** Which movement keys are down, folded into one stick vector on every change. */
private class KeyboardState {
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

            Key.ShiftLeft, Key.ShiftRight -> {
                viewModel.setDrift(pressed)
                return true
            }

            Key.Spacebar -> {
                if (pressed) viewModel.tapBoost()
                return true
            }

            Key.X, Key.CtrlLeft, Key.CtrlRight -> {
                viewModel.setBrake(pressed)
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
        // Screen up is negative y, matching the stick.
        val y = (if (down) 1f else 0f) - (if (up) 1f else 0f)
        viewModel.setStick(x, y)
        return true
    }
}

// --- HUD pieces --------------------------------------------------------------------------------

/**
 * Position, progress and score — as few elements as the mode allows.
 *
 * ## Why this was rebuilt
 *
 * It used to be four separately-styled read-outs stacked down the corner: a 34-point "P1", a
 * "/ 5", a "LAP 1/3", a "GATE 3/24" and a "110 pts", each its own size and colour. That is an
 * instrument panel — it invites you to read it, one line at a time, which is the opposite of what
 * a player doing 200 km/s through a debris field has attention for.
 *
 * Now: the place is the one big number, because it is the only thing that answers "am I winning",
 * and everything else is a single row of small chips under it that can be taken in at a glance
 * without being read.
 */
@Composable
private fun StatusCluster(hud: HudSnapshot, state: MultiPlayUiState, modifier: Modifier) {
    val accents = SatRideTheme.accents
    Column(modifier = modifier.padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "P${hud.place}",
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp),
                fontWeight = FontWeight.ExtraBold,
                color = if (hud.place == 1) accents.sunshine else Color.White,
            )
            Text(
                text = "${hud.score}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = accents.sunshine,
                modifier = Modifier.padding(bottom = 5.dp),
            )
            Text(
                text = "pts",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state.selectedMode) {
                GameMode.RACE -> {
                    HudChip("LAP ${(hud.lap + 1).coerceAtMost(maxOf(hud.laps, 1))}/${hud.laps}", Color.White)
                    HudChip("GATE ${hud.nextGate + 1}/${hud.gateCount}", accents.tracking)
                }

                GameMode.DODGE -> {
                    // Shields as pips rather than a count: three hearts is a game, "SHIELDS 3" is
                    // a status field.
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(hud.maxShields) { i ->
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .background(
                                        if (i < hud.shields) accents.coral else Color.White.copy(alpha = 0.22f),
                                        CircleShape,
                                    ),
                            )
                        }
                    }
                }

                GameMode.TAG -> HudChip(
                    text = if (hud.isIt) "YOU'RE IT — RUN" else "IT: ${hud.itName.ifEmpty { "—" }}",
                    tint = if (hud.isIt) accents.coral else Color.White,
                )

                GameMode.SOLAR -> Unit // The solar arena has its own pane.
            }
        }
    }
}

/** A small pill. Reads at a glance without being read. */
@Composable
private fun HudChip(text: String, tint: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = tint,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0x99070B1E))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Clock(hud: HudSnapshot, isRoom: Boolean) {
    val remaining = hud.secondsRemaining.toInt()
    val text = when (hud.phase) {
        GamePhase.WAITING -> "WAITING"
        GamePhase.COUNTDOWN -> "READY"
        GamePhase.RUNNING, GamePhase.FINISHED -> "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.ExtraBold,
        color = if (hud.phase == GamePhase.RUNNING && remaining < 30) SatRideTheme.accents.coral else Color.White,
    )
    if (isRoom && hud.phase == GamePhase.RUNNING && hud.finished) {
        Text(
            "Finished · waiting for the others",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun MiniStandings(standings: List<Standing>, hud: HudSnapshot, modifier: Modifier) {
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
                    color = when {
                        s.isIt -> accents.coral
                        s.isLocal -> Color.White
                        !s.alive -> Color.White.copy(alpha = 0.35f)
                        else -> Color.White.copy(alpha = 0.85f)
                    },
                    maxLines = 1,
                    modifier = Modifier.width(74.dp),
                )
                Text(
                    "${s.score}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
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
 * Speed, as a game reads it.
 *
 * ## Why this is not a speedometer any more
 *
 * It was: a 36-point "215", a "km/s", an altitude in kilometres and a thin progress bar — four
 * numbers in the middle of the screen, which is the single most cockpit-like thing in the arena
 * and the reason the whole screen felt like an instrument panel rather than a game.
 *
 * None of it was actionable. Nobody flies differently because they are at 215 km/s rather than
 * 190, and altitude is not a control input in any of these modes. What a player genuinely needs
 * from this corner is one thing: **is my drift charged, and am I boosting** — because that is the
 * only state here they can act on.
 *
 * So the bar is the hero and the number is a footnote. The bar fills as a drift charges, flashes
 * gold while a boost burns, and says one word about what is happening.
 */
@Composable
private fun SpeedCluster(hud: HudSnapshot, modifier: Modifier) {
    val accents = SatRideTheme.accents
    val state = when {
        hud.boosting -> "BOOST" to accents.sunshine
        hud.drifting -> "DRIFT" to accents.artifact
        hud.spinningOut -> "SPIN OUT" to accents.coral
        else -> null
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The state word, big, only when there is one. Silence the rest of the time — a label that
        // is always on screen stops being read within a lap.
        if (state != null) {
            Text(
                text = state.first,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = state.second,
            )
        }

        val charge = (hud.driftCharge / 2.5).toFloat().coerceIn(0f, 1f)
        Canvas(Modifier.width(210.dp).height(12.dp)) {
            val r = size.height / 2f
            val corner = androidx.compose.ui.geometry.CornerRadius(r, r)
            drawRoundRect(Color(0x99070B1E), cornerRadius = corner)
            drawRoundRect(
                Color.White.copy(alpha = 0.18f),
                cornerRadius = corner,
                style = Stroke(1.5.dp.toPx()),
            )
            if (hud.boosting) {
                drawRoundRect(accents.sunshine, cornerRadius = corner)
            } else if (charge > 0f) {
                drawRoundRect(
                    accents.artifact,
                    size = androidx.compose.ui.geometry.Size(size.width * charge, size.height),
                    cornerRadius = corner,
                )
            }
        }

        // Kept, small, because it is genuinely satisfying to watch climb — but it is a readout of
        // last resort, not the thing the eye lands on.
        Text(
            text = "${hud.speedKmS.roundToInt()} km/s",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
        )
    }
}

/** A ring of the lap with everyone on it; the next gate marked. */
@Composable
private fun Minimap(hud: HudSnapshot, standings: List<Standing>, playerColor: Color, modifier: Modifier) {
    val accents = SatRideTheme.accents
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.width / 2f - 6.dp.toPx()
        drawCircle(PanelFill, size.width / 2f, c)
        drawCircle(Color.White.copy(alpha = 0.3f), r, c, style = Stroke(2.dp.toPx()))
        fun at(fraction: Double): Offset {
            val a = fraction * 2.0 * PI - PI / 2.0
            return Offset(c.x + (cos(a) * r).toFloat(), c.y + (sin(a) * r).toFloat())
        }
        // Start/finish tick.
        val s0 = at(0.0)
        drawLine(
            Color.White.copy(alpha = 0.6f),
            Offset(s0.x, s0.y - 4.dp.toPx()),
            Offset(s0.x, s0.y + 4.dp.toPx()),
            2.dp.toPx(),
            StrokeCap.Round,
        )
        // Next gate.
        drawCircle(accents.tracking, 4.dp.toPx(), at(hud.nextGateFraction))
        // Others.
        standings.forEach { s ->
            if (s.isLocal) return@forEach
            val frac = s.racePosition - kotlin.math.floor(s.racePosition)
            drawCircle(Color(s.colorArgb).copy(alpha = if (s.alive) 0.9f else 0.3f), 3.dp.toPx(), at(frac))
        }
        // Me, on top.
        val me = at(hud.lapFraction)
        drawCircle(Color.White, 5.5.dp.toPx(), me)
        drawCircle(playerColor, 4.dp.toPx(), me)
    }
}

/**
 * A rail button, with its name under it.
 *
 * The label is what makes the rail usable. Four unlabelled glyphs — ❚❚, ?, ◎, ☺ — are a puzzle
 * even for someone who wrote them, and "recentre the camera" is not a thing anybody guesses from a
 * circle with a dot in it.
 */
@Composable
internal fun RailButton(glyph: String, label: String? = null, onClick: () -> Unit) {
    val haptics = LocalSatRideHaptics.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(PanelFill)
                .border(1.dp, PanelStroke, CircleShape)
                .clickable {
                    haptics.tap()
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

/** Countdown, "GO", finished and out banners, over the centre of the arena. */
@Composable
private fun PhaseOverlay(hud: HudSnapshot, state: MultiPlayUiState) {
    val accents = SatRideTheme.accents
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            hud.phase == GamePhase.WAITING || hud.phase == GamePhase.COUNTDOWN -> {
                val seconds = hud.secondsToStart
                val text = when {
                    hud.phase == GamePhase.WAITING -> "GET READY"
                    seconds > 3.0 -> "GET READY"
                    seconds > 0.05 -> "${seconds.toInt() + 1}"
                    else -> "GO!"
                }
                val scale by animateFloatAsState(if (text.length <= 2) 1.25f else 1f, label = "countdown")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp),
                        fontWeight = FontWeight.ExtraBold,
                        color = if (text == "GO!") accents.sunshine else Color.White,
                        modifier = Modifier.scale(scale),
                    )
                    // One reminder, not a manual. The full rules are on the card, which this
                    // player has either just read or can reach from the ? on the rail — a
                    // three-clause sentence under a counting-down number was read by nobody.
                    Text(
                        text = if (state.motionAvailable) {
                            "Steer with the stick · hold DRIFT through turns"
                        } else {
                            "Arrows steer · Shift drifts · Space boosts"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            hud.phase == GamePhase.RUNNING && !hud.alive -> {
                Banner("OUT", "You're spectating until the round ends.", accents.coral)
            }

            hud.phase == GamePhase.RUNNING && hud.finished && state.isRoomGame -> Unit

            hud.phase == GamePhase.RUNNING && hud.finished -> {
                Banner("FINISHED", "Tallying…", accents.sunshine)
            }

            hud.phase == GamePhase.FINISHED -> Banner("TIME", "Tallying…", Color.White)
        }
        // A short "GO" flash right after the lights change.
        AnimatedVisibility(
            visible = hud.phase == GamePhase.RUNNING && hud.elapsedSeconds < 1.2,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                "GO!",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp),
                fontWeight = FontWeight.ExtraBold,
                color = accents.sunshine,
            )
        }
    }
}

@Composable
internal fun Banner(title: String, caption: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.ExtraBold, color = color)
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f))
    }
}

@Composable
private fun PauseMenu(state: MultiPlayUiState, viewModel: MultiPlayViewModel) {
    Box(Modifier.fillMaxSize().background(Color(0xAA060B1E)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(360.dp)
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
                label = "Motion camera",
                hint = if (state.tiltCalibrated) "Hold the phone how you like and recentre" else "Tilt to look around",
                checked = state.motionCamera && state.motionAvailable,
                enabled = state.motionAvailable,
            ) { viewModel.setMotionCamera(it) }
            ToggleRow(
                label = "Tilt to steer",
                hint = "Roll the phone like a wheel",
                checked = state.tiltSteer && state.motionAvailable,
                enabled = state.motionAvailable,
            ) { viewModel.setTiltSteer(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SatRideButton(text = "Resume", onClick = { viewModel.setPaused(false) }, modifier = Modifier.weight(1f))
                SatRideButton(text = "Recentre", onClick = {
                    viewModel.recentreLook()
                }, style = SatRideButtonStyle.Secondary)
            }
            // Pausing is what a stuck player does. Offering the rules here is the difference
            // between a pause menu and a way out of being stuck.
            SatRideButton(
                text = "How to play",
                onClick = {
                    viewModel.setPaused(false)
                    viewModel.openHowToPlay()
                },
                style = SatRideButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
            SatRideButton(
                text = "Quit to Multi-Play",
                onClick = { viewModel.quitToHome() },
                style = SatRideButtonStyle.Secondary,
                accent = SatRideTheme.accents.coral,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
