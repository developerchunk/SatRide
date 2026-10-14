package com.developerstring.satride.core.notifications

import com.developerstring.satride.core.model.Pass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A clock a test moves by hand. Alerts are entirely about *when*, so nothing here uses the real one. */
internal class FakeClock(private var current: Instant) : Clock {
    override fun now(): Instant = current

    fun advance(by: Duration) {
        current += by
    }
}

internal class InMemoryAlertStore(initial: List<PassAlert> = emptyList()) : AlertStore {
    private val alerts = MutableStateFlow(initial.associateBy { it.id })

    override fun observeAll() = alerts.map { it.values.sortedBy { alert -> alert.aos } }

    override suspend fun all(): List<PassAlert> = alerts.value.values.sortedBy { it.aos }

    override suspend fun put(alert: PassAlert) {
        alerts.value = alerts.value + (alert.id to alert)
    }

    override suspend fun remove(alertId: String) {
        alerts.value = alerts.value - alertId
    }

    override suspend fun removePassed(now: Instant) {
        alerts.value = alerts.value.filterValues { it.aos > now }
    }
}

/** Records what the platform was asked to do, and can be told to fail. */
internal class RecordingPlatformScheduler(
    override var capability: AlertCapability = AlertCapability(supported = true, exact = true),
    private var failWith: Throwable? = null,
) : PlatformAlertScheduler {

    val armed = mutableListOf<PassAlert>()
    val disarmed = mutableListOf<String>()

    override suspend fun arm(alert: PassAlert): Result<Unit> {
        failWith?.let { return Result.failure(it) }
        armed += alert
        return Result.success(Unit)
    }

    override suspend fun disarm(alertId: String) {
        disarmed += alertId
    }

    fun failNext(error: Throwable) {
        failWith = error
    }
}

internal fun pass(
    noradId: Int = ISS,
    aos: Instant,
    maxElevationDeg: Double = 62.0,
    aosAzimuthDeg: Double = 270.0,
    duration: Duration = 9.minutes,
): Pass = Pass(
    noradId = noradId,
    aos = aos,
    tca = aos + duration / 2,
    los = aos + duration,
    maxElevationDeg = maxElevationDeg,
    aosAzimuthDeg = aosAzimuthDeg,
    losAzimuthDeg = (aosAzimuthDeg + 140.0) % 360.0,
    arc = emptyList(),
)

internal fun alert(
    noradId: Int = ISS,
    aos: Instant,
    name: String = "ISS (ZARYA)",
    maxElevationDeg: Double = 62.0,
    aosAzimuthDeg: Double = 270.0,
    leadTime: Duration = PassAlert.DEFAULT_LEAD_TIME,
    createdAt: Instant = aos - 1.minutes * 60,
): PassAlert = PassAlert(
    id = PassAlert.idFor(noradId, aos),
    noradId = noradId,
    satelliteName = name,
    aos = aos,
    maxElevationDeg = maxElevationDeg,
    aosAzimuthDeg = aosAzimuthDeg,
    leadTime = leadTime,
    createdAt = createdAt,
)

internal const val ISS = 25544
internal val T0: Instant = Instant.parse("2026-08-04T20:00:00Z")
