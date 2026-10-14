package com.developerstring.satride.feature.satellites.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.RadioLauncher
import com.developerstring.satride.core.data.PassRepository
import com.developerstring.satride.core.data.SatelliteRepository
import com.developerstring.satride.core.data.SatelliteTracker
import com.developerstring.satride.core.data.TleRepository
import com.developerstring.satride.core.data.TransmitterRepository
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.model.Satellite
import com.developerstring.satride.core.model.SatelliteSnapshot
import com.developerstring.satride.core.model.Transmitter
import com.developerstring.satride.core.network.IssEvent
import com.developerstring.satride.core.network.IssEventsApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

data class TransmitterRowUi(
    val id: String,
    val description: String,
    val downlinkHz: Long,
    val modeLabel: String,
    val baud: Int?,
    val isActive: Boolean,
    val isInverting: Boolean,
    /** True when SatRide has a decoder for this mode, as opposed to only being able to play audio. */
    val isDecodable: Boolean,
)

data class SatelliteDetailUiState(
    val noradId: Int = 0,
    val satellite: Satellite? = null,
    val snapshot: SatelliteSnapshot? = null,
    val nextPass: Pass? = null,
    val nextPassIn: Duration? = null,
    val transmitters: List<TransmitterRowUi> = emptyList(),
    val isFollowed: Boolean = false,
    val elementAge: Duration? = null,
    val radioAvailable: Boolean = false,
    val issEvent: IssEvent? = null,
    val loading: Boolean = true,
) {
    val isIss: Boolean get() = noradId == ISS_NORAD_ID
    val isUp: Boolean get() = (snapshot?.topo?.elevationDeg ?: -1.0) > 0.0

    companion object {
        const val ISS_NORAD_ID: Int = 25544
    }
}

sealed interface SatelliteDetailEvent {
    data object FollowToggled : SatelliteDetailEvent
    data class TuneClicked(val transmitterId: String) : SatelliteDetailEvent
    data object NextPassClicked : SatelliteDetailEvent
}

sealed interface SatelliteDetailEffect {
    /**
     * Open the pass detail screen.
     *
     * Carries the AOS as well as the satellite: P6's pass route is keyed by both, because a pass
     * is recomputed daily and has no stable stored id to point at.
     */
    data class OpenPass(val noradId: Int, val aosEpochMillis: Long) : SatelliteDetailEffect
    data object RadioUnavailable : SatelliteDetailEffect
}

/**
 * One satellite, live.
 *
 * Tracks the satellite for as long as the screen is open, so its numbers tick whether or not it is
 * currently above the horizon — a user checking "where is it now" wants an answer either way.
 */
class SatelliteDetailViewModel(
    private val noradId: Int,
    satelliteRepository: SatelliteRepository,
    transmitterRepository: TransmitterRepository,
    private val passRepository: PassRepository,
    private val tleRepository: TleRepository,
    private val trackingEngine: SatelliteTracker,
    private val radioLauncher: RadioLauncher,
    private val issEventsApi: IssEventsApi,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    /**
     * Whether the user pinned this satellite, as distinct from the screen tracking it while open.
     *
     * The distinction matters on close: a satellite the user explicitly followed stays tracked, one
     * that was only being viewed is released. Conflating the two either leaks tracked satellites
     * for the whole session or silently un-follows something the user asked to keep.
     */
    private val userFollowed = MutableStateFlow(trackingEngine.isFollowed(noradId))

    private val issEvent = MutableStateFlow<IssEvent?>(null)

    private val effectChannel = Channel<SatelliteDetailEffect>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: Flow<SatelliteDetailEffect> = effectChannel.receiveAsFlow()

    private val nextPass = MutableStateFlow<Pass?>(null)
    private val elementAge = MutableStateFlow<kotlin.time.Duration?>(null)

    init {
        trackingEngine.follow(noradId)

        viewModelScope.launch {
            nextPass.value = runCatching { passRepository.passesFor(noradId, PASS_WINDOW).firstOrNull() }
                .getOrNull()
        }

        viewModelScope.launch {
            elementAge.value = runCatching { tleRepository.elementAgeOf(noradId) }.getOrNull()
        }

        if (noradId == SatelliteDetailUiState.ISS_NORAD_ID) {
            viewModelScope.launch {
                issEvent.value = issEventsApi.activeEvent(clock.now())
            }
        }
    }

    val state: StateFlow<SatelliteDetailUiState> = combine(
        satelliteRepository.observe(noradId),
        trackingEngine.sky.map { it.snapshotOf(noradId) },
        transmitterRepository.observeForSatellite(noradId),
        combine(userFollowed, nextPass, issEvent) { followed, pass, event -> Triple(followed, pass, event) },
        elementAge,
    ) { satellite, snapshot, transmitters, (followed, pass, event), age ->
        SatelliteDetailUiState(
            noradId = noradId,
            satellite = satellite,
            snapshot = snapshot,
            nextPass = pass,
            nextPassIn = pass?.let { it.aos - clock.now() },
            transmitters = transmitters.map { it.toRow() },
            isFollowed = followed,
            elementAge = age,
            radioAvailable = radioLauncher.isAvailable,
            issEvent = event,
            loading = false,
        )
    }
        // Rebuilt on every tracking snapshot. Off the main thread for the same reason the catalog
        // is: `stateIn(viewModelScope, …)` runs the whole combine on `Dispatchers.Main.immediate`.
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), SatelliteDetailUiState())

    fun onEvent(event: SatelliteDetailEvent) {
        when (event) {
            SatelliteDetailEvent.FollowToggled -> toggleFollow()

            is SatelliteDetailEvent.TuneClicked ->
                if (radioLauncher.isAvailable) {
                    radioLauncher.tune(noradId, event.transmitterId)
                } else {
                    effectChannel.trySend(SatelliteDetailEffect.RadioUnavailable)
                }

            SatelliteDetailEvent.NextPassClicked -> {
                // No next pass means nothing to open. The card is already non-clickable in that
                // state; this is the guard that keeps the two from drifting apart.
                val pass = nextPass.value ?: return
                effectChannel.trySend(
                    SatelliteDetailEffect.OpenPass(noradId, pass.aos.toEpochMilliseconds()),
                )
            }
        }
    }

    private fun toggleFollow() {
        val next = !userFollowed.value
        userFollowed.value = next
        if (next) trackingEngine.follow(noradId) else trackingEngine.unfollow(noradId)
    }

    override fun onCleared() {
        releaseTracking()
        super.onCleared()
    }

    /**
     * Stops tracking unless the user pinned the satellite.
     *
     * Following is a deliberate act and outlives the screen; merely looking at a satellite does
     * not. Separate from [onCleared] because that is protected and so cannot be exercised by a
     * test — and this rule is worth a test.
     */
    fun releaseTracking() {
        if (!userFollowed.value) trackingEngine.unfollow(noradId)
    }

    private fun Transmitter.toRow(): TransmitterRowUi = TransmitterRowUi(
        id = id,
        description = description.ifBlank { mode.name },
        downlinkHz = centerHz,
        modeLabel = mode.satnogsName ?: mode.name,
        baud = baud,
        isActive = status == com.developerstring.satride.core.model.TxStatus.ACTIVE,
        isInverting = invert,
        isDecodable = mode.decoder != com.developerstring.satride.core.model.DecoderKind.LISTEN_ONLY,
    )

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        val PASS_WINDOW = 10.days
    }
}
