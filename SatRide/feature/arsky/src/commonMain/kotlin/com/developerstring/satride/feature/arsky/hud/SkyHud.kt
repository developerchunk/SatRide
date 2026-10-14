package com.developerstring.satride.feature.arsky.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.SatRideIcon
import com.developerstring.satride.core.designsystem.component.SatRideIconGraphic
import com.developerstring.satride.core.designsystem.theme.MotionSpec
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.core.permissions.PermissionStatus
import com.developerstring.satride.core.sensors.CompassAccuracy
import com.developerstring.satride.feature.arsky.SkyEvent
import com.developerstring.satride.feature.arsky.SkyMode
import com.developerstring.satride.feature.arsky.SkySatelliteUi
import com.developerstring.satride.feature.arsky.SkyUiState
import kotlin.math.PI
import kotlin.math.sin

/**
 * The top strip: compass accuracy, the mode switch, and search.
 *
 * Kept to one row of chips. The design brief allows overlays about a third of the AR view and
 * this is the only one always on screen, so everything else in this package appears conditionally
 * and along the bottom, where a hand holding a phone is not covering it.
 */
@Composable
internal fun SkyHud(state: SkyUiState, onEvent: (SkyEvent) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompassChip(
            accuracy = state.accuracy,
            available = state.attitudeAvailable,
            onClick = { onEvent(SkyEvent.CalibrationOpened) },
        )

        // Two controls, because they are two decisions. The mode chip chooses where the view's
        // attitude comes from — your hand, or a virtual camera you drag. The camera chip chooses
        // what is behind it, and only exists once the first choice is "your hand".
        ModeChip(augmented = state.mode == SkyMode.AUGMENTED, onClick = { onEvent(SkyEvent.ModeToggled) })

        AnimatedVisibility(
            visible = state.cameraSwitchAvailable,
            enter = fadeIn(MotionSpec.fast()) + expandHorizontally(MotionSpec.fast()),
            exit = fadeOut(MotionSpec.fast()) + shrinkHorizontally(MotionSpec.fast()),
        ) {
            CameraChip(on = state.cameraOn, onClick = { onEvent(SkyEvent.CameraToggled) })
        }

        AccentChip(
            label = "SEARCH",
            accent = SatRideTheme.accents.tracking,
            modifier = Modifier.clickable { onEvent(SkyEvent.SearchRequested) },
        )
    }
}

/**
 * The view-mode switch: the augmented sky, or the drawn one.
 *
 * Deliberately says which mode you are *in*, not which one tapping would take you to. A toggle
 * labelled with its destination is ambiguous the moment the two states look similar — and a sky
 * full of satellites looks much the same whichever way the view is being aimed.
 */
@Composable
private fun ModeChip(augmented: Boolean, onClick: () -> Unit) {
    AccentChip(
        label = if (augmented) "AR" else "SKY",
        accent = SatRideTheme.accents.tracking,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics {
                contentDescription =
                    if (augmented) "Switch to the drawn sky" else "Switch to the augmented sky"
            },
    )
}

/**
 * The camera switch, shown only inside the augmented view.
 *
 * A camera glyph with a state, not a mode label. The chip it replaced said "AR" or "SKY", which
 * named the two modes without saying which one you were in or what tapping would do — and the sky
 * looks broadly the same either way at a glance, so there was nothing to disambiguate it against.
 *
 * Turning the camera off is a first-class thing to do, not a fallback: the satellite positions are
 * real either way, and indoors, at a desk, on a simulator, or simply when a live camera feed is not
 * wanted, the drawn sky is the better view. So the "off" state is tinted like any other resting
 * control rather than like a warning.
 */
@Composable
private fun CameraChip(on: Boolean, onClick: () -> Unit) {
    val accents = SatRideTheme.accents
    val tint by animateColorAsState(
        targetValue = if (on) accents.tracking else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "cameraChipTint",
    )

    AccentChip(
        label = if (on) "CAMERA ON" else "CAMERA OFF",
        accent = tint,
        leading = {
            SatRideIconGraphic(
                icon = if (on) SatRideIcon.Camera else SatRideIcon.CameraOff,
                size = 14.dp,
                tint = tint,
            )
        },
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (on) "Turn the camera off" else "Turn the camera on"
            },
    )
}

/**
 * Compass state, as a chip that is worth tapping.
 *
 * Accuracy is first-class rather than hidden because when the magnetometer is confused the sky
 * view is simply *wrong*, and there is nothing on screen to say so — the glyphs look exactly as
 * confident as they do when correct. Telling the user, and offering the gesture that fixes it, is
 * the difference between "this app is broken" and "ah, I am standing next to a fridge".
 */
