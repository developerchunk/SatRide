package com.developerstring.satride.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.art.KurzSatellite
import com.developerstring.satride.core.designsystem.art.SpaceBackground
import com.developerstring.satride.core.designsystem.art.floating
import com.developerstring.satride.core.designsystem.art.rememberSceneCycle
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing

/**
 * Scaffolding for a screen whose real implementation lands in a later phase.
 *
 * It names the phase that will replace it so an unfinished surface is never mistaken for a
 * finished one — by us, by a reviewer, or in a screenshot. Every use of this must be gone
 * before the store submission in P13. Restyled as an illustrated scene so even unfinished
 * surfaces speak the app's visual language.
 */
@Composable
fun PhasePlaceholder(title: String, phase: String, description: String, modifier: Modifier = Modifier) {
    val blink = rememberSceneCycle(periodMillis = 2000, label = "placeholderBeacon")

    SpaceBackground(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier.padding(Spacing.xl),
            ) {
                KurzSatellite(
                    blink = blink,
                    modifier = Modifier
                        .size(140.dp)
                        .floating(amplitude = 8.dp),
                )
                AccentChip(label = "ARRIVES IN $phase", accent = SatRideTheme.accents.radio)
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
