package com.developerstring.satride.feature.play.multiplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.art.StarField
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.component.SatelliteGlyph
import com.developerstring.satride.core.designsystem.component.SatelliteGlyphKind
import com.developerstring.satride.core.designsystem.haptics.LocalSatRideHaptics
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.feature.play.multiplay.Achievement
import com.developerstring.satride.feature.play.multiplay.MultiPlayUiState
import com.developerstring.satride.feature.play.multiplay.MultiPlayViewModel
import com.developerstring.satride.feature.play.multiplay.engine.GameMode
import com.developerstring.satride.feature.play.multiplay.engine.GameSession

/**
 * The front door: who you are, what you want to play, and with whom.
 *
 * Three columns for a landscape phone — pilot on the left, modes and solo play in the middle,
 * rooms and today's leaderboard on the right — each scrolling on its own so nothing is ever
 * cut off on a short screen.
 */
@Composable
internal fun HomePane(state: MultiPlayUiState, viewModel: MultiPlayViewModel, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        StarField(modifier = Modifier.fillMaxSize(), starCount = 120, twinkle = true)
        Row(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PilotColumn(state, viewModel, onExit, Modifier.weight(0.9f))
            ModesColumn(state, viewModel, Modifier.weight(1.2f))
            RoomsColumn(state, viewModel, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PilotColumn(
    state: MultiPlayUiState,
    viewModel: MultiPlayViewModel,
    onExit: () -> Unit,
    modifier: Modifier,
) {
    val accents = SatRideTheme.accents
    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallPill(text = "‹ Play", onClick = onExit)
            Column {
                Text(
                    text = "MULTI-PLAY",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                )
                Text(
                    text = "Race the planet · or ride to the planets",
                    style = MaterialTheme.typography.labelMedium,
                    color = accents.tracking,
                )
            }
        }

        Panel(title = "PILOT") {
            var editing by remember { mutableStateOf(false) }
            var draft by remember(state.displayName) { mutableStateOf(state.displayName) }
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(16) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = Color.White),
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallPill("Save", selected = true) {
                        viewModel.setDisplayName(draft)
                        editing = false
                    }
                    SmallPill("Cancel") { editing = false }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(14.dp).background(Color(state.colorArgb), CircleShape))
                    Text(
                        text = state.displayName.ifBlank { "Pilot" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    val guest = state.identity?.isGuest != false
                    SmallPill(if (guest) "Rename" else "Name") { if (guest) editing = true }
                }
                val id = state.identity
                Text(
                    text = when {
                        id == null && state.identityError != null -> "Offline · solo only"
                        id?.isGuest == true -> "Guest · sign in on Profile to keep your scores"
                        id != null -> "Signed in"
                        else -> "Connecting…"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }

            Text("CRAFT", style = MaterialTheme.typography.labelSmall, color = accents.tracking)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SatClass.entries.forEach { skin ->
                    val selected = skin == state.skin
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) {
                                    Color(
                                        state.colorArgb,
                                    ).copy(alpha = 0.3f)
                                } else {
                                    Color.White.copy(alpha = 0.06f)
                                },
                            )
                            .border(
                                1.5.dp,
                                if (selected) Color(state.colorArgb) else Color.White.copy(alpha = 0.15f),
                                CircleShape,
                            )
                            .clickable { viewModel.setSkin(skin) },
                        contentAlignment = Alignment.Center,
                    ) {
                        SatelliteGlyph(
                            kind = skin.glyphKind(),
                            tint = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
                            size = 20.dp,
                        )
                    }
                }
            }
            Text(
                state.skin.craftName(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
            )

            Text("COLOUR", style = MaterialTheme.typography.labelSmall, color = accents.tracking)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GameSession.PLAYER_COLORS.forEach { argb ->
                    val selected = argb == state.colorArgb
                    Box(
                        modifier = Modifier
                            .size(if (selected) 26.dp else 22.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(2.dp, if (selected) Color.White else Color.Transparent, CircleShape)
                            .clickable { viewModel.setColor(argb) },
                    )
                }
            }
        }

        Panel(title = "CONTROLS") {
            ToggleRow(
                label = "Motion camera",
                hint = if (state.motionAvailable) "Tilt the phone to look around" else "No motion sensor here",
                checked = state.motionCamera && state.motionAvailable,
                enabled = state.motionAvailable,
            ) { viewModel.setMotionCamera(it) }
            ToggleRow(
                label = "Tilt to steer",
                hint = "Roll the phone like a wheel; the stick still works",
                checked = state.tiltSteer && state.motionAvailable,
                enabled = state.motionAvailable,
            ) { viewModel.setTiltSteer(it) }
        }

        Panel(title = "TROPHIES · ${state.achievements.size}/${Achievement.entries.size}") {
            Achievement.entries.chunked(7).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { a -> TrophyChip(a, a.id in state.achievements) }
                }
            }
            Text(
                text = "${state.gamesPlayed} games flown",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun TrophyChip(achievement: Achievement, unlocked: Boolean) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (unlocked) Color(0x33FFE14D) else Color.White.copy(alpha = 0.05f))
            .border(
                1.dp,
                if (unlocked) Color(0xFFFFE14D) else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(10.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = achievement.emoji,
            style = MaterialTheme.typography.titleMedium,
            color = if (unlocked) Color.White else Color.White.copy(alpha = 0.25f),
        )
    }
}

