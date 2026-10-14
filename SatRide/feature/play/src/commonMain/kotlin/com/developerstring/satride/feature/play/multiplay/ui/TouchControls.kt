package com.developerstring.satride.feature.play.multiplay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.haptics.LocalSatRideHaptics
import kotlin.math.hypot

/**
 * A thumb stick: the knob follows the finger inside the ring and reports a −1..1 vector.
 *
 * The stick lives wherever the thumb lands — the first touch anywhere in its pad becomes the
 * centre — because a fixed centre under a thumb that is looking at the screen, not at the
 * stick, is the single most common reason touch controls feel wrong.
 *
 * ## Why it is drawn opaque
 *
 * It used to be a six-per-cent-white pad with a twenty-two-per-cent ring: beautiful over the black
 * of space, and completely invisible over the lit limb of the Earth, which is exactly where the
 * bottom-left corner of the arena usually is. A player looking at the screen could not see the
 * game's primary control at all.
 *
 * So the pad is now a solid dark disc with a coloured rim. It is the one element on this screen
 * that must be legible against *anything* the renderer puts behind it, and a control you cannot
 * find is worse than one that is slightly less pretty.
 */
@Composable
fun VirtualStick(
    onChange: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    accent: Color = Color(0xFF3FE0FF),
    /** Shown across the pad until the stick has been touched. Null to never show it. */
    idleLabel: String? = null,
) {
    val onChangeState by rememberUpdatedState(onChange)
    var centre by remember { mutableStateOf<Offset?>(null) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    var everTouched by remember { mutableStateOf(false) }
    val haptics = LocalSatRideHaptics.current

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(size)
                .semantics { contentDescription = "Steering stick" }
                .pointerInput(Unit) {
                    val radius = this.size.width / 2f * 0.62f
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        haptics.tap()
                        everTouched = true
                        centre = down.position
                        knob = Offset.Zero
                        onChangeState(0f, 0f)
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.firstOrNull { it.pressed }
                            if (pressed == null) {
                                centre = null
                                knob = Offset.Zero
                                onChangeState(0f, 0f)
                                break
                            }
                            val c = centre ?: pressed.position
                            var dx = pressed.position.x - c.x
                            var dy = pressed.position.y - c.y
                            val m = hypot(dx, dy)
                            if (m > radius) {
                                dx = dx / m * radius
                                dy = dy / m * radius
                            }
                            knob = Offset(dx, dy)
                            // A small dead zone so a resting thumb does not steer.
                            val nx = if (kotlin.math.abs(dx) / radius < 0.08f) 0f else dx / radius
                            val ny = if (kotlin.math.abs(dy) / radius < 0.08f) 0f else dy / radius
                            onChangeState(nx.coerceIn(-1f, 1f), ny.coerceIn(-1f, 1f))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                },
        ) {
            val r = this.size.width / 2f
            val c = centre ?: Offset(r, r)
            val ringR = r * 0.62f
            val resting = centre == null
            val showLabel = idleLabel != null && !everTouched

            // Pad: solid, so the control reads against the Earth as well as against space.
            drawCircle(Color(0xE60A0F26), r, Offset(r, r))
            drawCircle(accent.copy(alpha = if (resting) 0.55f else 0.85f), r, Offset(r, r), style = Stroke(3.dp.toPx()))

            // Ring and cross hairs, wherever the thumb put the centre.
            drawCircle(Color.White.copy(alpha = 0.30f), ringR, c, style = Stroke(2.dp.toPx()))
            drawCircle(accent.copy(alpha = 0.12f), ringR, c)
            drawLine(Color.White.copy(alpha = 0.20f), Offset(c.x - ringR, c.y), Offset(c.x + ringR, c.y), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.20f), Offset(c.x, c.y - ringR), Offset(c.x, c.y + ringR), 1.dp.toPx())

            // Direction arrows while resting: the pad says which way it moves you before you touch it.
            if (resting) {
                val a = accent.copy(alpha = 0.45f)
                val d = ringR * 0.80f
                val w = r * 0.085f
                listOf(
                    Offset(c.x, c.y - d) to Offset(0f, -1f),
                    Offset(c.x, c.y + d) to Offset(0f, 1f),
                    Offset(c.x - d, c.y) to Offset(-1f, 0f),
                    Offset(c.x + d, c.y) to Offset(1f, 0f),
                ).forEach { (tip, dir) ->
                    val back = Offset(tip.x - dir.x * w * 1.6f, tip.y - dir.y * w * 1.6f)
                    val side = Offset(-dir.y * w, dir.x * w)
                    drawLine(a, Offset(back.x + side.x, back.y + side.y), tip, 2.5.dp.toPx())
                    drawLine(a, Offset(back.x - side.x, back.y - side.y), tip, 2.5.dp.toPx())
                }
            }

            // Knob.
            val k = Offset(c.x + knob.x, c.y + knob.y)
            drawCircle(
                Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.55f)), center = k, radius = r * 0.26f),
                r * 0.26f,
                k,
            )
            drawCircle(Color.White.copy(alpha = 0.85f), r * 0.26f, k, style = Stroke(2.dp.toPx()))
        }

        // Named until it has been used. A stick nobody recognises is a stick nobody presses, and
        // the name costs one line that disappears the moment it has done its job.
        if (idleLabel != null && !everTouched) {
            Text(
                text = idleLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accent.copy(alpha = 0.8f),
                modifier = Modifier.offset(y = size * 0.24f),
            )
        }
    }
}

/** A round button that reports press and release, for hold-to-drift and hold-to-brake. */
@Composable
fun HoldButton(
    label: String,
    accent: Color,
    onPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 78.dp,
    hint: String? = null,
    active: Boolean = false,
) {
    val onPress by rememberUpdatedState(onPressChange)
    var pressed by remember { mutableStateOf(false) }
    val haptics = LocalSatRideHaptics.current
    val lit = pressed || active
    Box(
        modifier = modifier
            .size(size)
            .background(if (lit) accent else accent.copy(alpha = 0.18f), CircleShape)
            .border(2.dp, if (lit) Color.White.copy(alpha = 0.9f) else accent.copy(alpha = 0.75f), CircleShape)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptics.tap()
                    onPress(true)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        event.changes.forEach { it.consume() }
                    }
                    pressed = false
                    onPress(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (lit) Color(0xFF0B0E2A) else Color.White,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (lit) Color(0xFF0B0E2A).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** A round tap button with a charge count, for boost. */
@Composable
fun TapButton(
    label: String,
    accent: Color,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 92.dp,
    charges: Int = 0,
    enabled: Boolean = true,
) {
    val onTapState by rememberUpdatedState(onTap)
    var pressed by remember { mutableStateOf(false) }
    val haptics = LocalSatRideHaptics.current
    val fill = when {
        !enabled -> Color.White.copy(alpha = 0.08f)
        pressed -> accent
        else -> accent.copy(alpha = 0.28f)
    }
    Box(
        modifier = modifier
            .size(size)
            .background(fill, CircleShape)
            .border(2.5.dp, if (enabled) accent else Color.White.copy(alpha = 0.25f), CircleShape)
            .semantics { contentDescription = label }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptics.press()
                    onTapState()
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        event.changes.forEach { it.consume() }
                    }
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (pressed) Color(0xFF0B0E2A) else Color.White,
            )
            androidx.compose.foundation.layout.Row {
                repeat(3) { i ->
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(
                                if (i < charges) accent else Color.White.copy(alpha = 0.18f),
                                CircleShape,
                            ),
                    )
                    if (i < 2) androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                }
            }
        }
    }
}
