package com.developerstring.satride.core.data

import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.database.dao.PassAlertDao
import com.developerstring.satride.core.database.entity.PassAlertEntity
import com.developerstring.satride.core.notifications.AlertStore
import com.developerstring.satride.core.notifications.PassAlert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Alerts, persisted.
 *
 * The mapping lives here rather than in `core:database` because the alert type belongs to
 * `core:notifications`, and storage has no business knowing about it — the same reason the pass
 * and satellite mappers live beside their repositories.
 */
class RoomAlertStore(private val dao: PassAlertDao, private val dispatchers: AppDispatchers) : AlertStore {

    override fun observeAll(): Flow<List<PassAlert>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun all(): List<PassAlert> = withContext(dispatchers.io) {
        dao.all().map { it.toDomain() }
    }

    override suspend fun put(alert: PassAlert) = withContext(dispatchers.io) {
        dao.upsert(alert.toEntity())
    }

    override suspend fun remove(alertId: String) = withContext(dispatchers.io) {
        dao.delete(alertId)
    }

    override suspend fun removePassed(now: Instant) = withContext(dispatchers.io) {
        dao.deletePassed(now.toEpochMilliseconds())
    }
}

internal fun PassAlertEntity.toDomain(): PassAlert = PassAlert(
    id = id,
    noradId = noradId,
    satelliteName = satelliteName,
    aos = Instant.fromEpochMilliseconds(aosMillis),
    maxElevationDeg = maxElevationDeg,
    aosAzimuthDeg = aosAzimuthDeg,
    leadTime = leadTimeSeconds.seconds,
    createdAt = Instant.fromEpochMilliseconds(createdAtMillis),
)

internal fun PassAlert.toEntity(): PassAlertEntity = PassAlertEntity(
    id = id,
    noradId = noradId,
    satelliteName = satelliteName,
    aosMillis = aos.toEpochMilliseconds(),
    maxElevationDeg = maxElevationDeg,
    aosAzimuthDeg = aosAzimuthDeg,
    leadTimeSeconds = leadTime.inWholeSeconds,
    createdAtMillis = createdAt.toEpochMilliseconds(),
)
