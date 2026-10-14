package com.developerstring.satride.feature.play.multiplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.developerstring.satride.core.designsystem.component.SatelliteGlyph
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.feature.play.multiplay.MultiPlayUiState
import com.developerstring.satride.feature.play.multiplay.MultiPlayViewModel
import com.developerstring.satride.feature.play.multiplay.engine.GameMode

/**
 * A room before the race: the code to share, who is here, and the host's start button.
 *
 * The code is the biggest thing on the screen because it is the thing that gets read out loud
 * across a table. The fifteen-minute clock is always visible so nobody is surprised when the
 * room closes.
 */
@Composable
internal fun LobbyPane(state: MultiPlayUiState, viewModel: MultiPlayViewModel) {
    val accents = SatRideTheme.accents
    val room = state.room
    Box(Modifier.fillMaxSize()) {
        StarField(modifier = Modifier.fillMaxSize(), starCount = 100)
        Row(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Left: the code, the clock, the controls.
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SmallPill("‹ Leave") { viewModel.leaveRoom() }
                    Text(
                        text = if (state.isHost) "YOUR ROOM" else "${room?.hostName ?: "Someone"}'s ROOM",
                        style = MaterialTheme.typography.labelMedium,
                        color = accents.tracking,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Panel(title = "ROOM CODE") {
                    Text(
                        text = room?.code?.chunked(3)?.joinToString(" ") ?: "······",
                        style = MaterialTheme.typography.displayMedium.copy(fontSize = 44.sp, letterSpacing = 4.sp),
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                    )
                    val minutes = state.roomSecondsLeft / 60
                    val seconds = state.roomSecondsLeft % 60
                    Text(
                        text = "Room closes in $minutes:${seconds.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.roomSecondsLeft < 120) accents.coral else Color.White.copy(alpha = 0.7f),
                    )
                }

                Panel(title = "GAME") {
                    val mode = GameMode.fromId(room?.mode)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GameMode.entries.forEach { m ->
                            SmallPill(m.title, selected = m == mode, enabled = state.isHost) {
                                viewModel.setRoomMode(m)
                            }
                        }
                    }
                    Text(
                        mode.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    if (state.isHost) {
                        val maxBots = ((room?.maxPlayers ?: 10) - state.players.size).coerceAtLeast(0)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Fill with bots",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            SmallPill("−") { viewModel.setLobbyBots(state.lobbyBots - 1) }
                            Text(
                                "${state.lobbyBots.coerceAtMost(maxBots)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.width(28.dp),
                            )
                            SmallPill("+") { viewModel.setLobbyBots((state.lobbyBots + 1).coerceAtMost(maxBots)) }
                        }
                        SatRideButton(
                            text = "Start in 5 seconds",
                            onClick = { viewModel.startRoom() },
                            enabled = room != null && state.players.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            accent = accents.sunshine,
                        )
                    } else {
                        Text(
                            "Waiting for the host to start…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = accents.sunshine,
                        )
                    }
                }
            }

            // Right: the grid.
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "PILOTS · ${state.players.size}/${room?.maxPlayers ?: 10}",
                    style = MaterialTheme.typography.labelMedium,
                    color = accents.tracking,
                    fontWeight = FontWeight.Bold,
                )
                state.players.forEach { p ->
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(PanelFill)
                            .border(1.dp, if (p.uid == state.identity?.uid) Color(p.color) else PanelStroke, shape)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(12.dp).background(Color(p.color), CircleShape))
                        SatelliteGlyph(
                            kind = (SatClass.entries.firstOrNull { it.name == p.skin } ?: SatClass.OTHER).glyphKind(),
                            tint = Color(p.color),
                            size = 20.dp,
                        )
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        if (p.uid == room?.hostUid) {
                            Text("HOST", style = MaterialTheme.typography.labelSmall, color = accents.sunshine)
                        }
                        if (p.isBot) {
                            Text(
                                "BOT",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
                repeat(((room?.maxPlayers ?: 10) - state.players.size).coerceIn(0, 3)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "open seat",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.35f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (!state.isHost) {
                    SatRideButton(
                        text = "Leave room",
                        onClick = { viewModel.leaveRoom() },
                        style = SatRideButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