@Composable
private fun CompassChip(accuracy: CompassAccuracy, available: Boolean, onClick: () -> Unit) {
    val accents = SatRideTheme.accents
    val (label, accent) = when {
        !available -> "NO COMPASS" to accents.danger
        accuracy == CompassAccuracy.HIGH -> "COMPASS OK" to accents.success
        accuracy == CompassAccuracy.MEDIUM -> "COMPASS FAIR" to accents.warning
        accuracy == CompassAccuracy.LOW -> "CALIBRATE" to accents.danger
        else -> "CALIBRATING" to accents.warning
    }

    AccentChip(label = label, accent = accent, modifier = Modifier.clickable(enabled = available, onClick = onClick))
}

/**
 * How to fix a confused compass, with the figure-of-eight animated rather than described.
 *
 * The gesture is impossible to convey in words — every attempt reads as "wave your phone around"
 * — so the card draws it, on a loop, and the user copies what they see.
 */
@Composable
internal fun CalibrationCard(accuracy: CompassAccuracy, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    GlassPanel(modifier = modifier) {
        Text("Calibrate the compass", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Trace a figure of eight with the phone, turning your wrist as you go. " +
                "Move away from anything metal — a desk frame or a magnetic case is usually the culprit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )

        FigureEight(modifier = Modifier.padding(vertical = Spacing.lg).size(width = 140.dp, height = 80.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = when (accuracy) {
                    CompassAccuracy.HIGH -> "Compass is good now."
                    CompassAccuracy.MEDIUM -> "Getting better."
                    else -> "Still uncertain."
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (accuracy == CompassAccuracy.HIGH) {
                    SatRideTheme.accents.success
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    }
}

/** A dot tracing a lemniscate — the calibration gesture, drawn. */
@Composable
private fun FigureEight(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "figureEight")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Restart),
        label = "phase",
    )
    val accent = SatRideTheme.accents.tracking

    Canvas(modifier) {
        val path = Path()
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val a = size.width / 2f * 0.9f
        val b = size.height / 2f * 0.9f

        // Lemniscate of Gerono: x = a·sin t, y = b·sin t·cos t. Simple, symmetric, and the shape
        // people actually make when told "figure of eight".
        var t = 0f
        while (t <= (2f * PI).toFloat()) {
            val x = centerX + a * sin(t)
            val y = centerY + b * sin(t) * kotlin.math.cos(t)
            if (t == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            t += 0.05f
        }
        drawPath(path, accent.copy(alpha = 0.35f), style = Stroke(width = 2.dp.toPx()))

        drawCircle(
            color = accent,
            radius = 5.dp.toPx(),
            center = Offset(
                centerX + a * sin(phase),
                centerY + b * sin(phase) * kotlin.math.cos(phase),
            ),
        )
    }
}

/** Says what is missing, when something is, and offers the one action that would fix it. */
@Composable
internal fun SkyPrompt(state: SkyUiState, onRequestCamera: () -> Unit, modifier: Modifier = Modifier) {
    val message: String
    val action: Pair<String, () -> Unit>?

    when {
        !state.observerKnown -> {
            message = if (state.locationPermission == PermissionStatus.DENIED) {
                "Without a location SatRide cannot tell which satellites are overhead. " +
                    "Grant location access in Settings, or set a position by hand."
            } else {
                "Waiting for a location fix…"
            }
            action = null
        }

        state.isEmpty -> {
            // Reached only when there are no element sets at all, not when the sky is quiet:
            // every catalogued satellite is tracked now, including the ones below the horizon.
            message = "No orbital data yet. Pull to refresh on the Satellites tab."
            action = null
        }

        state.canOfferCamera -> {
            // Offered here rather than on arrival: the sky is already drawn and working, so the
            // prompt is an upgrade to something the user can see rather than a toll gate in
            // front of a blank screen.
            message = "Show the camera behind the sky?"
            action = "Turn on" to onRequestCamera
        }

        else -> return
    }

    GlassPanel(modifier = modifier.fillMaxWidth()) {
        Text(message, style = MaterialTheme.typography.bodySmall)
        action?.let { (label, onClick) ->
            TextButton(onClick = onClick, modifier = Modifier.padding(top = Spacing.xs)) { Text(label) }
        }
    }
}

/** Says who is being guided to, and gives an obvious way to stop. */
@Composable
internal fun GuideBanner(target: SkySatelliteUi, onClear: () -> Unit, modifier: Modifier = Modifier) {
    GlassPanel(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Guiding to", style = MaterialTheme.typography.labelSmall, color = SatRideTheme.accents.success)
                Text(target.name, style = MaterialTheme.typography.titleSmall)
            }
            TextButton(onClick = onClear) { Text("Stop") }
        }
    }
}
