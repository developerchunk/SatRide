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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.feature.play.multiplay.engine.GameMode

/**
 * What a player needs before their first race, and nothing else.
 *
 * ## Why this exists
 *
 * The arena used to explain itself in one line, during the three-second countdown: "Left stick
 * steers and climbs · hold DRIFT through turns · double-tap to recentre the camera". That is a
 * sentence, shown while a number counts down over it, and then gone. Players arrived at a screen
 * with a stick, three buttons and six read-outs and no idea what any of it was for.
 *
 * So: one card, shown once per mode, that answers the three questions in the order people ask
 * them — what am I trying to do, how do I move, and what is the one thing that will make me
 * better. Three controls, not six. No lore.
 *
 * It is deliberately per mode. The four games share a stick and three buttons but not a goal, and
 * somebody who has raced still has no idea what "it" means in Tag.
 */
internal data class HowToPlay(
    /** The one sentence that says what winning looks like. */
    val goal: String,
    val controls: List<ControlHint>,
    /** The single thing worth knowing that is not obvious from the controls. */
    val tip: String,
) {
    /** One control: what it looks like, what it is called, what it does. */
    data class ControlHint(val glyph: String, val name: String, val what: String)
}

/**
 * The card for a mode.
 *
 * Written as data rather than composables so the same text can appear on the home screen — where
 * a player is choosing — and over the arena, where they are about to fly. Two copies of this
 * wording would drift apart within a week.
 */
internal fun howToPlayFor(mode: GameMode, motionAvailable: Boolean): HowToPlay {
    val steer = if (motionAvailable) {
        HowToPlay.ControlHint(
            glyph = "◉",
            name = "Stick",
            what = "Bottom left. Drag anywhere in the pad — left and right turn, up and down climb and dive.",
        )
    } else {
        HowToPlay.ControlHint(
            glyph = "◉",
            name = "Stick",
            what = "Bottom left, or the arrow keys. Left and right turn, up and down climb and dive.",
        )
    }

    val drift = HowToPlay.ControlHint(
        glyph = "◐",
        name = "Drift",
        what = "Hold it and keep steering to slide through a turn without losing speed. " +
            "Let go for a kick of points.",
    )

    val boost = HowToPlay.ControlHint(
        glyph = "▲",
        name = "Boost",
        what = "Three charges a round. Tap for a burst of speed — save one for the last straight.",
    )

    val brake = HowToPlay.ControlHint(
        glyph = "■",
        name = "Brake",
        what = "Hold to slow down. Cheaper than hitting a rock.",
    )

    return when (mode) {
        GameMode.RACE -> HowToPlay(
            goal = "Fly through all 24 gates, three times round. Fastest lap wins.",
            controls = listOf(steer, drift, boost),
            tip = "Gates are the glowing rings. Missing one costs you 40 points — line up early rather than " +
                "cutting it fine.",
        )

        GameMode.DODGE -> HowToPlay(
            goal = "Stay alive. The debris gets thicker every minute and you have three shields.",
            controls = listOf(steer, brake, boost),
            tip = "Braking is often better than swerving — most hits come from turning into something you " +
                "could have let pass.",
        )

        GameMode.TAG -> HowToPlay(
            goal = "Don't be it. If you are, fly into someone else to pass it on.",
            controls = listOf(steer, boost, drift),
            tip = "Whoever is it scores nothing while they hold it, so run — and save a boost for when " +
                "somebody comes for you.",
        )

        GameMode.SOLAR -> HowToPlay(
            goal = "Fly a starship to the real planets, where they actually are today.",
            controls = listOf(steer, boost, brake),
            tip = "Pick a planet and let the autopilot line you up, then take over. Distances are real, so " +
                "wind the speed of light up until they aren't.",
        )
    }
}

/**
 * The card itself, over whatever is behind it.
 *
 * Full-screen and modal: it appears at the moment a player would otherwise be lost, and the only
 * way past it is the button that starts the game. A dismissible tooltip would be read by nobody.
 */
@Composable
internal fun HowToPlayCard(
    mode: GameMode,
    motionAvailable: Boolean,
    onDismiss: () -> Unit,
    dismissLabel: String = "Got it",
) {
    val accents = SatRideTheme.accents
    val guide = howToPlayFor(mode, motionAvailable)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Nearly opaque. The arena behind is busy and moving, and a translucent scrim over it
            // makes body text genuinely hard to read at arm's length.
            .background(Color(0xF2070B1E))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        // A scrolling body under a pinned button.
        //
        // This screen is landscape on a phone, which is about 400dp tall — a card laid out as one
        // long scroll puts its own dismiss button below the fold, which is the exact complaint
        // that sent me here in the first place. The way out is always visible.
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 620.dp)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            Text(
                text = mode.title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accents.tracking,
            )
            Text(
                text = guide.goal,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                guide.controls.forEach { ControlRow(it) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(accents.sunshine.copy(alpha = 0.12f))
                        .border(1.dp, accents.sunshine.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("★", style = MaterialTheme.typography.titleSmall, color = accents.sunshine)
                    Text(
                        text = guide.tip,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            SatRideButton(text = dismissLabel, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * One control, drawn as the thing it is.
 *
 * The glyph is the same shape the arena draws, at the same colour, so the card teaches a player to
 * recognise the control rather than to remember a word for it.
 */
@Composable
private fun ControlRow(control: HowToPlay.ControlHint) {
    val accents = SatRideTheme.accents
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(accents.tracking.copy(alpha = 0.16f), CircleShape)
                .border(2.dp, accents.tracking.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = control.glyph,
                style = MaterialTheme.typography.titleLarge,
                color = accents.tracking,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = control.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = control.what,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
        Spacer(Modifier.width(2.dp))
    }
}
