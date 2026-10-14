package com.developerstring.satride.feature.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.art.SpaceBackground
import com.developerstring.satride.core.designsystem.component.SatRideWideButton
import com.developerstring.satride.core.designsystem.haptics.LocalSatRideHaptics
import com.developerstring.satride.core.designsystem.theme.MotionSpec
import com.developerstring.satride.core.designsystem.theme.SatRideAccents
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.absoluteValue

/** One onboarding page: its words, its mood colour, and its illustrated scene. */
private class OnboardingPage(
    val title: String,
    val body: String,
    val tint: (SatRideAccents) -> Color,
    val scene: @Composable (Modifier) -> Unit,
)

private val pages = listOf(
    OnboardingPage(
        title = "Welcome to SatRide",
        body = "Thousands of satellites are flying over you right now. Let's go meet them.",
        tint = { it.skyBlue },
        scene = { WelcomeScene(it) },
    ),
    OnboardingPage(
        title = "Point at the sky",
        body = "Aim your phone up and SatRide shows every satellite sailing past — live, in AR.",
        tint = { it.tracking },
        scene = { SkyScene(it) },
    ),
    OnboardingPage(
        title = "Never miss a pass",
        body = "SatRide predicts exactly when your favourites rise above your horizon, and nudges you in time.",
        tint = { it.lavender },
        scene = { PassesScene(it) },
    ),
    OnboardingPage(
        title = "Hear them talk",
        body = "Plug in an RTL-SDR radio and tune into weather photos, telemetry and voices from orbit.",
        tint = { it.radio },
        scene = { RadioScene(it) },
    ),
    OnboardingPage(
        title = "Ready for launch?",
        body = "Your ground station is set. The sky is waiting.",
        tint = { it.coral },
        scene = { LaunchScene(it) },
    ),
)

/**
 * The first-run tour: five illustrated, animated scenes in a pager, ending on the launch
 * button. Skippable from every page — the tour is a gift, not a toll gate.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val haptics = LocalSatRideHaptics.current
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val accents = SatRideTheme.accents

    // A light tick as each page settles; skip the initial settle on page 0.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.drop(1).collect { haptics.tap() }
    }

    val tint by animateColorAsState(
        targetValue = pages[pagerState.currentPage].tint(accents),
        animationSpec = MotionSpec.standard(),
        label = "onboardingTint",
    )

    val onLastPage = pagerState.currentPage == pages.lastIndex

    SpaceBackground(modifier = modifier.fillMaxSize(), tint = tint) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { viewModel.complete(onDone) }) {
                    Text(
                        "Skip",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                val spec = pages[page]
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The scene drifts at half the page speed — cheap parallax that makes the
                    // pager feel layered.
                    val sceneParallax = Modifier.graphicsLayer {
                        val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                        translationX = offset * size.width * 0.35f
                        alpha = 1f - offset.absoluteValue * 0.35f
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        spec.scene(sceneParallax.fillMaxSize())
                    }
                    Text(
                        text = spec.title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = spec.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xl),
                    )
                }
            }

            PageDots(
                count = pages.size,
                current = pagerState.currentPage,
                accent = tint,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            SatRideWideButton(
                text = if (onLastPage) "Blast off!" else "Continue",
                accent = if (onLastPage) accents.sunshine else accents.tracking,
                onClick = {
                    if (onLastPage) {
                        haptics.success()
                        viewModel.complete(onDone)
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(
                                pagerState.currentPage + 1,
                                animationSpec = MotionSpec.playful(),
                            )
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            )
        }
    }
}

/** The pager indicator: sleeping dots and one wide, springy active pill. */
@Composable
private fun PageDots(count: Int, current: Int, accent: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(
                targetValue = if (selected) 28.dp else 9.dp,
                animationSpec = MotionSpec.springPop(),
                label = "dotWidth",
            )
            Box(
                Modifier
                    .width(width)
                    .height(9.dp)
                    .background(
                        color = if (selected) accent else SatRideTheme.accents.hairline,
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}
