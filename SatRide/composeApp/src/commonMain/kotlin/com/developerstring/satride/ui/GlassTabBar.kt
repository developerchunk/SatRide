package com.developerstring.satride.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.component.SatRideIconGraphic
import com.developerstring.satride.core.designsystem.haptics.LocalSatRideHaptics
import com.developerstring.satride.core.designsystem.theme.MotionSpec
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.navigation.TopLevelTab
import com.developerstring.satride.ui.walkthrough.coachTarget
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.lens
import com.kashif_e.backdrop.highlight.Highlight
import com.kashif_e.backdrop.shadow.Shadow

/**
 * The floating tab bar's solid colour: a near-black navy that matches the dark space palette used
 * elsewhere (e.g. Play's `SpaceBlack`) and reads like iOS's dark glass bar. Shared with the gradient
 * gutter in `RootScaffold` so the bar and its base are the same colour.
 */
internal val NavBarColor: Color = Color(0xFF060B1E)

/** Horizontal inset between the pill's curved ends and the icon group. */
private val BarEdgePad = Spacing.lg

/**
 * The floating tab bar for Android and desktop — the counterpart of the SwiftUI Liquid Glass bar
 * in `ContentView.swift`, built to feel like the same object.
 *
 * ## A lens you pick up, not an indicator that sits there
 *
 * While a finger is down, the bar carries a **transparent disc that refracts whatever is under
 * it**. It has no fill of its own: what shows inside is the bar and the icons, bent through an SDF
 * lens with chromatic dispersion — the same `backdrop` machinery the sign-in screen's bubbles use
 * (AGSL on Android 13+, SkSL on desktop and iOS). It replaces an opaque accent-filled bubble,
 * which hid the icon it was meant to be showing.
 *
 * It exists **only during the gesture**, and it **follows the finger continuously** rather than
 * stepping between tabs. See [GlassSelector] for why both of those matter. At rest the bar is just
 * the bar: the selected tab is said by its accent tint and scale, nothing else.
 *
 * The capture is deliberately scoped to **the bar alone**, not to the whole screen. A full-screen
 * capture would have to be re-recorded on every frame the content behind it moves — which on the
 * Play globe is every frame — and re-run the lens shader with it. The bar is a fixed-size strip
 * that only redraws when a tab changes, so the capsule costs nothing while the screen is busy.
 *
 * ## Why it feels instant
 *
 * The lens appears on **touch-down**. The previous gesture waited for the finger to cross touch
 * slop before anything happened, which is a real delay of however long it takes to move ~8 dp —
 * and if the finger never moved, nothing lifted at all and the tap fell through to a separate code
 * path. One detector now handles both: down lifts the lens, dragging carries it, and lifting
 * commits. There is no slop gate and no long press.
 *
 * The highlight is also driven locally rather than by [activeTab], so it never waits for
 * navigation to round-trip. [activeTab] re-syncs it, which is what makes a tab change from
 * somewhere else — a deep link, a notification — still land on the right tab.
 */
