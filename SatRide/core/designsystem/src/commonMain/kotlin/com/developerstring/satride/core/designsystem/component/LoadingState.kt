package com.developerstring.satride.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.art.KurzSatellite
import com.developerstring.satride.core.designsystem.art.floating
import com.developerstring.satride.core.designsystem.art.rememberSceneCycle
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing

/**
 * The first-sync loading screen: shown INSTEAD of a half-filled list while the initial
 * download and processing run.
 *
 * This is as much a performance device as a visual one. On a first launch the catalog is
 * downloading, element sets are being written, and the pass search is saturating the CPU —
 * composing a live LazyColumn on top of that re-lays-out dozens of ticking rows every second
 * and reads as lag. Showing this instead keeps the frame cheap until the data is actually
 * ready, then reveals the finished list once.
 */
@Composable
fun FirstSyncLoading(
    title: String,
    caption: String,
    modifier: Modifier = Modifier,
    /** Live progress line, e.g. the source being fetched and a count. Measured, not invented. */
    detail: String? = null,
) {
    val blink = rememberSceneCycle(periodMillis = 2000, label = "firstSyncBeacon")

    Box(modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            KurzSatellite(
                blink = blink,
                modifier = Modifier
                    .size(140.dp)
                    .floating(amplitude = 8.dp),
            )
            CircularProgressIndicator(color = SatRideTheme.accents.tracking)
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = SatRideTheme.accents.tracking,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
