package com.developerstring.satride.core.designsystem.art

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import com.developerstring.satride.core.designsystem.theme.SatRideTheme

/*
 * Flat-vector machines: the friendly hardware of the illustrated style. Solid fills, rounded
 * everything, glowing yellow windows against navy hulls.
 */

/**
 * A toy satellite: rounded body, gold dish, blue solar wings. [blink] (0→1 cycle) drives the
 * antenna beacon.
 */
@Composable
fun KurzSatellite(modifier: Modifier = Modifier, blink: Float = 0f) {
    val a = SatRideTheme.accents
    Canvas(modifier) {
        val u = size.minDimension / 100f // design units on a 100-unit canvas
        val c = Offset(size.width / 2f, size.height / 2f)

        val silver = Color(0xFFDDE6F2)
        val silverShade = lerp(silver, a.shadow, 0.35f)
        val panel = a.skyBlue
        val panelDark = lerp(panel, a.shadow, 0.35f)

        // Solar wings.
        listOf(-1f, 1f).forEach { side ->
            val w = 30f * u
            val h = 20f * u
            val x = c.x + side * 17f * u + (if (side < 0) -w else 0f)
            val y = c.y - h / 2f
            // Strut.
            drawLine(
                silverShade,
                Offset(c.x + side * 12f * u, c.y),
                Offset(c.x + side * 20f * u, c.y),
                strokeWidth =
                2.5f * u,
            )
            drawRoundRect(panel, Offset(x, y), Size(w, h), CornerRadius(3f * u))
            // Cell grid.
            val third = w / 3f
            for (i in 1..2) {
                drawLine(
                    panelDark,
                    Offset(x + third * i, y + 1.5f * u),
                    Offset(x + third * i, y + h - 1.5f * u),
                    strokeWidth =
                    1.6f * u,
                )
            }
            drawLine(
                panelDark,
                Offset(x + 1.5f * u, y + h / 2f),
                Offset(x + w - 1.5f * u, y + h / 2f),
                strokeWidth =
                1.6f * u,
            )
        }

        // Body.
        drawRoundRect(silver, Offset(c.x - 12f * u, c.y - 14f * u), Size(24f * u, 28f * u), CornerRadius(6f * u))
        drawRoundRect(silverShade, Offset(c.x - 12f * u, c.y + 4f * u), Size(24f * u, 10f * u), CornerRadius(5f * u))
        // Glowing window.
        drawRoundRect(a.sunshine, Offset(c.x - 6f * u, c.y - 9f * u), Size(12f * u, 8f * u), CornerRadius(3f * u))

        // Dish on top.
        drawArc(
            color = a.radio,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(c.x - 10f * u, c.y - 26f * u),
            size = Size(20f * u, 14f * u),
        )
        drawLine(silverShade, Offset(c.x, c.y - 19f * u), Offset(c.x, c.y - 14f * u), strokeWidth = 2.5f * u)

        // Antenna + blinking beacon.
        drawLine(silverShade, Offset(c.x, c.y - 26f * u), Offset(c.x, c.y - 34f * u), strokeWidth = 1.8f * u)
        val beaconAlpha = 0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin(blink * kotlin.math.PI.toFloat() * 2f))
        drawCircle(a.danger.copy(alpha = beaconAlpha), radius = 3f * u, center = Offset(c.x, c.y - 36f * u))
    }
}

/**
 * A chubby rocket. [flame] 0→1 cycle animates the exhaust; 0 draws no flame (parked).
 */
