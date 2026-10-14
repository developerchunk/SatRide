package com.developerstring.satride.feature.satellites.import

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SectionHeader
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Pasting in element sets by hand.
 *
 * For the cases the catalogs miss — a launch from this morning, a community list, a satellite
 * someone is tracking for a project. The example in the placeholder is real ISS text, so a user
 * who has never seen a TLE can tell at a glance what is expected of them.
 */
@Composable
fun TleImportScreen(
    modifier: Modifier = Modifier,
    viewModel: TleImportViewModel = koinViewModel(),
    onImported: (Int) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TleImportEffect.Imported -> onImported(effect.count)
            }
        }
    }

    TleImportContent(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

@Composable
internal fun TleImportContent(
    state: TleImportUiState,
    onEvent: (TleImportEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionHeader(
            title = "Import element sets",
            trailing = {
                if (state.importsMade > 0) {
                    AccentChip(
                        label = "${state.importsMade} imported",
                        accent = SatRideTheme.accents.success,
                    )
                }
            },
        )

        Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
            Text(
                text = "Paste two-line element text. A name line above each pair is optional. " +
                    "Import as many as you like.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AccentChip(
                label = if (state.trackAfterImport) {
                    "\u2713 Track what I import"
                } else {
                    "Track what I import"
                },
                accent = if (state.trackAfterImport) {
                    SatRideTheme.accents.tracking
                } else {
                    SatRideTheme.accents.trackingDim
                },
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .clickable {
                        onEvent(TleImportEvent.TrackAfterImportChanged(!state.trackAfterImport))
                    },
            )

            OutlinedTextField(
                value = state.text,
                onValueChange = { onEvent(TleImportEvent.TextChanged(it)) },
                // Monospace: element sets are fixed-column, so a proportional font makes a
                // correctly aligned set look ragged and a broken one look fine.
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = {
                    Text(EXAMPLE, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                },
                isError = state.error != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
                    .padding(top = Spacing.sm),
            )

            state.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = SatRideTheme.accents.danger,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }

            SatRideButton(
                text = if (state.importing) "Importing…" else "Import",
                onClick = { onEvent(TleImportEvent.Submitted) },
                enabled = state.canSubmit,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }

        GlassPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
            Text("Where to find them", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "CelesTrak and Space-Track publish element sets for everything catalogued. " +
                    "For a brand-new launch, the operator often posts them before the catalogs " +
                    "pick them up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

/** Real ISS element text, so the expected shape is obvious without explaining the format. */
private const val EXAMPLE = """ISS (ZARYA)
1 25544U 98067A   26213.79682580  .00007322  00000-0  13941-3 0  9994
2 25544  51.6314  74.3802 0007140   2.2740 357.8280 15.49304641578823"""
