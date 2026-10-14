package com.developerstring.satride.feature.auth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.designsystem.art.StarField
import com.developerstring.satride.core.designsystem.art.rememberPlanetBitmaps
import com.developerstring.satride.core.designsystem.art.rememberSceneCycle
import com.developerstring.satride.core.designsystem.haptics.LocalSatRideHaptics
import com.developerstring.satride.core.designsystem.theme.MotionSpec
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.core.web.LegalPage
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.lens
import com.kashif_e.backdrop.effects.vibrancy
import com.kashif_e.backdrop.highlight.Highlight
import com.kashif_e.backdrop.shadow.Shadow
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The login moment, staged as one continuous liquid-glass choreography (Wabi-style), on the
 * app's own dark stage:
 *
 * 1. **Intro** — a giant glass dome breathes at the bottom edge under the headline, with
 *    "Swipe up to enter".
 * 2. **Arriving** — the swipe sends the bubble travelling up the screen; a blue liquid band
 *    sloshes inside it while it shrinks, and the intro text dissolves.
 * 3. **Ready** — the bubble settles into a small white "+" orb card, the title fades in
 *    beneath it, and a continuous stream of liquid-glass bubbles — planets sealed inside —
 *    rises from behind the orb and leaves through the top of the screen, slow while small and
 *    accelerating as they grow.
 *
 * The bubble is one object throughout: the same circle morphs across all three phases. The
 * stream bubbles are *real* liquid glass — they sample the scene behind them through the
 * `backdrop` library's lens/blur shaders (AGSL on Android 13+, SkSL on iOS/desktop) rather
 * than faking it with translucent fills.
 */
@Composable
fun SignInScreen(
    onSignedIn: () -> Unit,
    onNeedsProfile: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SignInViewModel = koinViewModel(),
    onMessage: (String) -> Unit = {},
    /** Opens one of the legal pages in the in-app web view. */
    onOpenLegal: (LegalPage) -> Unit = {},
    /** "Skip" when this screen is the first-run stack root; "Back" when pushed from Profile. */
    skipLabel: String = "Skip",
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberGoogleSignIn()

    LaunchedEffect(launcher.isAvailable) { viewModel.onLauncherAvailability(launcher.isAvailable) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SignInEffect.Done -> onSignedIn()
                SignInEffect.NeedsProfile -> onNeedsProfile()
                is SignInEffect.ShowMessage -> onMessage(effect.text)
            }
        }
    }

    SignInContent(
        signingIn = state.signingIn,
        signInAvailable = state.signInAvailable,
        error = state.error,
        onGoogle = { viewModel.signIn { launcher.requestTokens() } },
        onSkip = onSkip,
        modifier = modifier,
        onOpenLegal = onOpenLegal,
        skipLabel = skipLabel,
    )
}

/** The three beats of the choreography. */
enum class SignInPhase { Intro, Arriving, Ready }

/**
 * "By continuing you agree to … Terms … and Privacy Policy", with the two documents as real links.
 *
 * Uses `LinkAnnotation` rather than the old `ClickableText`/`pointerInput` dance: it is the
 * accessible route — a screen reader announces the spans as links and can activate them — and it
 * keeps hit-testing to the words themselves, so a tap on the surrounding sentence does nothing
 * instead of firing whichever link happens to be nearest.
 */
@Composable
private fun LegalConsentText(onOpenLegal: (LegalPage) -> Unit) {
    val linkStyle = SpanStyle(
        color = InkBright,
        textDecoration = TextDecoration.Underline,
    )

    val text = buildAnnotatedString {
        append("By continuing you agree to SatRide's ")
        withLink(LinkAnnotation.Clickable("terms", TextLinkStyles(linkStyle)) { onOpenLegal(LegalPage.TERMS) }) {
            append("Terms of Service")
        }
        append(" and ")
        withLink(LinkAnnotation.Clickable("privacy", TextLinkStyles(linkStyle)) { onOpenLegal(LegalPage.PRIVACY) }) {
            append("Privacy Policy")
        }
        append(".")
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = InkSoft,
        textAlign = TextAlign.Center,
    )
}

