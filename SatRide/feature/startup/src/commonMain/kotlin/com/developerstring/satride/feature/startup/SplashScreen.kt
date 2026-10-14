package com.developerstring.satride.feature.startup

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.designsystem.art.SpaceBackground
import com.developerstring.satride.core.designsystem.art.SpinningKurzEarth
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The first thing anyone sees.
 *
 * The app's own vector Earth, turning — the same planet the sign-in screen seals inside its glass
 * bubbles, so the two screens are recognisably one place. It replaced a wireframe globe that
 * assembled its meridians as progress landed: a clever idea that read, on a phone, as a loading
 * spinner having a hard time. A planet that simply rotates says the app is alive without pretending
 * the work and the animation are the same thing.
 *
 * The progress bar is **measured, not animated**: the same rule the catalog's pull-to-refresh
 * follows. It names the step it is on, because a named pause explains itself and a bare bar does
 * not. A bar that invents its own motion tells the user the app is working when it may be stuck,
 * which is exactly when they most need the truth.
 */
@Composable
fun SplashScreen(onReady: () -> Unit, modifier: Modifier = Modifier, viewModel: StartupViewModel = koinViewModel()) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()

    LaunchedEffect(ready) { if (ready) onReady() }

    val animatedFraction by animateFloatAsState(
        targetValue = progress.fraction,
        animationSpec = tween(durationMillis = 400),
        label = "startupFraction",
    )

    SpaceBackground(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.xxl),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                TurningEarth()

                Text("SatRide", style = MaterialTheme.typography.displayMedium)

                OrbitProgress(fraction = animatedFraction, label = progress.currentLabel)
            }
        }
    }
}

/**
 * The Earth, rotating once every twenty seconds, with a satellite in orbit around it.
 *
 * Twenty seconds is slow enough to read as a planet rather than as a spinner — a globe whirling
 * at spinner speed is a spinner — and the satellite's much shorter period gives the eye something
 * visibly moving during the second or two this screen is usually up for.
 */
@Composable
private fun TurningEarth() {
    val transition = rememberInfiniteTransition(label = "earth")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Restart),
        label = "earthSpin",
    )
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing), RepeatMode.Restart),
        label = "satelliteOrbit",
    )

    val accent = SatRideTheme.accents.tracking

    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        // `spin` is passed as a lambda, read inside the draw pass: the planet redraws every frame
        // without recomposing anything.
        SpinningKurzEarth(turns = { spin }, modifier = Modifier.size(168.dp))

        // The orbit ring and its rider, over the planet. The satellite dims through the far half
        // of its lap rather than disappearing, which keeps it findable while still reading as
        // passing behind.
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val rx = size.minDimension * 0.46f
            val ry = rx * 0.34f

            drawOval(
                color = accent.copy(alpha = 0.22f),
                topLeft = Offset(cx - rx, cy - ry),
                size = Size(rx * 2f, ry * 2f),
                style = Stroke(width = 1.5f),
            )

            val theta = orbit * 2f * PI.toFloat()
            val behind = cos(theta) < 0f
            drawCircle(
                color = accent.copy(alpha = if (behind) 0.35f else 1f),
                radius = (if (behind) 3.5.dp else 4.5.dp).toPx(),
                center = Offset(cx + rx * sin(theta), cy + ry * cos(theta)),
            )
        }
    }
}

/**
 * Startup progress, as a track with a bright leading head.
 *
 * The head is the point: a plain bar's edge is hard to locate against a dark track at a glance,
 * and being readable at a glance is this control's entire job during a two-second wait. The
 * percentage is spelled out as well, because on a slow first sync the bar can sit still for
 * seconds at a time, and a number that has not moved is at least unambiguous about that.
 */
@Composable
private fun OrbitProgress(fraction: Float, label: String) {
    val accent = SatRideTheme.accents.tracking
    val track = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(10.dp),
        ) {
            val y = size.height / 2f
            val cap = size.height / 2f
            drawLine(
                color = track,
                start = Offset(cap, y),
                end = Offset(size.width - cap, y),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )

            val span = size.width - cap * 2f
            val head = cap + span * fraction.coerceIn(0f, 1f)
            if (fraction > 0.001f) {
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(accent.copy(alpha = 0.55f), accent),
                        startX = cap,
                        endX = head,
                    ),
                    start = Offset(cap, y),
                    end = Offset(head, y),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
                // The head: a soft halo and a solid core, so the leading edge is findable even
                // when the filled portion is only a few pixels wide.
                drawCircle(accent.copy(alpha = 0.28f), radius = size.height * 1.15f, center = Offset(head, y))
                drawCircle(Color.White, radius = size.height * 0.32f, center = Offset(head, y))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "${(fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                textAlign = TextAlign.End,
            )
        }
    }
}
