package com.developerstring.satride.core.data

import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.AppError
import com.developerstring.satride.core.common.runCatchingCancellable
import com.developerstring.satride.core.database.dao.SatelliteDao
import com.developerstring.satride.core.database.dao.TleDao
import com.developerstring.satride.core.database.entity.SatelliteEntity
import com.developerstring.satride.core.database.toDomain
import com.developerstring.satride.core.database.toEntity
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Satellite
import com.developerstring.satride.core.orbit.sgp4.TleFormatter
import com.developerstring.satride.core.orbit.sgp4.TleParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock

class DefaultSatelliteRepository(
    private val satelliteDao: SatelliteDao,
    private val tleDao: TleDao,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
) : SatelliteRepository {

    override fun observeCurated(): Flow<List<Satellite>> =
        satelliteDao.observeCurated().map { rows -> rows.map { it.toDomain() } }

    override fun observeAll(): Flow<List<Satellite>> =
        satelliteDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeTracked(): Flow<List<Satellite>> =
        satelliteDao.observeTracked().map { rows -> rows.map { it.toDomain() } }

    override fun observe(noradId: Int): Flow<Satellite?> = satelliteDao.observe(noradId).map { it?.toDomain() }

    override fun search(query: String): Flow<List<Satellite>> {
        val trimmed = query.trim()
        // An empty query means "no filter", not "match everything one character at a time" —
        // sending it to LIKE would scan the whole catalog on the first keystroke's backspace.
        if (trimmed.isEmpty()) return observeAll()
        return satelliteDao.search(trimmed).map { rows -> rows.map { it.toDomain() } }
    }

    override fun observeByClass(classType: SatClass): Flow<List<Satellite>> =
        satelliteDao.observeByClass(classType.name).map { rows -> rows.map { it.toDomain() } }

    override fun observeWithTransmitters(): Flow<List<Satellite>> =
        satelliteDao.observeWithTransmitters().map { rows -> rows.map { it.toDomain() } }

    override suspend fun find(noradId: Int): Satellite? = withContext(dispatchers.io) {
        satelliteDao.find(noradId)?.toDomain()
    }

    override suspend fun count(): Int = withContext(dispatchers.io) { satelliteDao.count() }

    /**
     * Imports element sets the user pasted or opened.
     *
     * Parsed strictly, unlike a catalog fetch: text that came from a person is far more likely to
     * have lost a character to a copy-paste than a catalog is to be serving corruption, and here a
     * checksum failure is genuinely useful feedback rather than a satellite silently missing.
     *
     * The whole import is rejected if nothing parses, so the user finds out immediately instead of
     * discovering an empty result later.
     */
    override suspend fun importTle(text: String): Result<Int> = withContext(dispatchers.default) {
        runCatchingCancellable {
            val elements = TleParser.parseCatalog(text, strict = true)
            if (elements.isEmpty()) {
                throw AppError.InvalidInput(
                    "No element sets found. Expected two-line element text, optionally with a " +
                        "name line above each pair.",
                )
            }

            val now = clock.now()
            val tles = elements.map(TleFormatter::format)

            withContext(dispatchers.io) {
                tleDao.upsert(tles.map { it.toEntity(fetchedAt = now, sourceId = USER_IMPORT_SOURCE_ID) })
                satelliteDao.upsertFromCatalog(
                    tles.map {
                        SatelliteEntity(
                            noradId = it.noradId,
                            name = it.name.ifBlank { "Object ${it.noradId}" },
                            classType = SatelliteClassifier.classify(it.name, group = null).name,
                        )
                    },
                )
            }
            tles.size
        }
    }

    companion object {
        /** Marks element sets the user supplied, so a refresh can be seen not to have replaced them. */
        const val USER_IMPORT_SOURCE_ID: String = "user-import"
    }
}
