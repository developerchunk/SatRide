package com.developerstring.satride.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.NumericSize
import com.developerstring.satride.core.designsystem.component.NumericText
import com.developerstring.satride.core.designsystem.component.PolarPassPlot
import com.developerstring.satride.core.designsystem.component.PolarPlotStyle
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.component.SatRideIcon
import com.developerstring.satride.core.designsystem.component.SatRideIconGraphic
import com.developerstring.satride.core.designsystem.component.SectionHeader
import com.developerstring.satride.core.designsystem.component.SkyPoint
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing

/**
 * Every token and component on one screen.
 *
 * This is how design changes get reviewed without hunting through the app, and how night mode
 * gets verified in one glance — the toggle re-themes the whole page.
 */
@Composable
fun DesignSystemPreview(modifier: Modifier = Modifier) {
    var nightMode by remember { mutableStateOf(false) }

    SatRideTheme(nightMode = nightMode) {
        val accents = SatRideTheme.accents
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding(),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                SatRideButton(
                    text = if (nightMode) "Night mode: ON" else "Night mode: OFF",
                    onClick = { nightMode = !nightMode },
                    accent = accents.radio,
                )
            }

            item { SectionHeader("Accents") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf(
                        accents.tracking to "Tracking",
                        accents.radio to "Radio",
                        accents.artifact to "Artifact",
                    ).forEach { (color, label) -> Swatch(color, label) }
                }
            }

            item { SectionHeader("Status") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    AccentChip("COMPASS OK", accent = accents.success)
                    AccentChip("TLE AGING", accent = accents.warning)
                    AccentChip("NO SIGNAL", accent = accents.danger)
                }
            }

            item { SectionHeader("Icons") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    SatRideIcon.entries.forEach { icon ->
                        SatRideIconGraphic(icon, size = 28.dp, tint = accents.tracking)
                    }
                }
            }

            item { SectionHeader("Pass plots") }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PolarPassPlot(arc = SAMPLE_ARC, style = PolarPlotStyle.THUMBNAIL)
                    PolarPassPlot(
                        arc = SAMPLE_ARC,
                        style = PolarPlotStyle.FULL,
                        live = SAMPLE_ARC[9],
                        size = 180.dp,
                    )
                }
            }

            item { SectionHeader("Numerics") }
            item {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    NumericText("137.100 MHz", size = NumericSize.Large, color = accents.radio)
                    NumericText("AZ 214.6°  EL 47.2°", size = NumericSize.Medium)
                    NumericText("range 812.4 km  ṙ -5.31 km/s", size = NumericSize.Small)
                }
            }

            item { SectionHeader("Typography") }
            item {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Text("Headline", style = MaterialTheme.typography.headlineMedium)
                    Text("Title", style = MaterialTheme.typography.titleLarge)
                    Text("Body — the sky is not empty.", style = MaterialTheme.typography.bodyLarge)
                    Text("LABEL", style = MaterialTheme.typography.labelSmall)
                }
            }

            item { SectionHeader("Buttons") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SatRideButton("Tune", onClick = {}, accent = accents.radio)
                    SatRideButton(
                        text = "Remind me",
                        onClick = {},
                        style = SatRideButtonStyle.Secondary,
                    )
                }
            }
        }
    }
}

/**
 * A plausible overhead pass, for the gallery only.
 *
 * Rises in the north-east, culminates at 68° and sets in the south-west — the shape of a good ISS
 * pass, so the plot is shown doing the job it exists for rather than drawing a synthetic circle.
 */
private val SAMPLE_ARC: List<SkyPoint> = listOf(
    SkyPoint(38f, 0f),
    SkyPoint(41f, 6f),
    SkyPoint(45f, 13f),
    SkyPoint(51f, 22f),
    SkyPoint(60f, 33f),
    SkyPoint(75f, 46f),
    SkyPoint(101f, 60f),
    SkyPoint(142f, 68f),
    SkyPoint(180f, 60f),
    SkyPoint(205f, 46f),
    SkyPoint(220f, 33f),
    SkyPoint(229f, 22f),
    SkyPoint(235f, 13f),
    SkyPoint(239f, 6f),
    SkyPoint(242f, 0f),
)

@Composable
private fun Swatch(color: Color, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Column(
            modifier = Modifier
                .size(56.dp)
                .background(color, RoundedCornerShape(10.dp))
                .padding(Spacing.xs),
        ) {}
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
