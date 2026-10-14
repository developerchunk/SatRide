package com.developerstring.satride.feature.play.multiplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.developerstring.satride.core.designsystem.art.StarField
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.feature.play.multiplay.MultiPlayUiState
import com.developerstring.satride.feature.play.multiplay.MultiPlayViewModel
import com.developerstring.satride.feature.play.multiplay.engine.GameMode

/** The scoreboard: where you came, what you scored, and what you unlocked. */
@Composable
internal fun ResultsPane(state: MultiPlayUiState, viewModel: MultiPlayViewModel) {
    val accents = SatRideTheme.accents
    val results = state.results ?: return
    Box(Modifier.fillMaxSize()) {
        StarField(modifier = Modifier.fillMaxSize(), starCount = 120)
        Row(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val placeText = when (results.place) {
                    1 -> "1st"
                    2 -> "2nd"
                    3 -> "3rd"
                    else -> "${results.place}th"
                }
                Text(
                    text = when (results.mode) {
                        GameMode.RACE -> if (results.outcome.finished) "RACE COMPLETE" else "TIME"
                        GameMode.DODGE -> if (results.outcome.place == 1) "LAST ONE FLYING" else "OUT"
                        GameMode.TAG -> if (results.place == 1) "NOT IT" else "TAGGED OUT"
                        GameMode.SOLAR -> if (results.outcome.grandTour) "GRAND TOUR COMPLETE" else "EXPEDITION LOG"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = accents.tracking,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = placeText,
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp),
                    fontWeight = FontWeight.ExtraBold,
                    color = if (results.place == 1) accents.sunshine else Color.White,
                )
                Text(
                    text = "of ${results.standings.size} · ${results.mode.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Panel(title = "SCORE") {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${results.score}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                        )
                        if (results.isBest) {
                            Text(
                                "NEW BEST",
                                style = MaterialTheme.typography.labelMedium,
                                color = accents.sunshine,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    val o = results.outcome
                    if (results.mode.isSolar) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Stat("WORLDS", "${o.worldsVisited}")
                            Stat("SUN GRAZES", "${o.sunGrazes}")
                            Stat("RING DIVES", "${o.nearMisses}")
                            Stat("BOUNCES", "${o.crashes}")
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Stat("GATES", "${o.gatesPassed}")
                            Stat("NEAR MISSES", "${o.nearMisses}")
                            Stat("DRIFTS", "${o.driftBoosts}")
                            Stat("CRASHES", "${o.crashes}")
                        }
                    }
                    if (results.leaderboardId != null) {
                        Text(
                            "Posted to today's leaderboard.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
                if (results.unlocked.isNotEmpty()) {
                    Panel(title = "UNLOCKED") {
                        results.unlocked.forEach { a ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(a.emoji, style = MaterialTheme.typography.titleLarge)
                                Column {
                                    Text(
                                        a.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = accents.sunshine,
                                    )
                                    Text(
                                        a.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SatRideButton(
                        text = if (state.isRoomGame) "Home" else "Play again",
                        onClick = { viewModel.playAgain() },
                        modifier = Modifier.weight(1f),
                    )
                    if (!state.isRoomGame) {
                        SatRideButton(
                            text = "Home",
                            onClick = { viewModel.quitToHome() },
                            style = SatRideButtonStyle.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "STANDINGS",
                    style = MaterialTheme.typography.labelMedium,
                    color = accents.tracking,
                    fontWeight = FontWeight.Bold,
                )
                results.standings.forEachIndexed { i, s ->
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (s.isLocal) Color(s.colorArgb).copy(alpha = 0.18f) else PanelFill)
                            .border(1.dp, if (s.isLocal) Color(s.colorArgb) else PanelStroke, shape)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (i == 0) accents.sunshine else Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.width(24.dp),
                        )
                        Box(Modifier.size(10.dp).background(Color(s.colorArgb), CircleShape))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                            )
                            Text(
                                standingCaption(results.mode, s),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                        Text(
                            "${s.score}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private fun standingCaption(
    mode: GameMode,
    s: com.developerstring.satride.feature.play.multiplay.engine.Standing,
): String = when (mode) {
    GameMode.RACE -> if (s.finished) "finished · ${s.gatesPassed} gates" else "${s.laps} laps · ${s.gatesPassed} gates"
    GameMode.DODGE -> if (s.alive) "still flying" else "out · ${s.nearMisses} near misses"
    GameMode.TAG -> "it for ${s.itTimeS.toInt()}s"
    GameMode.SOLAR -> if (s.finished) "grand tour · ${s.laps} worlds" else "${s.laps} worlds · ${s.nearMisses} stunts"
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
