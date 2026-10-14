package com.developerstring.satride.feature.satellites.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.designsystem.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The satellite detail, as a sheet over another screen.
 *
 * Exists so the sky view can show a satellite's details without `feature:arsky` depending on
 * `feature:satellites` — the architecture check forbids feature-to-feature dependencies, and the
 * app shell composes this into the sky screen's slot instead.
 *
 * The content is exactly [SatelliteDetailContent], the same composable the full-screen route
 * renders, so the two can never drift apart. Only the frame around it differs, plus the one action
 * that means nothing on a list screen: *guide me to this*.
 *
 * ## Why the sheet owns its view model
 *
 * The full-screen route gets a fresh [SatelliteDetailViewModel] for free, because every navigation
 * entry is its own `ViewModelStoreOwner`. This sheet is not a navigation entry: it is composed
 * inside the sky tab, whose store lives for the whole session. Resolving the view model against
 * that store meant the *first* satellite ever tapped was the one every later tap showed — Koin
 * returns the stored instance and never looks at `parametersOf(noradId)` again — and, because the
 * store was never cleared, that first satellite stayed followed for the rest of the session.
 *
 * So the sheet is given a store of its own, keyed on the satellite. A new satellite means a new
 * store and a new view model; dismissing the sheet, or picking another satellite, clears the old
 * store, which runs `onCleared` and releases the tracking the sheet had asked for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteDetailSheet(noradId: Int, onGuide: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // `key` on top of the keyed store: it restarts the whole sheet — sheet state and enter
    // animation included — so switching satellites reads as a new sheet, not as the old one
    // silently swapping its numbers.
    key(noradId) {
        val owner = remember { SheetViewModelStoreOwner() }
        DisposableEffect(owner) {
            onDispose { owner.viewModelStore.clear() }
        }

        val viewModel: SatelliteDetailViewModel = koinViewModel(viewModelStoreOwner = owner) { parametersOf(noradId) }
        val state by viewModel.state.collectAsStateWithLifecycle()
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
            SatelliteDetailContent(state = state, onEvent = viewModel::onEvent)

            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, alignment = Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Close") }
                Button(onClick = onGuide) { Text("Guide me there") }
            }
        }
    }
}

/** A store that lives exactly as long as one satellite's sheet. */
private class SheetViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}