@Composable
fun KurzRocket(modifier: Modifier = Modifier, flame: Float = 0f) {
    val a = SatRideTheme.accents
    Canvas(modifier) {
        val u = size.minDimension / 100f
        val c = Offset(size.width / 2f, size.height / 2f)

        val hull = a.coral
        val hullDark = lerp(hull, a.shadow, 0.35f)

        // Flame first, behind the nozzle.
        if (flame > 0f) {
            val flicker = 1f + 0.18f * kotlin.math.sin(flame * kotlin.math.PI.toFloat() * 6f)
            val flamePath = Path().apply {
                moveTo(c.x - 7f * u, c.y + 30f * u)
                quadraticTo(c.x, c.y + (30f + 26f * flicker) * u, c.x + 7f * u, c.y + 30f * u)
                close()
            }
            drawPath(flamePath, a.sunshine)
            val corePath = Path().apply {
                moveTo(c.x - 3.5f * u, c.y + 30f * u)
                quadraticTo(c.x, c.y + (30f + 15f * flicker) * u, c.x + 3.5f * u, c.y + 30f * u)
                close()
            }
            drawPath(corePath, Color.White.copy(alpha = 0.85f))
        }

        // Fins.
        val finPath = Path().apply {
            moveTo(c.x - 12f * u, c.y + 8f * u)
            quadraticTo(c.x - 24f * u, c.y + 22f * u, c.x - 16f * u, c.y + 30f * u)
            lineTo(c.x - 8f * u, c.y + 24f * u)
            close()
        }
        drawPath(finPath, hullDark)
        val finPath2 = Path().apply {
            moveTo(c.x + 12f * u, c.y + 8f * u)
            quadraticTo(c.x + 24f * u, c.y + 22f * u, c.x + 16f * u, c.y + 30f * u)
            lineTo(c.x + 8f * u, c.y + 24f * u)
            close()
        }
        drawPath(finPath2, hullDark)

        // Body: capsule.
        val body = Path().apply {
            moveTo(c.x, c.y - 34f * u)
            quadraticTo(c.x + 14f * u, c.y - 14f * u, c.x + 12f * u, c.y + 14f * u)
            quadraticTo(c.x + 11f * u, c.y + 26f * u, c.x, c.y + 27f * u)
            quadraticTo(c.x - 11f * u, c.y + 26f * u, c.x - 12f * u, c.y + 14f * u)
            quadraticTo(c.x - 14f * u, c.y - 14f * u, c.x, c.y - 34f * u)
            close()
        }
        drawPath(body, hull)
        // Nose cone tint + porthole.
        drawCircle(Color.White, radius = 7.5f * u, center = Offset(c.x, c.y - 6f * u))
        drawCircle(a.tracking, radius = 5.5f * u, center = Offset(c.x, c.y - 6f * u))
        drawCircle(Color.White.copy(alpha = 0.55f), radius = 2f * u, center = Offset(c.x - 2f * u, c.y - 8f * u))
        // Belly band.
        drawRoundRect(hullDark, Offset(c.x - 11.5f * u, c.y + 16f * u), Size(23f * u, 7f * u), CornerRadius(3.5f * u))
        // Nozzle.
        drawRoundRect(
            lerp(hullDark, a.shadow, 0.4f),
            Offset(c.x - 6f * u, c.y + 25f * u),
            Size(12f * u, 6f * u),
            CornerRadius(2.5f * u),
        )
    }
}

/**
 * A ground station: teal dome base, mast, amber dish, expanding waves. [waves] is a 0→1 cycle;
 * pass 0 for a quiet dish.
 */
@Composable
fun KurzGroundStation(modifier: Modifier = Modifier, waves: Float = 0f) {
    val a = SatRideTheme.accents
    Canvas(modifier) {
        val u = size.minDimension / 100f
        val cx = size.width / 2f
        val ground = size.height * 0.82f

        val teal = Color(0xFF3FE0D0)
        val tealDark = lerp(teal, a.shadow, 0.35f)

        // Expanding waves from the dish focus.
        if (waves > 0f) {
            translate(left = cx - size.width / 2f, top = ground - 52f * u - size.height / 2f) {
                rotate(degrees = -35f, pivot = Offset(size.width / 2f, size.height / 2f)) {
                    drawRadioWaves(a.radio, waves, waves = 3)
                }
            }
        }

        // Dome base with glowing door.
        drawArc(
            teal,
            180f,
            180f,
            useCenter = true,
            topLeft = Offset(cx - 22f * u, ground - 22f * u),
            size = Size(44f * u, 44f * u),
        )
        drawArc(
            tealDark,
            180f,
            76f,
            useCenter = true,
            topLeft = Offset(cx - 22f * u, ground - 22f * u),
            size = Size(44f * u, 44f * u),
        )
        drawRoundRect(a.sunshine, Offset(cx - 5f * u, ground - 12f * u), Size(10f * u, 12f * u), CornerRadius(4f * u))

        // Mast.
        drawLine(
            tealDark,
            Offset(cx, ground - 22f * u),
            Offset(cx, ground - 40f * u),
            strokeWidth = 3f * u,
            cap = StrokeCap.Round,
        )

        // Dish: an amber bowl aimed up-left.
        rotate(degrees = -35f, pivot = Offset(cx, ground - 44f * u)) {
            drawArc(
                color = a.radio,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(cx - 16f * u, ground - 52f * u),
                size = Size(32f * u, 18f * u),
            )
            drawLine(
                lerp(a.radio, a.shadow, 0.4f),
                Offset(cx, ground - 44f * u),
                Offset(cx, ground - 56f * u),
                strokeWidth =
                2.2f * u,
            )
            drawCircle(a.danger, radius = 2.2f * u, center = Offset(cx, ground - 57f * u))
        }

        // Ground line: a soft hill.
        drawArc(
            color = tealDark.copy(alpha = 0.5f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(cx - 60f * u, ground - 6f * u),
            size = Size(120f * u, 24f * u),
        )
    }
}
