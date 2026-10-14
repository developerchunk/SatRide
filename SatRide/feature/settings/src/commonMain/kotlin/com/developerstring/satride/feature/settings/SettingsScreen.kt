package com.developerstring.satride.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.datastore.ObserverMode
import com.developerstring.satride.core.datastore.UnitSystem
import com.developerstring.satride.core.designsystem.component.AccentChip
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.component.ScreenReveal
import com.developerstring.satride.core.designsystem.component.SectionHeader
import com.developerstring.satride.core.designsystem.component.readableWidth
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * Settings: the handful of choices that change what the rest of the app computes.
 *
 * Ordered by how much each one matters. **Where you are** comes first because every pass, every
 * elevation and every reminder in the app is derived from it, and it is the one setting whose
 * being wrong makes everything else quietly wrong too. Display preferences come last because
 * getting them wrong costs nothing.
 *
 * Deliberately short. Every switch here is one somebody has to understand, and a settings screen
 * that offers thirty of them is how an app ends up with a support inbox.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
    onOpenTleImport: () -> Unit = {},
    onOpenTracking: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenTesterSignIn: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SettingsEffect.ShowMessage -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(effect.text)
                }
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        ScreenReveal(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .readableWidth()
                    .padding(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.lg,
                        bottom = Spacing.navBarClearance,
                    ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                ObserverSection(state = state, onEvent = viewModel::onEvent)
                PassesSection(state = state, onEvent = viewModel::onEvent)
                DataSection(
                    state = state,
                    onEvent = viewModel::onEvent,
                    onOpenTleImport = onOpenTleImport,
                    onOpenTracking = onOpenTracking,
                )
                DisplaySection(state = state, onEvent = viewModel::onEvent)
                AboutSection(
                    state = state,
                    onEvent = viewModel::onEvent,
                    onOpenAbout = onOpenAbout,
                    onOpenTesterSignIn = onOpenTesterSignIn,
                )
            }
        }

        SnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Spacing.navBarClearance),
        )
    }

    if (state.manualEntryOpen) {
        ManualPositionDialog(state = state, onEvent = viewModel::onEvent)
    }
}

/**
 * Where the app thinks you are.
 *
 * Manual is a first-class choice, not a fallback: plenty of people plan a session at home for a
 * site they will drive to, and declining location is something a user is entitled to do outright.
 */
@Composable
private fun ObserverSection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    SectionHeader(title = "Your position")

    GlassPanel(Modifier.fillMaxWidth()) {
        Text(
            text = "Every pass time and elevation in the app is computed from this.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ChoiceRow(
            options = ObserverMode.entries,
            selected = state.observerMode,
            label = { if (it == ObserverMode.AUTOMATIC) "Automatic" else "Set by hand" },
            onSelect = { onEvent(SettingsEvent.ObserverModeSelected(it)) },
        )

        when {
            state.observerMode == ObserverMode.AUTOMATIC -> SettingNote(
                "Follows your device's location. Grant location access on the Profile tab if passes " +
                    "look wrong.",
            )

            state.manualPosition != null -> {
                SettingNote(
                    "Using ${format(state.manualPosition.latDeg)}, ${format(state.manualPosition.lonDeg)}.",
                )
                SatRideButton(
                    text = "Change position",
                    onClick = { onEvent(SettingsEvent.ManualEntryOpened) },
                    style = SatRideButtonStyle.Secondary,
                )
            }

            else -> {
                SettingNote("No position set — the app cannot work out what is overhead yet.")
                SatRideButton(
                    text = "Enter a position",
                    onClick = { onEvent(SettingsEvent.ManualEntryOpened) },
                    style = SatRideButtonStyle.Secondary,
                )
            }
        }
    }
}

@Composable
private fun PassesSection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    SectionHeader(title = "Passes")

    GlassPanel(Modifier.fillMaxWidth()) {
        Text("Lowest pass worth showing", style = MaterialTheme.typography.titleSmall)
        SettingNote(
            "Passes that never climb above this are hidden. A two-degree pass is technically a " +
                "pass and practically a rooftop.",
        )
        ChoiceRow(
            options = SettingsViewModel.MinimumElevations,
            selected = state.minimumPassElevationDeg,
            label = { "${it.roundToInt()}°" },
            onSelect = { onEvent(SettingsEvent.MinimumElevationSelected(it)) },
        )
    }
}