@Composable
internal fun GlassTabBar(
    activeTab: TopLevelTab?,
    onTabSelected: (TopLevelTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalSatRideHaptics.current
    val accent = SatRideTheme.accents.tracking
    val tabs = TopLevelTab.visible
    // The bar only renders on tab destinations, so a null activeTab is treated as "first tab" for
    // highlight purposes only — it never actually shows off-tab.
    val activeIndex = tabs.indexOf(activeTab).coerceAtLeast(0)

    // What the bar has captured of itself, for the selection capsule to refract.
    val barBackdrop = rememberLayerBackdrop()

    var barWidthPx by remember { mutableFloatStateOf(0f) }
    var barHeightPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val edgePadPx = with(density) { BarEdgePad.toPx() }

    /** The tab under the finger, or -1 when nothing is being touched. */
    var pressedIndex by remember { mutableIntStateOf(-1) }

    /**
     * Where the finger is, in bar-local pixels. Read by the lens every frame while a touch is
     * down; meaningless otherwise, because the lens only exists during a touch.
     */
    var fingerXPx by remember { mutableFloatStateOf(0f) }

    /**
     * What the bar believes is selected, ahead of navigation confirming it. Seeded from
     * [activeTab] and re-synced whenever it changes, so an external navigation still wins.
     */
    var localIndex by remember { mutableIntStateOf(activeIndex) }
    LaunchedEffect(activeIndex) { localIndex = activeIndex }

    val highlighted = if (pressedIndex >= 0) pressedIndex else localIndex

    val slotWidthPx = if (barWidthPx > 0f) (barWidthPx - 2f * edgePadPx) / tabs.size else 0f

    fun indexAt(xPx: Float): Int {
        if (slotWidthPx <= 0f) return 0
        return ((xPx - edgePadPx) / slotWidthPx).toInt().coerceIn(0, tabs.lastIndex)
    }

    val barShape = CircleShape

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Capped and centred so the pill stays a control on a tablet rather than stretching
            // into a stripe across the whole window.
            .wrapContentWidth()
            .widthIn(max = Spacing.navBarMaxWidth)
            .fillMaxWidth()
            .navigationBarsPadding()
            // No bottom margin: the pill sits directly above the dark gutter gradient `RootScaffold`
            // draws in the navigation-bar inset, so the bar floats on a solid dark base.
            .padding(start = Spacing.lg, end = Spacing.lg),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The raw bar, captured as it is drawn. `layerBackdrop` draws its content and *then*
        // records it, so what the capsule samples is the finished pill — background, hairline and
        // icons — rather than a reconstruction of it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .layerBackdrop(barBackdrop)
                .onSizeChanged {
                    barWidthPx = it.width.toFloat()
                    barHeightPx = it.height.toFloat()
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NavBarColor, barShape)
                    .border(1.dp, SatRideTheme.accents.hairline, barShape)
                    .pointerInput(tabs.size) {
                        awaitEachGesture {
                            // No `requireUnconsumed`, no slop threshold: the first thing a touch
                            // does is put the lens under the finger.
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            fingerXPx = down.position.x
                            var current = indexAt(down.position.x)
                            pressedIndex = current
                            haptics.tap()

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: continue
                                change.consume()

                                // The lens tracks the finger continuously; the index is only what
                                // gets *tinted* and, on release, selected. Those are two different
                                // things on purpose — see [GlassSelector].
                                fingerXPx = change.position.x
                                val hovered = indexAt(change.position.x)
                                if (hovered != current) {
                                    current = hovered
                                    pressedIndex = hovered
                                    haptics.tap()
                                }

                                if (!change.pressed) {
                                    pressedIndex = -1
                                    // Locally first so the tint settles on this frame;
                                    // navigation confirms it a beat later.
                                    localIndex = current
                                    onTabSelected(tabs[current])
                                    break
                                }
                            }
                        }
                    }
                    .padding(horizontal = BarEdgePad, vertical = Spacing.lg),
            ) {
                tabs.forEachIndexed { index, tab ->
                    TabCell(
                        tab = tab,
                        selected = index == highlighted,
                        accent = accent,
                        // Each cell reports its measured bounds so the walkthrough's spotlight
                        // can land exactly on it, whatever the bar's height works out to be on
                        // this device.
                        modifier = Modifier.weight(1f).coachTarget(tab.name),
                    )
                }
            }
        }

        // The lens. It exists ONLY while a finger is down: at rest the bar is just the bar, and
        // the selected tab is said by its accent tint alone. A lens parked on a tab reads as a
        // decoration stuck to the UI; a lens that appears under your finger reads as an object you
        // picked up.
        //
        // Exactly the bar's height, not taller: it shares the outer Box with the pill, so a taller
        // one would grow the Box and lift the bar off the gutter gradient beneath it.
        AnimatedVisibility(
            visible = pressedIndex >= 0 && slotWidthPx > 0f && barHeightPx > 0f,
            enter = fadeIn(MotionSpec.fast()) + scaleIn(MotionSpec.fast(), initialScale = 0.6f),
            exit = fadeOut(MotionSpec.fast()) + scaleOut(MotionSpec.fast(), targetScale = 0.6f),
        ) {
            GlassSelector(
                backdrop = barBackdrop,
                widthPx = slotWidthPx,
                heightPx = barHeightPx,
                fingerXPx = { fingerXPx },
                barWidthPx = barWidthPx,
            )
        }
    }
}

