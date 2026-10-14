package com.developerstring.satride.feature.passes

import com.developerstring.satride.core.data.LiveSky
import com.developerstring.satride.core.data.PassRepository
import com.developerstring.satride.core.data.SatelliteRepository
import com.developerstring.satride.core.data.SatelliteTracker
import com.developerstring.satride.core.data.TrackingStatus
import com.developerstring.satride.core.data.TransmitterRepository
import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.ObserverPosition
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Satellite
import com.developerstring.satride.core.model.SatelliteSnapshot
import com.developerstring.satride.core.model.TimedTopo
import com.developerstring.satride.core.model.Topocentric
import com.developerstring.satride.core.model.Transmitter
import com.developerstring.satride.core.model.TxMode
import com.developerstring.satride.core.model.TxStatus
import com.developerstring.satride.core.notifications.AlertCapability
import com.developerstring.satride.core.notifications.AlertScheduler
import com.developerstring.satride.core.notifications.PassAlert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal const val ISS = 25544
internal const val NOAA = 33591

internal val T0: Instant = Instant.parse("2026-08-04T18:00:00Z")

internal class FixedClock(var instant: Instant = T0) : Clock {
    override fun now(): Instant = instant
}

internal class FakePassRepository(passes: List<Pass> = emptyList()) : PassRepository {
    val upcoming = MutableStateFlow(passes)
    val requestedWindows = mutableListOf<Duration>()
    val refreshedWindows = mutableListOf<Duration>()

    override fun upcoming(window: Duration): Flow<List<Pass>> {
        requestedWindows += window
        return upcoming.map { list -> list.filter { it.aos - T0 <= window } }
    }

    override suspend fun passesFor(noradId: Int, window: Duration): List<Pass> =
        upcoming.value.filter { it.noradId == noradId }

    override suspend fun passesFor(noradId: Int, observer: GeoPos, window: Duration): List<Pass> =
        passesFor(noradId, window)

    override suspend fun nextPass(noradId: Int, observer: GeoPos): Pass? = passesFor(noradId, Duration.INFINITE)
        .minByOrNull { it.aos }

    override suspend fun prune() = Unit

    override suspend fun refreshUpcoming(window: Duration): Int {
        refreshedWindows += window
        return 0
    }
}

internal class FakeSatelliteRepository(private val satellites: List<Satellite>) : SatelliteRepository {
    override fun observeCurated(): Flow<List<Satellite>> = flowOf(satellites)
    override fun observeAll(): Flow<List<Satellite>> = flowOf(satellites)
    override fun observeTracked(): Flow<List<Satellite>> = flowOf(satellites.filter { it.tracked })
    override fun observe(noradId: Int): Flow<Satellite?> = flowOf(satellites.firstOrNull { it.noradId == noradId })
    override fun search(query: String): Flow<List<Satellite>> = flowOf(emptyList())
    override fun observeByClass(classType: SatClass): Flow<List<Satellite>> = flowOf(emptyList())
    override fun observeWithTransmitters(): Flow<List<Satellite>> = flowOf(emptyList())
    override suspend fun find(noradId: Int): Satellite? = satellites.firstOrNull { it.noradId == noradId }
    override suspend fun count(): Int = satellites.size
    override suspend fun importTle(text: String): Result<Int> = Result.success(0)
}

internal class FakeTransmitterRepository(private val transmitters: List<Transmitter> = emptyList()) :
    TransmitterRepository {
    override fun observeForSatellite(noradId: Int): Flow<List<Transmitter>> =
        flowOf(transmitters.filter { it.noradId == noradId })

    override fun observeAll(): Flow<List<Transmitter>> = flowOf(transmitters)
    override suspend fun forSatellite(noradId: Int): List<Transmitter> = transmitters.filter { it.noradId == noradId }
    override suspend fun refresh(): Result<Int> = Result.success(0)
}

