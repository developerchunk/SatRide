package com.developerstring.satride.feature.passes.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.SkyGuideRequests
import com.developerstring.satride.core.common.runCatchingCancellable
import com.developerstring.satride.core.data.PassRepository
import com.developerstring.satride.core.data.SatelliteRepository
import com.developerstring.satride.core.data.SatelliteTracker
import com.developerstring.satride.core.data.TransmitterRepository
import com.developerstring.satride.core.designsystem.component.SkyPoint
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Transmitter
import com.developerstring.satride.core.model.TxStatus
import com.developerstring.satride.core.notifications.AlertScheduler
import com.developerstring.satride.core.notifications.PassAlert
import com.developerstring.satride.core.orbit.doppler.Doppler
import com.developerstring.satride.feature.passes.PassGrade
import com.developerstring.satride.feature.passes.passId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One point of the elevation-against-time curve, already normalised for drawing. */
data class CurvePoint(
    /** 0 at AOS, 1 at LOS. */
    val fraction: Float,
    val value: Float,
)

data class DownlinkRowUi(
    val id: String,
    val description: String,
    val downlinkHz: Long,
    val modeLabel: String,
    val isActive: Boolean,
)

data class PassDetailUiState(
    val noradId: Int = 0,
    val satelliteName: String = "",
    val classType: SatClass = SatClass.OTHER,
    val pass: Pass? = null,
    val arc: List<SkyPoint> = emptyList(),
    val elevationCurve: List<CurvePoint> = emptyList(),
    /** Doppler shift across the pass for [dopplerTransmitter], normalised to ±1 of its own peak. */
    val dopplerCurve: List<CurvePoint> = emptyList(),
    val dopplerPeakHz: Double = 0.0,
    val dopplerTransmitter: DownlinkRowUi? = null,
    val downlinks: List<DownlinkRowUi> = emptyList(),
    val grade: PassGrade = PassGrade.VISUAL_ONLY,
    val startsIn: Duration = Duration.ZERO,
    val live: SkyPoint? = null,
    val hasAlert: Boolean = false,
    val alertsSupported: Boolean = true,
    val loading: Boolean = true,
    val notFound: Boolean = false,
) {
    val inProgress: Boolean get() = pass != null && startsIn <= Duration.ZERO && live != null
}

sealed interface PassDetailEvent {
    data object RemindToggled : PassDetailEvent
    data object OpenInSkyClicked : PassDetailEvent
}

sealed interface PassDetailEffect {
    /** The sky view has been asked to guide at this satellite; the shell has to go there. */
    data object OpenSky : PassDetailEffect
    data class ShowMessage(val text: String) : PassDetailEffect
    data object RequestNotificationPermission : PassDetailEffect
}

/**
 * One pass, in full.
 *
 * The pass is found by satellite and AOS rather than held by reference, because the route carries
 * those two numbers and the cache row behind them can be pruned and recomputed while this screen
 * is open. Looking it up means a restored back stack, a process death or a TLE refresh all land on
 * *this* pass rather than on a stale copy of it.
 */
