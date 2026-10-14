package com.developerstring.satride.core.data

import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.runCatchingCancellable
import com.developerstring.satride.core.database.dao.TransmitterDao
import com.developerstring.satride.core.database.toDomain
import com.developerstring.satride.core.database.toEntity
import com.developerstring.satride.core.model.Transmitter
import com.developerstring.satride.core.network.api.SatNogsApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class DefaultTransmitterRepository(
    private val satNogsApi: SatNogsApi,
    private val transmitterDao: TransmitterDao,
    private val dispatchers: AppDispatchers,
) : TransmitterRepository {

    override fun observeForSatellite(noradId: Int): Flow<List<Transmitter>> =
        transmitterDao.observeForSatellite(noradId).map { rows -> rows.map { it.toDomain() } }

    override fun observeAll(): Flow<List<Transmitter>> =
        transmitterDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun forSatellite(noradId: Int): List<Transmitter> = withContext(dispatchers.io) {
        transmitterDao.forSatellite(noradId).map { it.toDomain() }
    }

    /**
     * Replaces the stored catalog with a freshly fetched one.
     *
     * Replace rather than merge, because transmitters are retired upstream: a satellite's
     * downlink gets marked dead when it stops working, and an upsert-only refresh would leave the
     * user tuned to a frequency that has nothing on it, wondering why they can hear nothing.
     *
     * An empty response is treated as a failure rather than as "everything was retired" — that
     * shape almost always means an endpoint problem, and wiping the catalog on the strength of it
     * would leave the radio half of the app with nothing to tune.
     */
    override suspend fun refresh(): Result<Int> = withContext(dispatchers.io) {
        runCatchingCancellable {
            val transmitters = satNogsApi.transmitters()
            if (transmitters.isEmpty()) {
                error("SatNOGS returned no transmitters; keeping the stored catalog")
            }
            transmitterDao.replaceAll(transmitters.map { it.toEntity() })
            transmitters.size
        }
    }
}