@Composable
private fun DataSection(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    onOpenTleImport: () -> Unit,
    onOpenTracking: () -> Unit,
) {
    SectionHeader(title = "Orbital data")

    // First in the section, because it is the setting every other one here is downstream of: how
    // much of the catalog is being tracked decides how much there is to refresh, predict and draw.
    GlassPanel(Modifier.fillMaxWidth()) {
        Text("What to track", style = MaterialTheme.typography.titleSmall)
        SettingNote(
            "Choose the starter set, your own selection, or every satellite in the catalog.",
        )
        SatRideButton(
            text = "Manage tracking",
            onClick = onOpenTracking,
            style = SatRideButtonStyle.Secondary,
        )
    }

    GlassPanel(Modifier.fillMaxWidth()) {
        ToggleRow(
            title = "Refresh automatically",
            detail = "Keeps element sets current so predictions stay accurate.",
            checked = state.autoRefreshTles,
            onCheckedChange = { onEvent(SettingsEvent.AutoRefreshToggled(it)) },
        )

        if (state.autoRefreshTles) {
            Text(
                text = "How often",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            ChoiceRow(
                options = SettingsViewModel.RefreshIntervals,
                selected = state.tleRefreshInterval,
                label = ::describe,
                onSelect = { onEvent(SettingsEvent.RefreshIntervalSelected(it)) },
            )
        }
    }

    GlassPanel(Modifier.fillMaxWidth()) {
        Text("Import element sets", style = MaterialTheme.typography.titleSmall)
        SettingNote("Paste in a TLE for something the catalogs do not carry yet.")
        SatRideButton(
            text = "Import",
            onClick = onOpenTleImport,
            style = SatRideButtonStyle.Secondary,
        )
    }
}

@Composable
private fun DisplaySection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    SectionHeader(title = "Display")

    GlassPanel(Modifier.fillMaxWidth()) {
        Text("Units", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(
            options = UnitSystem.entries,
            selected = state.units,
            label = { if (it == UnitSystem.METRIC) "Kilometres" else "Miles" },
            onSelect = { onEvent(SettingsEvent.UnitsSelected(it)) },
        )

        Box(Modifier.padding(top = Spacing.sm)) {
            ToggleRow(
                title = "Night mode",
                detail = "Dims the sky view to red so it does not wreck your dark adaptation.",
                checked = state.nightMode,
                onCheckedChange = { onEvent(SettingsEvent.NightModeToggled(it)) },
            )
        }
    }
}

@Composable
private fun AboutSection(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenTesterSignIn: () -> Unit,
) {
    SectionHeader(title = "About")

    GlassPanel(Modifier.fillMaxWidth()) {
        LabelledValue("Version", state.appVersion.ifEmpty { "—" })
        LabelledValue("Orbital data", "CelesTrak")
    }

    SatRideButton(
        text = "About SatRide",
        onClick = onOpenAbout,
        style = SatRideButtonStyle.Secondary,
        modifier = Modifier.fillMaxWidth(),
    )

    SatRideButton(
        text = "Replay the tour",
        onClick = { onEvent(SettingsEvent.WalkthroughReplayRequested) },
        style = SatRideButtonStyle.Secondary,
        modifier = Modifier.fillMaxWidth(),
    )

    // Sitting in plain sight, and labelled for what it is.
    //
    // Google's reviewers need a way into the signed-in half of the app that does not involve a
    // shared Google account, and the access is declared to them in the App access form. A door
    // that is declared is fine; the same door hidden behind a secret gesture is the thing that
    // gets an app pulled. The note is there so the handful of users who find it are not left
    // wondering what they are missing.
    SectionHeader(title = "Testers")
    GlassPanel(Modifier.fillMaxWidth()) {
        SettingNote(
            "Reviewer and internal-test accounts only.",
        )
        Box(Modifier.padding(top = Spacing.sm)) {
            SatRideButton(
                text = "Tester sign-in",
                onClick = onOpenTesterSignIn,
                style = SatRideButtonStyle.Secondary,
            )
        }
    }
}

@Composable
private fun ManualPositionDialog(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(SettingsEvent.ManualEntryDismissed) },
        title = { Text("Set your position") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = "Decimal degrees. North and east are positive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.manualLatInput,
                    onValueChange = { onEvent(SettingsEvent.ManualLatChanged(it)) },
                    label = { Text("Latitude") },
                    singleLine = true,
                    isError = state.manualEntryError != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = state.manualLonInput,
                    onValueChange = { onEvent(SettingsEvent.ManualLonChanged(it)) },
                    label = { Text("Longitude") },
                    singleLine = true,
                    isError = state.manualEntryError != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                )
                state.manualEntryError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = SatRideTheme.accents.danger)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(SettingsEvent.ManualEntrySubmitted) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(SettingsEvent.ManualEntryDismissed) }) { Text("Cancel") }
        },
    )
}

/**
 * A row of mutually exclusive choices as chips.
 *
 * Chips rather than a dropdown: every one of these sets has five options or fewer, and a menu that
 * hides four short words behind a tap is a menu that exists to save space there is plenty of.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        options.forEach { option ->
            val active = option == selected
            AccentChip(
                label = label(option),
                // Selection is said by the accent alone: the chip is already an outlined pill, so
                // a second selected treatment on top of it would be two signals for one fact.
                accent = if (active) {
                    SatRideTheme.accents.tracking
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.clickable { onSelect(option) },
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xxs),
    )
}

private fun describe(interval: Duration): String {
    val hours = interval.inWholeHours
    return if (hours < 24) "$hours h" else "${hours / 24} d"
}

/** Coordinates to four decimals — about eleven metres, far finer than any of this needs. */
private fun format(degrees: Double): String {
    val rounded = (degrees * 10_000.0).roundToInt() / 10_000.0
    return rounded.toString()
}