private val InkBright = Color(0xFFF4F6FF)
private val InkSoft = Color(0xFFA8B3E6)
private val LiquidBlue = Color(0xFF2F6BFF)
private val LiquidCyan = Color(0xFF35D6E8)
private val OrbRadius = 56.dp

/** Stateless so the offscreen render test can draw it; [initialPhase] lets tests skip ahead. */
@Composable
fun SignInContent(
    signingIn: Boolean,
    signInAvailable: Boolean,
    error: String?,
    onGoogle: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLegal: (LegalPage) -> Unit = {},
    initialPhase: SignInPhase = SignInPhase.Intro,
    skipLabel: String = "Skip",
) {
    val haptics = LocalSatRideHaptics.current
    // Two capture layers: the stream bubbles refract the scene; the hero circle and the
    // button sample scene AND stream, so planets passing behind the plus show through its
    // glass — which needs them inside the layer the plus looks at.
    val sceneBackdrop = rememberLayerBackdrop()
    val fullBackdrop = rememberLayerBackdrop()

    var phase by remember { mutableStateOf(initialPhase) }
    // 0 = giant bubble at the bottom edge, 1 = settled "+" orb. One value drives the morph.
    val travel = remember { Animatable(if (initialPhase == SignInPhase.Ready) 1f else 0f) }

    LaunchedEffect(phase) {
        if (phase == SignInPhase.Arriving) {
            travel.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1500, easing = CubicBezierEasing(0.30f, 0f, 0.18f, 1f)),
            )
            haptics.success()
            phase = SignInPhase.Ready
        }
    }

    val readyT by animateFloatAsState(
        targetValue = if (phase == SignInPhase.Ready) 1f else 0f,
        animationSpec = MotionSpec.celebratory(),
        label = "signInReady",
    )

    val cycle = rememberSceneCycle(periodMillis = 4200, label = "bubbleBreath")

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(phase) {
                if (phase == SignInPhase.Intro) {
                    var pulled = 0f
                    detectVerticalDragGestures(
                        onDragStart = { pulled = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            pulled += dragAmount
                            if (pulled < -60f) {
                                haptics.tap()
                                phase = SignInPhase.Arriving
                            }
                        },
                    )
                }
            },
    ) {
        val w = maxWidth
        val h = maxHeight
        val orbCenterYFrac = 0.34f

        // The outer capture holds everything the hero circle looks through: the scene AND the
        // stream. The inner capture is what the stream bubbles themselves refract.
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(fullBackdrop),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(sceneBackdrop),
            ) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                // Static stars: any animation here would invalidate the captured layer every
                // frame and force every glass shader on screen to re-run.
                StarField(starCount = 110, twinkle = false, modifier = Modifier.fillMaxSize())
            }

            // The continuous stream: liquid-glass planet bubbles born behind the plus, rising
            // in straight rays across the full width and out of the screen.
            if (phase == SignInPhase.Ready) {
                BubbleStream(backdrop = sceneBackdrop, w = w, h = h, orbCenterYFrac = orbCenterYFrac)
            }

            // The copy lives INSIDE the capture, beneath the hero: when the glass circle
            // travels over a headline, the lens visibly warps the letters — the reference's
            // signature moment. (The Google pill stays outside; it is glass itself.)
            val introAlpha = (1f - travel.value * 3f).coerceIn(0f, 1f)
            if (introAlpha > 0f) {
                Text(
                    text = "A new era of\nsky watching is here.",
                    style = MaterialTheme.typography.headlineLarge,
                    color = InkBright,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = (-h * 0.10f))
                        .graphicsLayer { alpha = introAlpha },
                )
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = InkSoft)) { append("Swipe ") }
                        withStyle(SpanStyle(color = InkBright.copy(alpha = 0.85f))) { append("up to enter") }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = Spacing.xxl + Spacing.lg)
                        .graphicsLayer {
                            alpha = introAlpha
                            translationY = sin(cycle * 2f * PI.toFloat()) * 4.dp.toPx()
                        },
                )
            }

            if (readyT > 0.01f) {
                Text(
                    text = "Meet SatRide.\nSatellites, passes and\nsignals — in your hand.",
                    style = MaterialTheme.typography.headlineLarge,
                    color = InkBright,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = h * orbCenterYFrac + 84.dp)
                        .graphicsLayer {
                            alpha = readyT
                            translationY = (1f - readyT) * 24.dp.toPx()
                        },
                )
            }
        }

        // The hero: ONE liquid-glass circle for its entire life — the breathing dome at the
        // bottom edge, the travelling bubble, and the settled plus card. Its size and position
        // are tracked per frame, and the glass samples scene, stream AND text throughout, so
        // whatever it passes over bends through it.
        GlassHero(
            backdrop = fullBackdrop,
            travel = { travel.value },
            readyT = { readyT },
            breath = { cycle },
            orbCenterYFrac = orbCenterYFrac,
            modifier = Modifier.fillMaxSize(),
        )

        // The single glass control below the reveal.
        if (readyT > 0.01f) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.xl)
                    .graphicsLayer {
                        alpha = readyT
                        translationY = (1f - readyT) * 48.dp.toPx()
                    },
            ) {
                GlassGoogleButton(
                    loading = signingIn,
                    enabled = signInAvailable && !signingIn,
                    backdrop = fullBackdrop,
                    onClick = onGoogle,
                )

                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = SatRideTheme.accents.danger,
                        textAlign = TextAlign.Center,
                    )
                }

                if (!signInAvailable) {
                    Text(
                        text = "Google sign-in is not available in this build.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                        textAlign = TextAlign.Center,
                    )
                }

                LegalConsentText(onOpenLegal = onOpenLegal)
            }
        }

        // The way out, always visible and matching onboarding's Skip. Arriving from the
        // Profile tab this is effectively "back"; arriving after onboarding it skips the
        // account and goes straight to the app. Outside the captures so no glass warps it.
        TextButton(
            onClick = {
                haptics.tap()
                onSkip()
            },
            enabled = !signingIn,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        ) {
            Text(skipLabel, style = MaterialTheme.typography.labelLarge, color = InkSoft)
        }
    }
}

