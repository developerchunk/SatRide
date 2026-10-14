package com.developerstring.satride.feature.satellites.import

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.common.AppError
import com.developerstring.satride.core.data.SatelliteRepository
import com.developerstring.satride.core.data.TrackingSelectionRepository
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.core.orbit.sgp4.TleParseException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class TleImportUiState(
    val text: String = "",
    val importing: Boolean = false,
    val error: String? = null,
    /** How many element sets the user has pasted in so far, for the "N imported" line. */
    val importsMade: Int = 0,
    /** Whether imported satellites are added to the tracking selection automatically. */
    val trackAfterImport: Boolean = true,
) {
    val canSubmit: Boolean get() = text.isNotBlank() && !importing
}

sealed interface TleImportEvent {
    data class TextChanged(val text: String) : TleImportEvent
    data class TrackAfterImportChanged(val enabled: Boolean) : TleImportEvent
    data object Submitted : TleImportEvent
}

sealed interface TleImportEffect {
    data class Imported(val count: Int, val tracked: Boolean) : TleImportEffect
}

/**
 * How many element sets the user has pasted in.
 *
 * A narrow interface rather than the whole settings repository: this view model needs a counter,
 * and depending on all of preferences would drag a real DataStore — and its real disk I/O — into
 * every test of it, where it does not survive a virtual clock.
 *
 * Imports are unlimited. The counter survives because it is still worth telling somebody how many
 * sets they have added, and because it is the natural place to notice that a hand-imported
 * satellite is one a public catalog has since started carrying.
 */
interface ImportAllowance {
    val importsUsed: Flow<Int>

    suspend fun recordImport()
}

/** The real one, over the user's stored preferences. */
class SettingsImportAllowance(private val settings: SettingsRepository) : ImportAllowance {
    override val importsUsed: Flow<Int> = settings.settings.map { it.customTleImports }

    override suspend fun recordImport() = settings.recordCustomTleImport()
}

/**
 * Pasting in element sets by hand.
 *
 * Exists for the cases the catalogs miss: a launch from this morning, a classified-object set from
 * a community list, a satellite someone is tracking for a project. Parsing is strict here, unlike
 * a catalog fetch — text that came from a person is far more likely to have lost a character on
 * the way through a chat app than a catalog is to be serving corruption.
 */
class TleImportViewModel(
    private val satelliteRepository: SatelliteRepository,
    private val allowance: ImportAllowance,
    private val selectionRepository: TrackingSelectionRepository,
) : ViewModel() {

    /**
     * The field and its error, as one value.
     *
     * Held together because they change together: editing clears the error. As separate flows,
     * `combine` emits once with the new text still beside the old error, which flashes a stale
     * message under what the user just typed.
     */
    private data class Input(val text: String = "", val error: String? = null)

    private val input = MutableStateFlow(Input())
    private val importing = MutableStateFlow(false)

    /**
     * The allowance, mirrored so it can be read synchronously.
     *
     * Every decision below reads these fields rather than `state.value`. The state is a projection
     * that is republished a moment later, so gating on it means a user who types and immediately
     * taps Import gets nothing at all — the button reads a `text` that has not caught up yet.
     */
    private val importsUsed = MutableStateFlow(0)

    private val trackAfterImport = MutableStateFlow(true)

    private val _state = MutableStateFlow(TleImportUiState())
    val state: StateFlow<TleImportUiState> = _state.asStateFlow()

    private val effectChannel = Channel<TleImportEffect>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: Flow<TleImportEffect> = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch { allowance.importsUsed.collect { importsUsed.value = it } }

        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                input,
                importing,
                trackAfterImport,
                importsUsed,
            ) { input, importing, track, used ->
                TleImportUiState(
                    text = input.text,
                    importing = importing,
                    error = input.error,
                    importsMade = used,
                    trackAfterImport = track,
                )
            }.collect { _state.value = it }
        }
    }

    fun onEvent(event: TleImportEvent) {
        when (event) {
            // The error clears as the user edits: leaving a stale message under changed input
            // makes the screen look broken rather than helpful.
            is TleImportEvent.TextChanged -> input.value = Input(text = event.text, error = null)

            is TleImportEvent.TrackAfterImportChanged -> trackAfterImport.value = event.enabled

            TleImportEvent.Submitted -> submit()
        }
    }

    private fun submit() {
        val text = input.value.text
        if (text.isBlank() || importing.value) return

        val track = trackAfterImport.value

        viewModelScope.launch {
            importing.value = true
            val result = satelliteRepository.importTle(text)

            result
                .onSuccess { count ->
                    allowance.recordImport()
                    // Selecting what was just imported by default is the whole point of the
                    // screen: nobody pastes an element set in order to *not* track that satellite,
                    // and leaving it unticked meant the import appeared to have done nothing.
                    if (track) {
                        runCatching { selectionRepository.setTracked(importedIds(text), tracked = true) }
                    }
                    input.value = Input()
                    effectChannel.trySend(TleImportEffect.Imported(count, tracked = track))
                }
                .onFailure { input.value = input.value.copy(error = it.friendlyMessage()) }

            importing.value = false
        }
    }

    /**
     * Catalog numbers in the pasted text, read straight off line 1 of each element set.
     *
     * Re-derived from the text rather than returned by the import, because `importTle` reports a
     * count and widening its contract to return ids would push a UI concern into the data layer
     * for the benefit of exactly one caller. Line 1 columns 3–7 are the catalog number in every
     * two-line element set ever published, so this cannot drift from the parser.
     */
    private fun importedIds(text: String): List<Int> = text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("1 ") && it.length >= 7 }
        .mapNotNull { it.substring(2, 7).trim().toIntOrNull() }
        .distinct()
        .toList()

    /**
     * Turns a parse failure into something actionable.
     *
     * The parser's own messages name a column or a checksum, which is right for a log and useless
     * to someone who pasted from a web page. These say what to do about it.
     */
    private fun Throwable.friendlyMessage(): String = when (this) {
        is AppError.InvalidInput -> message ?: DEFAULT_MESSAGE

        is TleParseException -> when {
            message?.contains("Checksum", ignoreCase = true) == true ->
                "That element set does not add up — a character was probably lost in copying. " +
                    "Try copying it again."

            message?.contains("too short", ignoreCase = true) == true ->
                "A line is shorter than a two-line element set should be. Check that nothing " +
                    "wrapped or was cut off."

            else -> "That does not look like two-line element text. $DEFAULT_MESSAGE"
        }

        else -> DEFAULT_MESSAGE
    }

    private companion object {
        const val DEFAULT_MESSAGE =
            "Paste the two lines beginning \"1 \" and \"2 \", optionally with the satellite's " +
                "name on the line above."
    }
}