internal class FakeTracker(observer: GeoPos? = GeoPos(52.2, 0.13)) : SatelliteTracker {
    override val observer: StateFlow<ObserverPosition> = MutableStateFlow(
        observer?.let { ObserverPosition.Automatic(it) } ?: ObserverPosition.Unknown,
    )
    override val sky: StateFlow<LiveSky> = MutableStateFlow(LiveSky.EMPTY)
    override val status: StateFlow<TrackingStatus> = MutableStateFlow(TrackingStatus())
    private val followed = MutableStateFlow<Set<Int>>(emptySet())
    override val followedIds: StateFlow<Set<Int>> = followed
    var interpolatedTopo: Topocentric? = null

    override fun follow(noradId: Int) {
        followed.value = followed.value + noradId
    }

    override fun unfollow(noradId: Int) {
        followed.value = followed.value - noradId
    }

    override fun isFollowed(noradId: Int): Boolean = noradId in followed.value

    /** Nothing to refresh: these fakes hand out a fixed observer position. */
    override suspend fun requestFix() = Unit

    override fun interpolated(noradId: Int, time: Instant): Topocentric? = interpolatedTopo
}

internal class FakeAlertScheduler(
    override var capability: AlertCapability = AlertCapability(supported = true, exact = true),
) : AlertScheduler {
    val alerts = MutableStateFlow<List<PassAlert>>(emptyList())
    val cancelled = mutableListOf<String>()
    var failWith: Throwable? = null

    override suspend fun schedule(alert: PassAlert): Result<Unit> {
        failWith?.let { return Result.failure(it) }
        alerts.value = alerts.value.filterNot { it.id == alert.id } + alert
        return Result.success(Unit)
    }

    override suspend fun cancel(alertId: String) {
        cancelled += alertId
        alerts.value = alerts.value.filterNot { it.id == alertId }
    }

    override fun scheduled(): Flow<List<PassAlert>> = alerts
}

internal fun satellite(noradId: Int = ISS, name: String = "ISS (ZARYA)", classType: SatClass = SatClass.CREWED) =
    Satellite(noradId = noradId, name = name, classType = classType)

internal fun transmitter(
    noradId: Int = ISS,
    id: String = "tx-$noradId",
    downlinkHz: Long = 145_800_000,
    status: TxStatus = TxStatus.ACTIVE,
) = Transmitter(
    id = id,
    noradId = noradId,
    description = "Downlink",
    downlinkLowHz = downlinkHz,
    downlinkHighHz = null,
    mode = TxMode.FM,
    baud = null,
    status = status,
    invert = false,
    service = null,
)

/**
 * A pass with a plausible arc.
 *
 * The arc matters in these tests: the timeline downsamples it for the thumbnail and the detail
 * screen derives both curves from it, so a pass fixture with an empty arc would quietly pass tests
 * that a real one fails.
 */
internal fun pass(
    noradId: Int = ISS,
    aos: Instant,
    maxElevationDeg: Double = 62.0,
    duration: Duration = 10.minutes,
    aosAzimuthDeg: Double = 270.0,
    samples: Int = 41,
): Pass {
    val arc = (0 until samples).map { index ->
        val fraction = index.toDouble() / (samples - 1)
        // A symmetric hump: zero at the ends, peak in the middle.
        val elevation = maxElevationDeg * kotlin.math.sin(fraction * kotlin.math.PI)
        Topocentric(
            azimuthDeg = (aosAzimuthDeg + fraction * 140.0) % 360.0,
            elevationDeg = elevation,
            rangeKm = 400.0 + (1.0 - kotlin.math.sin(fraction * kotlin.math.PI)) * 1600.0,
            // Negative approaching, zero at culmination, positive receding.
            rangeRateKmS = -6.0 * kotlin.math.cos(fraction * kotlin.math.PI),
        ).let { TimedTopo(time = aos + duration * fraction, topo = it) }
    }
    return Pass(
        noradId = noradId,
        aos = aos,
        tca = aos + duration / 2,
        los = aos + duration,
        maxElevationDeg = maxElevationDeg,
        aosAzimuthDeg = aosAzimuthDeg,
        losAzimuthDeg = (aosAzimuthDeg + 140.0) % 360.0,
        arc = arc,
    )
}
