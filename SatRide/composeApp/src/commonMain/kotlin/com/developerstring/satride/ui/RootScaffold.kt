package com.developerstring.satride.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.component.SatRideIcon
import com.developerstring.satride.core.designsystem.component.SatRideIconGraphic
import com.developerstring.satride.core.designsystem.theme.MotionSpec
import com.developerstring.satride.navigation.TopLevelTab

/**
 * App chrome: the tab bar and top bar that wrap every destination.
 *
 * The tab bar **floats over full-screen content**. The 5 tab screens are given the whole height and
 * the bar overlays their bottom edge with a translucent background, so the screen shows through it.
 * This is achieved by NOT putting the bar in `Scaffold.bottomBar` (which reserves space) and instead
 * overlaying it in a `Box` on top of the content.
 *
 * On Android and desktop this composable draws its own [GlassTabBar]; on iOS the bar is a native
 * SwiftUI view (`ContentView.swift`) and nothing is drawn here — the same full-screen content is
 * left for the native bar to float over.
 *
 * The Sky tab will eventually render edge-to-edge camera behind translucent chrome (P5); the
 * scaffold is structured so that becomes a property of the destination rather than a rewrite.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScaffold(
    activeTab: TopLevelTab?,
    onTabSelected: (TopLevelTab) -> Unit,
    onSettingsClick: () -> Unit,
    onBack: () -> Unit,
    /** False for full-screen surfaces that own their own chrome, such as the splash. */
    showChrome: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!showChrome) return@Scaffold

            // Always present, but it says different things.
            //
            // On a tab it is the title and the settings entry. On anything else — Settings, the
            // design gallery, a detail screen — it is a **back button**, which is the thing that was
            // missing: those routes were full-screen with no visible way out, so the only way back
            // was the system gesture. That worked on Android and was invisible on desktop, where
            // there is no back gesture at all.
            TopAppBar(
                title = {
                    if (activeTab != null) {
                        Text(text = activeTab.label, style = MaterialTheme.typography.headlineMedium)
                    }
                },
                navigationIcon = {
                    if (activeTab == null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.semantics { contentDescription = "Back" },
                        ) {
                            SatRideIconGraphic(SatRideIcon.Back)
                        }
                    }
                },
                actions = {
                    if (activeTab != null) {
                        IconButton(
                            onClick = onSettingsClick,
                            modifier = Modifier.semantics { contentDescription = "Settings" },
                        ) {
                            SatRideIconGraphic(SatRideIcon.Settings)
                        }
                    }
                },
                colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        content = { innerPadding ->
            val layoutDir = LocalLayoutDirection.current
            // Full-screen content: only the top bar reserves space.
            //
            // **The bottom inset is dropped on purpose.** `Scaffold` puts the navigation-bar inset
            // into `innerPadding`, and passing it on meant every destination stopped short of the
            // bottom of the display — leaving a strip of bare `containerColor` under the content
            // with the gesture handle sitting on it. That strip was the "bottom bar" that would not
            // go away: it was not a bar at all, it was the screen refusing to reach the edge.
            // Nothing draws there now, so each screen's own background runs to the display edge and
            // the system gesture handle floats over real content.
            //
            // Screens whose last row must clear the floating pill add their own bottom content
            // padding — that is a per-screen concern, not the shell's.
            val contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDir),
                top = innerPadding.calculateTopPadding(),
                end = innerPadding.calculateEndPadding(layoutDir),
                bottom = 0.dp,
            )
            Box(Modifier.fillMaxSize()) {
                content(contentPadding)

                // The floating Compose bar, overlaid on top of the full-screen content. iOS renders
                // its own native bar; on those platforms this block is absent.
                //
                // Nothing is drawn in the system navigation-bar inset below the pill. There used to
                // be a transparent→[NavBarColor] gradient there, meant to give the bar a base to
                // float on; what it actually produced was a solid dark band pinned to the bottom of
                // every screen, in a colour that belonged to no surface above it. It read as a
                // second, uglier bar under the real one. The gutter is now fully transparent — the
                // screen's own background runs to the edge of the display and the gesture handle
                // sits directly on it, which is what edge-to-edge is supposed to look like.
                if (!nativeBottomBarActive && showChrome) {
                    AnimatedVisibility(
                        visible = activeTab != null,
                        enter = slideInVertically(MotionSpec.playful()) { it } + fadeIn(MotionSpec.standard()),
                        exit = slideOutVertically(MotionSpec.fast()) { it } + fadeOut(MotionSpec.fast()),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        GlassTabBar(
                            activeTab = activeTab,
                            onTabSelected = onTabSelected,
                        )
                    }
                }
            }
        },
    )
}
