package com.developerstring.satride.feature.play.multiplay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.designsystem.haptics.LocalSatRideHaptics
import com.developerstring.satride.core.designsystem.theme.MotionSpec
import com.developerstring.satride.feature.play.multiplay.MultiPlayStage
import com.developerstring.satride.feature.play.multiplay.MultiPlayViewModel
import com.developerstring.satride.feature.play.multiplay.engine.GameEventKind
import com.developerstring.satride.feature.play.multiplay.engine.GameMode
import com.developerstring.satride.feature.play.multiplay.platform.ImmersiveLandscape
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

/**
 * Multi-Play: the whole game in one landscape, full-screen surface.
 *
 * Home (pilot, modes, rooms, leaderboard) → Lobby (a room's roster and the host's start button)
 * → Arena (the race) → Results, all owned by one view model so leaving the arena for the
 * results does not lose the room, and leaving the screen entirely frees the seat.
 */
@Composable
fun MultiPlayScreen(
    onExit: () -> Unit,
    /** A [GameMode.id] to land on, e.g. "solar" from the Play tab's Solar System button. */
    initialMode: String? = null,
    viewModel: MultiPlayViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ImmersiveLandscape()
    LaunchedEffect(initialMode) {
        if (initialMode != null) viewModel.selectMode(GameMode.fromId(initialMode))
    }

    // Haptics for what the game does to the player, from the events the rules raise.
    val haptics = LocalSatRideHaptics.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event.kind) {
                GameEventKind.CRASH, GameEventKind.TAGGED, GameEventKind.OUT -> haptics.success()

                GameEventKind.GATE, GameEventKind.PERFECT_GATE, GameEventKind.LAP, GameEventKind.FINISH,
                GameEventKind.ORB, GameEventKind.DRIFT_BOOST, GameEventKind.TAG_PASSED,
                GameEventKind.DISCOVERY, GameEventKind.SUN_GRAZE, GameEventKind.RING_DIVE, GameEventKind.GRAND_TOUR,
                -> haptics.press()

                GameEventKind.BOUNCE -> haptics.success()

                else -> haptics.tap()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(ArenaBlack)) {
        AnimatedContent(
            targetState = state.stage,
            transitionSpec = { fadeIn(MotionSpec.standard()) togetherWith fadeOut(MotionSpec.fast()) },
            label = "multiplayStage",
        ) { stage ->
            when (stage) {
                MultiPlayStage.HOME -> HomePane(state = state, viewModel = viewModel, onExit = onExit)

                MultiPlayStage.LOBBY -> LobbyPane(state = state, viewModel = viewModel)

                MultiPlayStage.ARENA -> if (state.selectedMode.isSolar) {
                    SolarPane(state = state, viewModel = viewModel)
                } else {
                    ArenaPane(state = state, viewModel = viewModel)
                }

                MultiPlayStage.RESULTS -> ResultsPane(state = state, viewModel = viewModel)
            }
        }

        // Above every stage, so the same card serves the home screen's "How to play", the first
        // entry into a mode, and the pause menu. One copy, one dismissal path.
        if (state.showHowToPlay) {
            HowToPlayCard(
                mode = state.selectedMode,
                motionAvailable = state.motionAvailable,
                onDismiss = { viewModel.dismissHowToPlay() },
                dismissLabel = if (state.stage == MultiPlayStage.ARENA) "Fly" else "Got it",
            )
        }

        val error = state.error
        if (error != null) {
            LaunchedEffect(error) {
                delay(4_000L)
                viewModel.dismissError()
            }
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                containerColor = Color(0xFF1B2150),
                contentColor = Color.White,
            ) {
                Text(error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Deep space, the arena's floor colour; matches the Play globe's vignette. */
internal val ArenaBlack = Color(0xFF060B1E)
internal val PanelFill = Color(0xE0101B33)
internal val PanelStroke = Color(0x334DE3FF)
