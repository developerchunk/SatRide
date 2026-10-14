package com.developerstring.satride.core.data

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.runCatchingCancellable
import com.developerstring.satride.core.database.dao.SatelliteDao
import com.developerstring.satride.core.database.dao.TleDao
import com.developerstring.satride.core.database.dao.TransmitterDao
import com.developerstring.satride.core.database.entity.SatelliteEntity
import com.developerstring.satride.core.database.toEntity
import com.developerstring.satride.core.network.GpGroup
import com.developerstring.satride.core.network.SatRideHttpClient
import com.developerstring.satride.core.orbit.sgp4.TleFormatter
import com.developerstring.satride.core.orbit.sgp4.TleParser
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * Loads the bundled catalog on first launch.
 *
 * Without this the first thing a new user sees is an empty screen and a network request, which is
 * the worst possible first impression for an app whose whole claim is that it works offline. With
 * it, the catalog and its pass predictions are there before the first frame.
 *
 * Runs only when the database is empty. It is a starting point, not a source of truth — the first
 * successful refresh supersedes it, and re-seeding afterwards would overwrite fresher data with
 * whatever was true on the day the release was built.
 */
class CatalogSeeder(
    private val source: CatalogSnapshotSource,
    private val satelliteDao: SatelliteDao,
    private val tleDao: TleDao,
    private val transmitterDao: TransmitterDao,
    private val dispatchers: AppDispatchers,
) {

    private val logger = Logger.withTag("CatalogSeeder")

    data class SeedResult(val satellites: Int, val transmitters: Int, val skipped: Boolean)

    /** Seeds if the catalog is empty. Returns what happened, so a caller can log or show it. */
    suspend fun seedIfEmpty(): Result<SeedResult> = runCatchingCancellable {
        val existing = withContext(dispatchers.io) { satelliteDao.count() }
        if (existing > 0) {
            return@runCatchingCancellable SeedResult(0, 0, skipped = true)
        }

        val json = source.read()
        if (json == null) {
            logger.w { "no bundled catalog snapshot; first launch will need the network" }
            return@runCatchingCancellable SeedResult(0, 0, skipped = true)
        }

        val snapshot = withContext(dispatchers.default) {
            SatRideHttpClient.json.decodeFromString<CatalogSnapshot>(json)
        }
        seed(snapshot)
    }

    /** Writes a snapshot unconditionally. Split out so it can be tested without the empty check. */
    suspend fun seed(snapshot: CatalogSnapshot): SeedResult {
        // Stamped with when the *snapshot* was built, not when it was unpacked. Recording "now"
        // would tell the refresh policy the catalog had just been downloaded, so a build that sat
        // in review for three weeks would suppress its own first refresh and start the user off
        // on three-week-old elements — the exact case the bundle exists to bridge, not to hide.
        val fetchedAt = runCatchingCancellable { Instant.parse(snapshot.generatedAt) }
            .getOrElse {
                logger.w { "snapshot has an unreadable generatedAt (${snapshot.generatedAt})" }
                Instant.DISTANT_PAST
            }

        val parsed = withContext(dispatchers.default) {
            TleParser.parseCatalog(snapshot.tleText)
        }
        // Invert the group map once: classification needs group-by-satellite, and scanning the
        // lists per satellite would be quadratic over a few hundred entries.
        val groupOf: Map<Int, GpGroup> = buildMap {
            snapshot.groups.forEach { (queryValue, ids) ->
                val group = GpGroup.entries.firstOrNull { it.queryValue == queryValue }
                if (group != null) ids.forEach { put(it, group) }
            }
        }

        val tles = parsed.map(TleFormatter::format)
        val designators = parsed.associate { it.noradId to it.internationalDesignator.ifBlank { null } }
        val satellites = tles.map { tle ->
            SatelliteEntity(
                noradId = tle.noradId,
                name = tle.name.ifBlank { "Object ${tle.noradId}" },
                classType = SatelliteClassifier.classify(tle.name, groupOf[tle.noradId]).name,
                internationalDesignator = designators[tle.noradId],
                // The defining property of the bundled set: it is what the app can show before
                // any network call has ever succeeded.
                curated = true,
            )
        }
        val transmitters = snapshot.transmitters.mapNotNull { it.toTransmitter() }

        withContext(dispatchers.io) {
            satelliteDao.upsert(satellites)
            tleDao.upsert(tles.map { it.toEntity(fetchedAt = fetchedAt, sourceId = SNAPSHOT_SOURCE_ID) })
            if (transmitters.isNotEmpty()) {
                transmitterDao.upsert(transmitters.map { it.toEntity() })
            }
        }

        logger.i { "seeded ${satellites.size} satellites and ${transmitters.size} transmitters" }
        return SeedResult(satellites.size, transmitters.size, skipped = false)
    }

    companion object {
        const val SNAPSHOT_SOURCE_ID: String = "bundled-snapshot"
    }
}