/**
 * The lens the finger carries: a disc of pure refraction that floats freely over the bar.
 *
 * ## It follows the finger, it does not step between tabs
 *
 * The position is [fingerXPx] — a continuous pixel coordinate — not a tab slot. Snapping the lens
 * to slots made it jump from icon to icon, which reads as a *selection indicator being moved*
 * rather than as a piece of glass being slid across a surface. Glass does not teleport. It sits
 * wherever the hand puts it, including halfway between two icons.
 *
 * A stiff, non-bouncy spring smooths it. Pointer events arrive in bursts and are not aligned to
 * the frame clock, so binding the raw coordinate straight to the layer would stutter on exactly the
 * slow drags where smoothness is most visible. The spring interpolates between events and gives
 * the lens a trace of weight — it settles into the finger rather than being welded to it — which
 * is what the iOS selector's liquidity actually is.
 *
 * The tab that gets *selected* is still decided by which slot the finger is over, and is shown by
 * the icon tint. Position and selection are deliberately two different things: the lens is where
 * your hand is, the tint is what will happen if you let go.
 *
 * ## The material
 *
 * No blur and no frost — only refraction and a rim. A blur pass would smear the icon underneath
 * into an unreadable smudge, and a frost would defeat the transparency the whole component exists
 * for. The lens alone is what makes the bar's edge visibly bend as it crosses.
 *
 * Position is a graphics-layer translation, not padding: it changes every frame, and doing that in
 * layout would relayout the bar on each one.
 */
@Composable
private fun GlassSelector(
    backdrop: Backdrop,
    widthPx: Float,
    heightPx: Float,
    fingerXPx: () -> Float,
    barWidthPx: Float,
) {
    val density = LocalDensity.current
    val width = with(density) { widthPx.toDp() }
    val height = with(density) { heightPx.toDp() }

    // Centred on the finger, kept inside the pill so the lens never hangs off the curved ends.
    // `animateFloatAsState` seeds itself from the first target it sees — and this composable is
    // only created when the touch begins — so the lens materialises *under* the finger instead of
    // flying in from wherever the last gesture left it.
    val targetX = (fingerXPx() - widthPx / 2f).coerceIn(0f, (barWidthPx - widthPx).coerceAtLeast(0f))
    val x by animateFloatAsState(
        targetValue = targetX,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
        label = "tabLensX",
    )

    Box(
        Modifier
            .size(width, height)
            .graphicsLayer { translationX = x }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    lens(
                        refractionHeight = size.minDimension * 0.28f,
                        refractionAmount = size.minDimension * 0.55f,
                        chromaticAberration = true,
                    )
                },
                highlight = { Highlight.Default },
                shadow = { Shadow(radius = 10.dp, color = Color.Black.copy(alpha = 0.35f)) },
                // A whisper of white so the capsule still has an edge on devices with no
                // RuntimeShader support (Android 12 and below), where `lens` is a no-op and
                // refraction alone would leave nothing to see.
                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.06f)) },
            ),
    )
}

/** A single evenly-weighted tab. Selection is tint + scale only — the capsule does the rest. */
@Composable
private fun TabCell(tab: TopLevelTab, selected: Boolean, accent: Color, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MotionSpec.standard(),
        label = "tabTint",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.15f else 1f,
        animationSpec = MotionSpec.springPop(),
        label = "tabScale",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = Spacing.sm),
    ) {
        SatRideIconGraphic(
            tab.icon,
            tint = tint,
            size = 24.dp,
            modifier = Modifier.graphicsLayer {
                scaleX = iconScale
                scaleY = iconScale
            },
        )
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}
