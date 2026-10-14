package com.developerstring.satride.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.data.SatelliteRepository
import com.developerstring.satride.core.data.SatelliteTracker
import com.developerstring.satride.core.model.ObserverPosition
import com.developerstring.satride.core.model.SatelliteSnapshot
import com.developerstring.satride.core.orbit.doppler.Doppler
import com.developerstring.satride.core.orbit.math.Quaternion
import com.developerstring.satride.core.sensors.AttitudeFrames
import com.developerstring.satride.core.sensors.AttitudeProvider
import com.developerstring.satride.core.sensors.AzimuthElevation
import com.developerstring.satride.core.sensors.CompassAccuracy
import com.developerstring.satride.core.sensors.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the live tracking table. */
data class TrackedRow(
    val noradId: Int,
    val name: String,
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val rangeKm: Double,
    val rangeRateKmS: Double,
    val sunlit: Boolean,
    /** Angle between where the phone points and this satellite, or null with no compass. */
    val separationDeg: Double?,
)

data class TrackingDebugState(
    val observer: ObserverPosition = ObserverPosition.Unknown,
    val accuracy: CompassAccuracy = CompassAccuracy.UNCALIBRATED,
    val compassAvailable: Boolean = false,
    val pointing: AzimuthElevation? = null,
    val trackedCount: Int = 0,
    val rows: List<TrackedRow> = emptyList(),
)

/**
 * The P3 verification surface.
 *
 * Its only job is to make the whole sensor and tracking chain visible at once, so it can be
 * checked against a reference tracker on a real phone: where the app thinks the user is, what
 * the compass thinks it is pointing at, and where every tracked satellite is right now. Numbers
 * that look right in a unit test can still be ninety degrees out on a device, and this is what
 * catches that.
 */
class TrackingDebugViewModel(
    private val trackingEngine: SatelliteTracker,
    private val attitudeProvider: AttitudeProvider,
    private val locationProvider: LocationProvider,
    satelliteRepository: SatelliteRepository,
) : ViewModel() {

    private val attitude = MutableStateFlow<Quaternion?>(null)

    init {
        // Collected in the view model rather than the composable so the sensor keeps running
        // across a recomposition, and stops when the screen's scope dies.
        viewModelScope.launch {
            attitudeProvider.attitude.collect { attitude.value = it.rotation }
        }
        viewModelScope.launch { locationProvider.requestFix() }
    }

    private val names = satelliteRepository.observeAll()
        .map { list -> list.associate { it.noradId to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val state: StateFlow<TrackingDebugState> = combine(
        trackingEngine.sky,
        trackingEngine.observer,
        attitudeProvider.accuracy,
        attitude,
        names,
    ) { sky, observer, accuracy, pointing, nameOf ->
        TrackingDebugState(
            observer = observer,
            accuracy = accuracy,
            compassAvailable = attitudeProvider.isAvailable,
            pointing = pointing?.let { AttitudeFrames.pointingAzimuthElevation(it) },
            trackedCount = sky.size,
            // Highest first, and only the top ten: these are the satellites a user can actually
            // check against the sky, and the ones a reference tracker agrees on most tightly.
            // `upNow` does the ordering over the column-wise table, so nothing is allocated for
            // the thousands of objects that will not be shown.
            rows = sky.upNow(limit = 10).mapNotNull { noradId ->
                sky.snapshotOf(noradId)?.toRow(pointing, nameOf)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackingDebugState())

    private fun SatelliteSnapshot.toRow(pointing: Quaternion?, nameOf: Map<Int, String>): TrackedRow = TrackedRow(
        noradId = noradId,
        name = nameOf[noradId] ?: "Object $noradId",
        azimuthDeg = topo.azimuthDeg,
        elevationDeg = topo.elevationDeg,
        rangeKm = topo.rangeKm,
        rangeRateKmS = topo.rangeRateKmS,
        sunlit = sunlit,
        separationDeg = pointing?.let { AttitudeFrames.angularSeparationDeg(it, topo) },
    )

    /**
     * Doppler shift at 145.800 MHz — the ISS voice downlink.
     *
     * Shown because it is the number a radio amateur can check against their own rig, and
     * because it is the first place a sign error in range rate would be obvious: the shift must
     * be positive while the satellite approaches and negative as it recedes.
     */
    fun dopplerHzAt(rangeRateKmS: Double, transmitHz: Long = ISS_VOICE_DOWNLINK_HZ): Long =
        Doppler.shiftHz(transmitHz, rangeRateKmS).toLong()

    companion object {
        const val ISS_VOICE_DOWNLINK_HZ: Long = 145_800_000L
    }
}