/**
 * Pick a game, then play it.
 *
 * ## Why the play button is pinned
 *
 * It used to be at the bottom of a scrolling column, under four mode cards — which on a landscape
 * phone put the single action this whole screen exists for *below the fold*. A first-time player
 * saw four descriptions and, as the largest and brightest thing on screen, "Create room · Orbit
 * Race" over on the right: an invitation to set up a multiplayer lobby before they had ever flown.
 *
 * So the list scrolls and the action does not. The mode cards are a choice; playing is the point.
 */
@Composable
private fun ModesColumn(state: MultiPlayUiState, viewModel: MultiPlayViewModel, modifier: Modifier) {
    val accents = SatRideTheme.accents
    Column(modifier = modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CHOOSE A GAME", style = MaterialTheme.typography.labelMedium, color = accents.tracking)

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GameMode.entries.forEach { mode ->
                ModeCard(mode = mode, selected = mode == state.selectedMode, best = state.bestScores[mode.id]) {
                    viewModel.selectMode(mode)
                }
            }
        }

        PlayFooter(state, viewModel)
    }
}

/**
 * The always-visible bottom of the column: how many bots, how to play, and go.
 *
 * "Practice" became "Play" because that is what the button does — the previous pair, *Practice*
 * and *Daily challenge*, asked a player to understand the difference between two things before
 * they had done either.
 */
@Composable
private fun PlayFooter(state: MultiPlayUiState, viewModel: MultiPlayViewModel) {
    val accents = SatRideTheme.accents
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallPill("How to play") { viewModel.openHowToPlay() }
            Spacer(Modifier.weight(1f))
            Text(
                "Bots",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            SmallPill("−") { viewModel.setSoloBots(state.soloBots - 1) }
            Text(
                "${state.soloBots}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.width(24.dp),
            )
            SmallPill("+") { viewModel.setSoloBots(state.soloBots + 1) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SatRideButton(
                text = "Play · ${state.selectedMode.title}",
                onClick = { viewModel.startSolo(daily = false) },
                modifier = Modifier.weight(1.6f),
            )
            SatRideButton(
                text = "Daily",
                onClick = { viewModel.startSolo(daily = true) },
                modifier = Modifier.weight(1f),
                accent = accents.sunshine,
            )
        }

        Text(
            text = if (state.selectedMode.isSolar) {
                "Daily: bots tour the planets with you, and your expedition score goes on the board."
            } else {
                "Daily: everyone flies the same track today, and your best goes on the board."
            },
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun ModeCard(mode: GameMode, selected: Boolean, best: Int?, onClick: () -> Unit) {
    val accents = SatRideTheme.accents
    val accent = when (mode) {
        GameMode.RACE -> accents.tracking
        GameMode.DODGE -> accents.coral
        GameMode.TAG -> accents.artifact
        GameMode.SOLAR -> accents.sunshine
    }
    val haptics = LocalSatRideHaptics.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.18f) else PanelFill)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else PanelStroke, shape)
            .clickable {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = mode.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (selected) accent else Color.White,
                modifier = Modifier.weight(1f),
            )
            if (best != null) {
                Text("BEST $best", style = MaterialTheme.typography.labelSmall, color = accents.sunshine)
            }
        }
        Text(mode.tagline, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))

        // The selected card says what winning is, not just what the mode is called. A tagline
        // ("Three laps through the gates") describes; a goal tells you what you are being scored
        // on, and that is what somebody choosing between four games actually wants.
        if (selected) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Text("→", style = MaterialTheme.typography.labelMedium, color = accent)
                Text(
                    text = howToPlayFor(mode, motionAvailable = true).goal,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}