class PassDetailViewModel(
    private val noradId: Int,
    private val aos: Instant,
    private val passRepository: PassRepository,
    private val satelliteRepository: SatelliteRepository,
    transmitterRepository: TransmitterRepository,
    private val tracker: SatelliteTracker,
    private val alertScheduler: AlertScheduler,
    private val guideRequests: SkyGuideRequests,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val pass = MutableStateFlow<Pass?>(null)

    /** Cached for the same reason the timeline caches it: two binder calls, read once a second. */
    private val alertCapability = MutableStateFlow(alertScheduler.capability)
    private val loading = MutableStateFlow(true)

    private val effectChannel = Channel<PassDetailEffect>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: Flow<PassDetailEffect> = effectChannel.receiveAsFlow()

    /**
     * Plain flows, not `stateIn` with empty defaults.
     *
     * A `StateFlow` seeded with null makes `combine` emit before the data arrives, so the first
     * frame would show "Object 25544" with no downlinks and no Doppler preview, and correct itself
     * a moment later. Left cold, `combine` waits — which is what the loading state is for.
     */
    private val satellite = satelliteRepository.observe(noradId)

    private val transmitters = transmitterRepository.observeForSatellite(noradId)

    private val alertedIds = alertScheduler.scheduled()
        .map { alerts -> alerts.map { it.id }.toSet() }

    private val ticker: Flow<Instant> = flow {
        while (true) {
            emit(clock.now())
            delay(TICK)
        }
    }

    init {
        // Tracked while the screen is open, so the live dot on the plot moves for a pass that is
        // happening now.
        tracker.follow(noradId)

        viewModelScope.launch {
            val found = runCatchingCancellable { passRepository.passesFor(noradId, SEARCH_WINDOW) }
                .getOrDefault(emptyList())
                // Matched by nearest AOS rather than by equality: a refresh between the timeline
                // being drawn and this screen opening moves the pass by seconds, and an exact
                // match would report it missing.
                .minByOrNull { abs((it.aos - aos).inWholeSeconds) }
                ?.takeIf { abs((it.aos - aos).inWholeSeconds) <= MATCH_TOLERANCE.inWholeSeconds }

            pass.value = found
            loading.value = false
        }
    }

    val state: StateFlow<PassDetailUiState> = combine(
        pass,
        loading,
        combine(satellite, transmitters) { satellite, transmitters -> satellite to transmitters },
        combine(alertedIds, alertCapability) { alerted, capability -> alerted to capability },
        ticker,
    ) { pass, loading, (satellite, transmitters), (alerted, capability), now ->
        if (pass == null) {
            return@combine PassDetailUiState(
                noradId = noradId,
                satelliteName = satellite?.name ?: "Object $noradId",
                loading = loading,
                notFound = !loading,
                alertsSupported = capability.supported,
            )
        }

        val downlinks = transmitters.map { it.toRow() }
        val strongest = transmitters.pickForDopplerPreview()
        val hasActiveDownlink = transmitters.any { it.status == TxStatus.ACTIVE }
        val doppler = strongest?.let { pass.dopplerCurve(it.downlinkLowHz) }

        PassDetailUiState(
            noradId = noradId,
            satelliteName = satellite?.name ?: "Object $noradId",
            classType = satellite?.classType ?: SatClass.OTHER,
            pass = pass,
            arc = pass.arc.map { SkyPoint(it.topo.azimuthDeg.toFloat(), it.topo.elevationDeg.toFloat()) },
            elevationCurve = pass.elevationCurve(),
            dopplerCurve = doppler?.points.orEmpty(),
            dopplerPeakHz = doppler?.peakHz ?: 0.0,
            dopplerTransmitter = strongest?.toRow(),
            downlinks = downlinks,
            grade = PassGrade.of(pass.maxElevationDeg, hasActiveDownlink),
            startsIn = pass.aos - now,
            live = livePoint(pass, now),
            hasAlert = passId(noradId, pass.aos) in alerted,
            alertsSupported = capability.supported,
            loading = false,
        )
    }
        // The elevation and Doppler curves are resampled on every one-second tick. Running that
        // on `Dispatchers.Main.immediate` — which is what `stateIn(viewModelScope, …)` does — put
        // a curve solve on the UI thread once a second, for as long as the screen was open.
        .flowOn(dispatchers.default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            PassDetailUiState(noradId = noradId),
        )

    fun onEvent(event: PassDetailEvent) {
        when (event) {
            PassDetailEvent.RemindToggled -> onRemindToggled()

            PassDetailEvent.OpenInSkyClicked -> {
                // The same handoff a notification tap uses, rather than a second path into the sky
                // view: one mechanism means one thing to get right.
                guideRequests.request(noradId)
                effectChannel.trySend(PassDetailEffect.OpenSky)
            }
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        alertCapability.value = alertScheduler.capability
        if (granted) {
            onRemindToggled()
        } else {
            effectChannel.trySend(
                PassDetailEffect.ShowMessage(
                    "Reminders need notification permission, which you can grant in Settings.",
                ),
            )
        }
    }

    private fun onRemindToggled() {
        val current = pass.value ?: return
        val id = passId(noradId, current.aos)

        if (state.value.hasAlert) {
            viewModelScope.launch {
                alertScheduler.cancel(id)
                effectChannel.trySend(PassDetailEffect.ShowMessage("Reminder cancelled."))
            }
            return
        }

        val capability = alertScheduler.capability.also { alertCapability.value = it }
        if (!capability.supported) {
            effectChannel.trySend(PassDetailEffect.ShowMessage("This device cannot show pass reminders."))
            return
        }
        if (capability.needsPermission) {
            effectChannel.trySend(PassDetailEffect.RequestNotificationPermission)
            return
        }

        viewModelScope.launch {
            val name = state.value.satelliteName
            alertScheduler.schedule(PassAlert.from(current, name, clock.now()))
                .onSuccess {
                    val lead = PassAlert.DEFAULT_LEAD_TIME.inWholeMinutes
                    effectChannel.trySend(PassDetailEffect.ShowMessage("You will be told $lead minutes before."))
                }
                .onFailure {
                    effectChannel.trySend(
                        PassDetailEffect.ShowMessage(it.message ?: "Could not set that reminder."),
                    )
                }
        }
    }

    override fun onCleared() {
        // Followed only for the life of this screen, unless the user pinned it elsewhere. The
        // distinction is the tracker's; unfollowing something they explicitly followed would be a
        // side effect of having looked at a pass.
        if (!tracker.isFollowed(noradId)) tracker.unfollow(noradId)
        super.onCleared()
    }

    /**
     * Where the satellite is during a pass in progress.
     *
     * Asked of the tracker, not interpolated along the stored arc: the detail plot is 240 dp
     * across, where the difference between a real position and one sampled every fifteen seconds
     * is several pixels of visible stepping.
     */
    private fun livePoint(pass: Pass, now: Instant): SkyPoint? {
        if (now < pass.aos || now > pass.los) return null
        val topo = tracker.interpolated(noradId, now) ?: return null
        return SkyPoint(topo.azimuthDeg.toFloat(), topo.elevationDeg.toFloat())
    }

    private fun Transmitter.toRow(): DownlinkRowUi = DownlinkRowUi(
        id = id,
        description = description,
        downlinkHz = downlinkLowHz,
        modeLabel = mode.name.replace('_', ' '),
        isActive = status == TxStatus.ACTIVE,
    )

    /**
     * Which downlink the Doppler preview is drawn for.
     *
     * The highest active frequency, because shift scales with it: at 2 m a pass swings about
     * ±3 kHz and at 70 cm about ±10 kHz, and the preview exists to show the user how much retuning
     * a pass demands. Showing the gentlest curve when the satellite also has a harsh one would
     * understate exactly what the feature is there to warn about.
     */
    private fun List<Transmitter>.pickForDopplerPreview(): Transmitter? =
        filter { it.status == TxStatus.ACTIVE }.maxByOrNull { it.downlinkLowHz }
            ?: maxByOrNull { it.downlinkLowHz }

    private companion object {
        val TICK = 1.seconds
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

        /** Wide enough to find any pass the seven-day timeline could have linked to. */
        val SEARCH_WINDOW: Duration = 8.days

        /** How far the found pass may sit from the one the route named and still be it. */
        val MATCH_TOLERANCE: Duration = 20.minutes
    }
}

/** Elevation against time, normalised so the chart does not have to know about instants. */
internal fun Pass.elevationCurve(): List<CurvePoint> {
    val span = (los - aos).inWholeSeconds.takeIf { it > 0 } ?: return emptyList()
    return arc.map { sample ->
        CurvePoint(
            fraction = ((sample.time - aos).inWholeSeconds.toFloat() / span).coerceIn(0f, 1f),
            value = sample.topo.elevationDeg.toFloat(),
        )
    }
}

internal class DopplerCurve(val points: List<CurvePoint>, val peakHz: Double)

/**
 * Doppler shift across the pass, for one downlink.
 *
 * Computed from the range rates already stored in the arc, so the preview costs no propagation.
 * Values are normalised to ±1 of the pass's own peak and the peak reported alongside, because the
 * shape is the message — a steep S through the culmination is what says "this one will need
 * constant retuning" — while the number is what says how much.
 */
internal fun Pass.dopplerCurve(transmitHz: Long): DopplerCurve {
    val span = (los - aos).inWholeSeconds.takeIf { it > 0 } ?: return DopplerCurve(emptyList(), 0.0)
    val shifts = arc.map { Doppler.shiftHz(transmitHz, it.topo.rangeRateKmS) }
    val peak = shifts.maxOfOrNull { abs(it) }?.takeIf { it > 0.0 } ?: return DopplerCurve(emptyList(), 0.0)
    return DopplerCurve(
        points = arc.mapIndexed { index, sample ->
            CurvePoint(
                fraction = ((sample.time - aos).inWholeSeconds.toFloat() / span).coerceIn(0f, 1f),
                value = (shifts[index] / peak).toFloat(),
            )
        },
        peakHz = peak,
    )
}
