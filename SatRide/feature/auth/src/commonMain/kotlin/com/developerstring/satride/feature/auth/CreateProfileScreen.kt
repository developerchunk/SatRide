package com.developerstring.satride.feature.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.auth.Country
import com.developerstring.satride.core.auth.UserProfile
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * The one form in the app.
 *
 * Name is required and pre-filled from the Google account; country and referral are optional and
 * labelled as such. Nothing here is validated more strictly than the Firestore rules validate it,
 * so a form that passes cannot be rejected by the server — a mismatch between the two would show up
 * as an unexplained failure at the last step of sign-up, which is the worst possible place for one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateProfileScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CreateProfileViewModel = koinViewModel(),
    onMessage: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CreateProfileEffect.Saved -> onDone()
                is CreateProfileEffect.ShowMessage -> onMessage(effect.text)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text("About you", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Only your name is needed. The rest is optional.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.name,
            onValueChange = { viewModel.onEvent(CreateProfileEvent.NameChanged(it)) },
            label = { Text("Name") },
            singleLine = true,
            isError = state.error != null,
            supportingText = { Text("${state.name.length} / ${UserProfile.MAX_NAME_LENGTH}") },
            modifier = Modifier.fillMaxWidth(),
        )

        // A field that opens a searchable sheet, rather than a dropdown: two hundred and
        // forty-nine countries in a dropdown is a scroll, not a choice.
        //
        // `readOnly`, and deliberately *not* `enabled = false`. A disabled field is drawn at
        // reduced contrast, so it reads as "you cannot use this" when the truth is the opposite —
        // it is the one field here that opens a whole picker. Read-only keeps full contrast and
        // only suppresses the keyboard.
        //
        // The tap opens the sheet via a transparent overlay rather than the field's own interaction
        // source: a read-only OutlinedTextField emits `PressInteraction.Release` on Android but not
        // reliably on iOS, so on iOS the country sheet never opened. An overlay sized to the field
        // owns the tap on every platform without changing how the field looks.
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.selectedCountry?.let { "${it.flag}  ${it.name}" } ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Country (optional)") },
                placeholder = { Text("Tap to choose") },
                trailingIcon = {
                    Text(
                        text = "▾",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Box(
                Modifier
                    .matchParentSize()
                    .clickable { viewModel.onEvent(CreateProfileEvent.CountryPickerOpened) },
            )
        }

        OutlinedTextField(
            value = state.referredBy,
            onValueChange = { viewModel.onEvent(CreateProfileEvent.ReferredByChanged(it)) },
            label = { Text("Referred by (optional)") },
            placeholder = { Text("Who told you about SatRide?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = SatRideTheme.accents.danger)
        }

        SatRideButton(
            text = if (state.saving) "Saving…" else "Continue",
            onClick = { viewModel.onEvent(CreateProfileEvent.Save) },
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        )

        SatRideButton(
            text = "Skip for now",
            onClick = onDone,
            style = SatRideButtonStyle.Secondary,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (state.countryPickerOpen) {
        ModalBottomSheet(onDismissRequest = { viewModel.onEvent(CreateProfileEvent.CountryPickerDismissed) }) {
            CountryPicker(
                query = state.countryQuery,
                results = state.countryResults,
                onQueryChange = { viewModel.onEvent(CreateProfileEvent.CountryQueryChanged(it)) },
                onSelect = { viewModel.onEvent(CreateProfileEvent.CountrySelected(it)) },
            )
        }
    }
}

@Composable
private fun CountryPicker(
    query: String,
    results: List<Country>,
    onQueryChange: (String) -> Unit,
    onSelect: (Country?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search country or code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (results.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(Spacing.xl), Alignment.Center) {
                Text(
                    text = "Nothing matches \"$query\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(vertical = Spacing.sm)) {
            item {
                // Clearing is a real choice: the field is optional, so a user who picked one by
                // accident needs a way back to "not answered".
                Text(
                    text = "No country",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(null) }
                        .padding(vertical = Spacing.md),
                )
            }
            items(results, key = { it.code }) { country ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(country) }
                        .padding(vertical = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(country.flag, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = country.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = country.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
