package com.developerstring.satride.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.art.KurzSatellite
import com.developerstring.satride.core.designsystem.art.rememberSceneCycle
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.SatRideIcon
import com.developerstring.satride.core.designsystem.component.SatRideIconGraphic
import com.developerstring.satride.core.designsystem.component.SectionHeader
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.core.web.LegalPage
import kotlinx.coroutines.launch

/**
 * About SatRide: what this build is, who made it, and where the legal documents are.
 *
 * Every link opens inside the app rather than in a browser — see `InAppWebView`. A privacy policy
 * that throws the reader into Chrome is a privacy policy a good number of people never finish
 * reading, and on iOS a store reviewer following one has left the app they were reviewing.
 */
@Composable
fun AboutScreen(
    version: String,
    buildDate: String,
    onOpenPage: (LegalPage) -> Unit,
    onContact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val drift = rememberSceneCycle(periodMillis = 7000, label = "aboutDrift")

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.lg,
                bottom = Spacing.navBarClearance,
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = Spacing.md), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                KurzSatellite(blink = drift, modifier = Modifier.size(120.dp))
                Text(
                    text = "SatRide",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                Text(
                    text = "Satellites, passes and signals — in your hand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        SectionHeader(title = "This build")
        GlassPanel(Modifier.fillMaxWidth()) {
            InfoRow("Version", version)
            InfoRow("Last updated", buildDate)
            InfoRow("Orbital data", "CelesTrak")
        }

        SectionHeader(title = "Legal")
        GlassPanel(Modifier.fillMaxWidth()) {
            // Deletion is listed alongside the policies rather than buried in the account section:
            // Play requires the route to be findable, and somebody looking for it is not going to
            // think of it as a "setting".
            LegalPage.entries.forEachIndexed { index, page ->
                if (index > 0) Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xxs))
                LinkRow(label = page.title, onClick = { onOpenPage(page) })
            }
        }

        SectionHeader(title = "Contact")
        GlassPanel(Modifier.fillMaxWidth()) {
            LinkRow(label = LegalPage.CONTACT_EMAIL, onClick = onContact)
            Text(
                text = "Bug reports, questions, or a satellite you think should be in the catalog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }

        Text(
            text = "Positions are propagated with SGP4 from public element sets. Nothing you do in " +
                "this app is uploaded anywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        SatRideIconGraphic(
            icon = SatRideIcon.Chevron,
            size = 18.dp,
            tint = SatRideTheme.accents.tracking,
        )
    }
}