/**
 * The hero: ONE liquid-glass circle for its entire life. A custom layout tracks its size and
 * position every frame — giant dome breathing at the bottom edge, shrinking as it travels on
 * the swipe, settling as the plus card — and `drawBackdrop` samples the scene through it the
 * whole way, with the lens scaled to whatever size the circle currently is. Transparent glass:
 * only the faintest frost, so planets streaming behind the plus stay visible through it.
 */
@Composable
private fun GlassHero(
    backdrop: Backdrop,
    travel: () -> Float,
    readyT: () -> Float,
    breath: () -> Float,
    orbCenterYFrac: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Box(
            modifier = Modifier
                .layout { measurable, constraints ->
                    // Per-frame values read HERE: updating them re-runs layout and draw only.
                    val wPx = constraints.maxWidth.toFloat()
                    val hPx = constraints.maxHeight.toFloat()
                    val t = travel()
                    val introR = wPx * 0.95f
                    val r = lerp(introR, OrbRadius.toPx(), t)
                    // The intro dome sits mostly below the bottom edge; only its crown shows.
                    val cy = lerp(hPx * 0.66f + introR, hPx * orbCenterYFrac, t)
                    val d = (r * 2f).roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(d, d))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place((wPx / 2f - r).roundToInt(), (cy - r).roundToInt())
                    }
                }
                .graphicsLayer {
                    // Breathing as a layer transform, so the idle dome never relayouts.
                    val t = travel()
                    val breathe = if (t == 0f) sin(breath() * 2f * PI.toFloat()) else 0f
                    val s = 1f + 0.008f * breathe
                    scaleX = s
                    scaleY = s
                    translationY = breathe * 5.dp.toPx()
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    // Pure lens, nothing else: no blur, no highlight stroke, no frost tint.
                    // The circle is invisible except for what it bends — which is exactly what
                    // makes it read as glass when it crosses the headline or a planet.
                    effects = {
                        lens(
                            refractionHeight = size.minDimension * 0.20f,
                            refractionAmount = size.minDimension * 0.42f,
                            chromaticAberration = true,
                        )
                    },
                    shadow = { Shadow(radius = 16.dp, color = Color.Black.copy(alpha = 0.30f)) },
                ),
        ) {
            // The life inside the glass: the sloshing liquid while it flies, the plus once it
            // has settled. Drawn as a child so the lens above never distorts it.
            Canvas(Modifier.fillMaxSize()) {
                val t = travel()
                val ready = readyT()
                val cyc = breath()
                val radius = size.minDimension / 2f
                val cx = radius
                val cy = radius
                val disc = Path().apply {
                    addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
                }

                val slosh = sin(PI.toFloat() * t)
                if (slosh > 0.02f) {
                    clipPath(disc) {
                        val bandY = cy + radius * (0.35f - 0.25f * t) +
                            sin(cyc * 2f * PI.toFloat()) * radius * 0.04f
                        val band = Path().apply {
                            moveTo(cx - radius, bandY - radius * 0.10f)
                            quadraticTo(cx, bandY + radius * 0.55f, cx + radius, bandY - radius * 0.10f)
                        }
                        // The blue liquid alone — no white overlay line: the transition is
                        // allowed its blue, and nothing else.
                        drawPath(
                            band,
                            brush = Brush.verticalGradient(
                                listOf(
                                    LiquidCyan.copy(alpha = 0.0f),
                                    LiquidBlue.copy(alpha = 0.9f),
                                    LiquidCyan.copy(alpha = 0.25f),
                                ),
                                startY = bandY - radius * 0.3f,
                                endY = bandY + radius * 0.7f,
                            ),
                            style = Stroke(width = radius * 0.34f, cap = StrokeCap.Round),
                            alpha = slosh * 0.95f,
                        )
                    }
                }

                if (ready > 0.01f) {
                    val arm = radius * 0.34f * ready
                    val plusStroke = radius * 0.085f
                    drawLine(
                        InkBright.copy(alpha = ready),
                        Offset(cx - arm, cy),
                        Offset(cx + arm, cy),
                        strokeWidth = plusStroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        InkBright.copy(alpha = ready),
                        Offset(cx, cy - arm),
                        Offset(cx, cy + arm),
                        strokeWidth = plusStroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * One emitter slot. The slot's period and start delay are fixed; everything visible about a
 * given flight — ray angle, planet, size — re-rolls per cycle from a seeded hash, so releases
 * look random and no slot ever shows the same planet twice in a row.
 */
private class StreamSlot(val seed: Int) {
    val periodMillis: Float = 5200f + (seed * 577) % 3200

    /**
     * The opening: a handful of scouts launch in the first moments, then the rest join one
     * after another — a continuous release from the first second, never in waves.
     */
    val startDelayMillis: Float =
        if (seed < 6) (seed * 90).toFloat() else 600f + (seed - 6) * 260f + (seed * 131) % 240

    val isSmall: Boolean = seed % 6 == 5

    /**
     * Layout size is the flight's maximum; the later releases run a little bigger.
     *
     * Scaled up from the original (small fillers 18→24, scouts 56→68, rest 72→88) so the planets
     * read at a glance. Bubble size spends flicker headroom on iOS — bigger offscreen layers cost
     * more to composite — so this is balanced against the 12-bubble count, which is the ceiling on
     * an iPhone 16 Pro. If the delayed flicker returns, this and the count are the two dials.
     */
    val maxSize: Dp = when {
        isSmall -> (24 + (seed * 89) % 10).dp
        seed < 6 -> (68 + (seed * 191) % 28).dp
        else -> (88 + (seed * 191) % 44).dp
    }
}

// Each slot is one permanently-composed bubble whose liquid-glass lens re-composites every frame.
// On iOS/Metal a high enough count of those concurrent offscreen lens passes is what flickers —
// not immediately, but once the stream has filled up to full density after the first few seconds.
// 30 flickered, 12 was clean, 16 brought a small flicker back: 12 is the threshold on an iPhone 16
// Pro, so this is the number to hold. Density is recovered through the planets instead (they scale
// inside the existing layer, which costs nothing to composite), not through more bubbles.
private val StreamSlots = List(12) { StreamSlot(it) }

/** Deterministic per-(slot, cycle) randomness in 0..1 — xorshift over the three inputs. */
private fun roll(seed: Int, cycle: Int, salt: Int): Float {
    var x = (seed * 73_856_093) xor (cycle * 19_349_663) xor (salt * 83_492_791)
    x = x xor (x shl 13)
    x = x xor (x ushr 17)
    x = x xor (x shl 5)
    return ((x ushr 8) and 0xFFFF) / 65535f
}

/**
 * The planet for this flight: a random walk whose step is always 1..4, so consecutive flights
 * from one slot can never repeat a planet.
 */
private fun planetFor(seed: Int, cycle: Int): Int {
    var idx = (seed * 7 + 3) % 5
    for (c in 0..cycle) {
        idx = (idx + 1 + (roll(seed, c, 5) * 3.999f).toInt()) % 5
    }
    return idx
}

/** Where a slot is in its current flight: fractional progress plus the flight's index. */
private fun flightOf(millis: Float, slot: StreamSlot): Pair<Float, Int>? {
    val local = millis - slot.startDelayMillis
    if (local < 0f) return null
    val cycle = (local / slot.periodMillis).toInt()
    return (local / slot.periodMillis - cycle) to cycle
}

/**
 * The continuous emitter. Each slot loops forever: born small behind the plus card, a bubble
 * rises along its flight's ray, swelling and accelerating — position and scale both follow an
 * ease-in curve, so it is slow while small ("far away") and fast by the time it exits, which
 * is what gives the stream its depth.
 */
@Composable
private fun BubbleStream(backdrop: Backdrop, w: Dp, h: Dp, orbCenterYFrac: Float) {
    // A monotonically increasing frame clock: no wrap, so no once-a-minute stutter. Read only
    // inside placement/draw lambdas — the stream never recomposes while animating.
    var elapsedNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { elapsedNanos = it - start }
        }
    }

    // Planets baked to bitmaps once: a planet is then a single drawImage per frame instead of
    // dozens of tessellated vector paths — the difference between 60 fps and jank with twenty
    // bubbles aloft. Baking also lets a bubble swap planets between flights at DRAW time,
    // with no recomposition.
    val planetBitmaps = rememberPlanetBitmaps()
    val density = LocalDensity.current
    val wPx = with(density) { w.toPx() }
    val hPx = with(density) { h.toPx() }

    StreamSlots.forEach { slot ->
        val sizePx = with(density) { slot.maxSize.toPx() }

        GlassBubble(
            size = slot.maxSize,
            backdrop = backdrop,
            planetPainter = if (slot.isSmall) {
                null
            } else {
                painter@{
                    val flight = flightOf(elapsedNanos / 1_000_000f, slot) ?: return@painter
                    val bitmap = planetBitmaps[planetFor(slot.seed, flight.second)]
                    val d = size.minDimension
                    val inset = (d * 0.125f).roundToInt()
                    val side = (d * 0.75f).roundToInt()
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset(inset, inset),
                        dstSize = IntSize(side, side),
                        filterQuality = FilterQuality.High,
                    )
                }
            },
            modifier = Modifier
                .offset {
                    val flight = flightOf(elapsedNanos / 1_000_000f, slot)
                        ?: return@offset IntOffset(0, -(sizePx * 4f).roundToInt())
                    val (t, cycle) = flight
                    val ease = t * t
                    val angle = (-64f + 128f * roll(slot.seed, cycle, 1)) * (PI.toFloat() / 180f)
                    val travelPx = hPx * 0.62f + sizePx * 2f
                    val x = wPx / 2f + sin(angle) * travelPx * ease - sizePx / 2f
                    val y = hPx * orbCenterYFrac - cos(angle) * travelPx * ease - sizePx / 2f
                    IntOffset(x.roundToInt(), y.roundToInt())
                }
                .graphicsLayer {
                    val flight = flightOf(elapsedNanos / 1_000_000f, slot)
                    if (flight == null) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val (t, cycle) = flight
                    val ease = t * t
                    // Per-flight size roll on top of the eased growth. Capped lower than before: a
                    // bubble ballooning past ~2x and leaving through the top edge is large, edge-
                    // clipped, and behind a lens RenderEffect in its own offscreen layer — and on
                    // iOS/Metal that combination is what flickered. It still grows for depth, just
                    // not far enough to reach the flicker-prone state.
                    val grow = (0.26f + 1.35f * ease) * (0.8f + 0.35f * roll(slot.seed, cycle, 2))
                    scaleX = grow
                    scaleY = grow
                    // Fade in fast, then fade out over the last third of the flight. The fade-out is
                    // the real fix for the iOS flicker: a bubble has dissolved to nothing before it
                    // is large and at the screen edge, so the flicker-prone state is never on screen
                    // at full alpha. It reads as the bubble dissipating into the distance, which is
                    // the intended depth cue anyway.
                    val fadeIn = (t / 0.05f).coerceAtMost(1f)
                    val fadeOut = ((1f - t) / 0.30f).coerceIn(0f, 1f)
                    alpha = fadeIn * fadeOut
                },
        )
    }
}

/**
 * A liquid-glass bubble: `drawBackdrop` runs the SDF lens with chromatic dispersion over the
 * scene behind it. Lens only — no blur or vibrancy passes — so twenty of these stay cheap; on
 * a dark stage the extra passes were invisible anyway. The planet is painted inside the glass
 * at draw time from a baked bitmap.
 */
@Composable
private fun GlassBubble(
    size: Dp,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    planetPainter: (DrawScope.() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    lens(
                        refractionHeight = (size * 0.34f).toPx(),
                        refractionAmount = (size * 0.70f).toPx(),
                        chromaticAberration = true,
                    )
                },
                highlight = { Highlight.Ambient },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // A faint soap-film sweep so plain bubbles still carry a whisper of colour.
            val d = this.size.minDimension
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        LiquidBlue.copy(alpha = 0.10f),
                        Color(0xFFFF7AD9).copy(alpha = 0.08f),
                        Color(0xFFFFB13D).copy(alpha = 0.08f),
                        LiquidCyan.copy(alpha = 0.10f),
                        LiquidBlue.copy(alpha = 0.10f),
                    ),
                    center = Offset(d * 0.42f, d * 0.40f),
                ),
                radius = d / 2f,
                center = Offset(d / 2f, d / 2f),
            )
            planetPainter?.invoke(this)
        }
    }
}