@Composable
private fun RoomsColumn(state: MultiPlayUiState, viewModel: MultiPlayViewModel, modifier: Modifier) {
    val accents = SatRideTheme.accents
    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Panel(title = "PLAY TOGETHER") {
            val online = state.multiplayerAvailable && state.identity != null
            Text(
                text = "A room lasts 15 minutes and holds up to 10 pilots. Share the code.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
            )
            SatRideButton(
                text = if (state.busy) "…" else "Create room · ${state.selectedMode.title}",
                onClick = { viewModel.createRoom() },
                enabled = online && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.joinCode,
                    onValueChange = { viewModel.setJoinCode(it) },
                    singleLine = true,
                    placeholder = { Text("ROOM CODE", color = Color.White.copy(alpha = 0.4f)) },
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    ),
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.weight(1f),
                )
                SatRideButton(
                    text = "Join",
                    onClick = { viewModel.joinRoom() },
                    enabled = online && !state.busy && state.joinCode.length == 6,
                    style = SatRideButtonStyle.Secondary,
                )
            }
            if (!online) {
                Text(
                    text =
                    state.identityError
                        ?: if (!state.multiplayerAvailable) "Rooms need Firebase in this build." else "Connecting…",
                    style = MaterialTheme.typography.labelSmall,
                    color = accents.coral,
                )
            }
        }

        Panel(title = "TODAY · ${state.selectedMode.title.uppercase()}") {
            if (state.leaderboard.isEmpty()) {
                Text(
                    "Nobody has flown today's track yet. Be first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
            state.leaderboard.take(8).forEachIndexed { i, row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (i == 0) accents.sunshine else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.width(18.dp),
                    )
                    val me = row.uid == state.identity?.uid
                    Text(
                        row.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (me) FontWeight.Bold else FontWeight.Normal,
                        color = if (me) accents.tracking else Color.White,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${row.score}", style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
        }
    }
}

// --- Shared bits -----------------------------------------------------------------------------

@Composable
internal fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PanelFill)
            .border(1.dp, PanelStroke, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = SatRideTheme.accents.tracking,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
}

@Composable
internal fun SmallPill(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val accents = SatRideTheme.accents
    val haptics = LocalSatRideHaptics.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) accents.tracking else Color.White.copy(alpha = 0.10f))
            .border(1.dp, if (selected) accents.tracking else Color.White.copy(alpha = 0.2f), CircleShape)
            .clickable(enabled = enabled) {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                accents.shadow
            } else if (enabled) {
                Color.White
            } else {
                Color.White.copy(alpha = 0.4f)
            },
        )
    }
}

@Composable
internal fun ToggleRow(label: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val accents = SatRideTheme.accents
    val haptics = LocalSatRideHaptics.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) {
            haptics.tap()
            onChange(!checked)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            )
            Text(hint, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f))
        }
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(24.dp)
                .clip(CircleShape)
                .background(if (checked) accents.tracking else Color.White.copy(alpha = 0.15f)),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(3.dp).size(18.dp).background(Color.White, CircleShape))
        }
    }
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = SatRideTheme.accents.tracking,
    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
    cursorColor = SatRideTheme.accents.tracking,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
)

internal fun SatClass.glyphKind(): SatelliteGlyphKind = when (this) {
    SatClass.CREWED -> SatelliteGlyphKind.CREWED
    SatClass.WEATHER -> SatelliteGlyphKind.WEATHER
    SatClass.AMATEUR -> SatelliteGlyphKind.AMATEUR
    SatClass.CUBESAT -> SatelliteGlyphKind.CUBESAT
    SatClass.NAVIGATION -> SatelliteGlyphKind.NAVIGATION
    SatClass.ROCKET_BODY -> SatelliteGlyphKind.ROCKET_BODY
    SatClass.OTHER -> SatelliteGlyphKind.OTHER
}

internal fun SatClass.craftName(): String = when (this) {
    SatClass.CREWED -> "Station · big and bright"
    SatClass.WEATHER -> "Weather bird · one long wing"
    SatClass.AMATEUR -> "Ham bird · gold foil and whips"
    SatClass.CUBESAT -> "CubeSat · small target"
    SatClass.NAVIGATION -> "Nav bus · twin wings"
    SatClass.ROCKET_BODY -> "Spent stage · long and lean"
    SatClass.OTHER -> "Generic bus · the classic"
}
