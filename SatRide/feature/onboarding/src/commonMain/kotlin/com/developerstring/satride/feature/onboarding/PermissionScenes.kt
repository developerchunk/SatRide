package com.developerstring.satride.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import com.developerstring.satride.core.designsystem.art.rememberSceneCycle
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.permissions.SkyPermission
import kotlin.math.PI
import kotlin.math.sin

/**
 * A small illustrated badge per permission, in the same flat-vector language as the onboarding
 * scenes: solid fills, no gradients, one accent hue each.
 *
 * Each one animates on a slow loop. A still icon next to a permission request reads as a form
 * field; a moving one reads as the thing being described, which is the whole reason this screen
 * has pictures on it at all.
 */
@Composable
internal fun PermissionScene(permission: SkyPermission, modifier: Modifier = Modifier) {
    val accents = SatRideTheme.accents
    val cycle = rememberSceneCycle(periodMillis = 3400, label = "permissionScene${permission.name}")

    when (permission) {
        SkyPermission.LOCATION -> {
            val ground = accents.skyBlue
            val accent = accents.tracking
            Canvas(modifier) { drawLocationBadge(ground, accent, accents.shadow, cycle) }
        }

        SkyPermission.CAMERA -> {
            val body = accents.lavender
            val accent = accents.tracking
            Canvas(modifier) { drawCameraBadge(body, accent, accents.shadow, cycle) }
        }

        SkyPermission.NOTIFICATIONS -> {
            val bell = accents.sunshine
            val accent = accents.coral
            Canvas(modifier) { drawBellBadge(bell, accent, accents.shadow, cycle) }
        }
    }
}

/** The badge plate every scene sits on: a soft disc in the scene's own accent. */
private fun DrawScope.plate(tint: Color) {
    drawCircle(tint.copy(alpha = 0.14f), radius = size.minDimension * 0.48f)
}

/** A pin dropping onto a curved horizon, with a ping ring spreading from where it lands. */
private fun DrawScope.drawLocationBadge(ground: Color, accent: Color, shadow: Color, cycle: Float) {
    plate(accent)
    val w = size.width
    val h = size.height
    val horizonR = w * 0.62f
    val horizonC = Offset(w / 2f, h * 0.96f + horizonR * 0.42f)

    // The curved ground, clipped to the badge so it reads as the top of a planet.
    clipPath(Path().apply { addOval(Rect(0f, 0f, w, h)) }) {
        drawCircle(ground, radius = horizonR, center = horizonC)
        drawCircle(shadow.copy(alpha = 0.25f), radius = horizonR, center = horizonC.copy(x = horizonC.x + w * 0.30f))
    }

    val landing = Offset(w / 2f, h * 0.66f)

    // The ping: a ring expanding and fading out of the landing point.
    val ping = cycle
    drawCircle(
        color = accent.copy(alpha = (1f - ping) * 0.55f),
        radius = w * 0.10f + ping * w * 0.26f,
        center = landing,
        style = Stroke(width = w * 0.03f),
    )

    // The pin, bobbing above where it points.
    val bob = sin(cycle * 2f * PI.toFloat()) * h * 0.03f
    val tip = Offset(landing.x, landing.y - h * 0.02f + bob)
    val headR = w * 0.115f
    val headC = Offset(tip.x, tip.y - headR * 1.75f)
    drawPath(
        path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(headC.x - headR * 0.78f, headC.y + headR * 0.60f)
            lineTo(headC.x + headR * 0.78f, headC.y + headR * 0.60f)
            close()
        },
        color = accent,
    )
    drawCircle(accent, radius = headR, center = headC)
    drawCircle(Color.White.copy(alpha = 0.92f), radius = headR * 0.42f, center = headC)
}

/** A phone held up to the sky, with a satellite crossing its screen. */
private fun DrawScope.drawCameraBadge(body: Color, accent: Color, shadow: Color, cycle: Float) {
    plate(body)
    val w = size.width
    val h = size.height
    val phone = Rect(w * 0.30f, h * 0.20f, w * 0.70f, h * 0.84f)

    drawRoundRect(
        color = body,
        topLeft = Offset(phone.left, phone.top),
        size = Size(phone.width, phone.height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
    )
    val screen = Rect(phone.left + w * 0.04f, phone.top + h * 0.07f, phone.right - w * 0.04f, phone.bottom - h * 0.06f)
    drawRoundRect(
        color = shadow.copy(alpha = 0.55f),
        topLeft = Offset(screen.left, screen.top),
        size = Size(screen.width, screen.height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
    )

    // What the screen is showing: a satellite tracking left to right behind the glass.
    clipPath(
        Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    screen,
                    androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
                ),
            )
        },
    ) {
        val x = screen.left + screen.width * cycle
        val y = screen.top + screen.height * (0.62f - 0.24f * sin(cycle * PI.toFloat()))
        drawCircle(accent, radius = w * 0.045f, center = Offset(x, y))
        drawLine(
            color = accent.copy(alpha = 0.45f),
            start = Offset(x - w * 0.14f, y + h * 0.05f),
            end = Offset(x, y),
            strokeWidth = w * 0.018f,
            cap = StrokeCap.Round,
        )
        // Two stars, so the screen reads as sky rather than as a blank panel.
        drawCircle(
            Color.White.copy(alpha = 0.7f),
            radius = w * 0.014f,
            center = Offset(screen.left + screen.width * 0.26f, screen.top + screen.height * 0.28f),
        )
        drawCircle(
            Color.White.copy(alpha = 0.5f),
            radius = w * 0.011f,
            center = Offset(screen.left + screen.width * 0.72f, screen.top + screen.height * 0.38f),
        )
    }
}

/** A bell with a rising pass arc behind it, ringing on the loop. */
private fun DrawScope.drawBellBadge(bell: Color, accent: Color, shadow: Color, cycle: Float) {
    plate(accent)
    val w = size.width
    val h = size.height

    // The pass the reminder is about: an arc rising and setting behind the bell.
    drawArc(
        color = accent.copy(alpha = 0.45f),
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(w * 0.14f, h * 0.30f),
        size = Size(w * 0.72f, h * 0.62f),
        style = Stroke(width = w * 0.03f, cap = StrokeCap.Round),
    )

    // A small swing rather than a full rotation: a bell that spins looks broken.
    val swing = sin(cycle * 4f * PI.toFloat()) * 7f
    rotate(degrees = swing, pivot = Offset(w / 2f, h * 0.26f)) {
        val body = Path().apply {
            moveTo(w * 0.30f, h * 0.64f)
            cubicTo(w * 0.30f, h * 0.34f, w * 0.38f, h * 0.26f, w * 0.50f, h * 0.26f)
            cubicTo(w * 0.62f, h * 0.26f, w * 0.70f, h * 0.34f, w * 0.70f, h * 0.64f)
            close()
        }
        drawPath(body, bell)
        drawPath(
            path = Path().apply {
                moveTo(w * 0.56f, h * 0.28f)
                cubicTo(w * 0.66f, h * 0.34f, w * 0.70f, h * 0.44f, w * 0.70f, h * 0.64f)
                lineTo(w * 0.56f, h * 0.64f)
                close()
            },
            color = shadow.copy(alpha = 0.22f),
        )
        drawRoundRect(
            color = bell,
            topLeft = Offset(w * 0.26f, h * 0.64f),
            size = Size(w * 0.48f, h * 0.075f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.04f),
        )
        drawCircle(bell, radius = w * 0.055f, center = Offset(w * 0.50f, h * 0.775f))
        drawCircle(bell, radius = w * 0.045f, center = Offset(w * 0.50f, h * 0.215f))
    }
}