/** The liquid-glass pill: backdrop-sampled glass with a light frost so the label stays legible. */
@Composable
private fun GlassGoogleButton(loading: Boolean, enabled: Boolean, backdrop: Backdrop, onClick: () -> Unit) {
    val haptics = LocalSatRideHaptics.current
    val shape = RoundedCornerShape(28.dp)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(8.dp.toPx())
                    lens(14.dp.toPx(), 28.dp.toPx(), chromaticAberration = true)
                },
                highlight = { Highlight.Default },
                shadow = { Shadow(radius = 14.dp, color = Color.Black.copy(alpha = 0.35f)) },
                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.10f)) },
            )
            .clickable(enabled = enabled) {
                haptics.press()
                onClick()
            },
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = SatRideTheme.accents.tracking,
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoogleGlyph(Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.md))
                Text(
                    text = "Continue with Google",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) InkBright else InkSoft,
                )
            }
        }
    }
}

/**
 * The four-colour G as arcs. Angles follow the real mark: the stroke opens at the top-right,
 * blue runs from the horizontal bar down to the bottom-right where green takes over.
 */
@Composable
private fun GoogleGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.22f
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)

        fun arc(color: Color, start: Float, sweep: Float) = drawArc(
            color = color,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
        arc(Color(0xFF4285F4), 0f, 45f) // blue: bar to bottom-right
        arc(Color(0xFF34A853), 45f, 90f) // green: across the bottom
        arc(Color(0xFFFBBC05), 135f, 90f) // yellow: up the left
        arc(Color(0xFFEA4335), 225f, 90f) // red: across the top, ending before the gap
        // The blue bar into the centre that makes it a G rather than a C.
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(size.width * 0.52f, size.height / 2f),
            end = Offset(size.width - inset + stroke * 0.5f, size.height / 2f),
            strokeWidth = stroke,
        )
    }
}
