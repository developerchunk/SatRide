package com.developerstring.satride.core.data

import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.database.dao.SatelliteDao
import com.developerstring.satride.core.database.toDomain
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.core.datastore.TrackingBudget
import com.developerstring.satride.core.datastore.TrackingMode
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Satellite
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * How the catalog is narrowed on the tracking screen.
 *
 * One value rather than four parameters because every operation on that screen takes the same
 * four — list them, count them, select all of them — and a bulk action that used a subtly
 * different filter from the list it was launched over would select the wrong satellites.
 */
data class CatalogQuery(
    val text: String = "",
    val classes: Set<SatClass> = emptySet(),
    val trackedOnly: Boolean = false,
    val withDownlinkOnly: Boolean = false,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && classes.isEmpty() && !trackedOnly && !withDownlinkOnly

    internal val classNames: List<String> get() = classes.map { it.name }
}

/** What the tracking engine has been asked to keep live, and how much it may spend doing it. */
data class TrackingSelection(
    val mode: TrackingMode,
    val budget: TrackingBudget,
    /** Ascending, and the engine's input verbatim. Empty means "nothing selected yet". */
    val noradIds: IntArray,
) {
    val size: Int get() = noradIds.size

    /**
     * Whether a satellite is in the selection.
     *
     * A binary search over the sorted ids, which is what the ordering exists for. Callers filter a
     * whole catalog through this — the sky's label lookup, the globe's field, the "Tracked"
     * segment — and the alternative, materialising a `Set<Int>`, boxes thirty thousand integers
     * every time any of those re-emits.
     *
     * Written out rather than `IntArray.binarySearch`, which only exists on the JVM: this class is
     * common code and the iOS build is where that would have been found.
     */
    operator fun contains(noradId: Int): Boolean {
        var low = 0
        var high = noradIds.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = noradIds[mid]
            when {
                value < noradId -> low = mid + 1
                value > noradId -> high = mid - 1
                else -> return true
            }
        }
        return false
    }

    /**
     * Array equality by content.
     *
     * The engine deduplicates on this to decide whether to rebuild every propagator it holds, and
     * `IntArray`'s inherited `equals` is identity — which would rebuild the whole selection on
     * every emission of an unchanged list.
     */
    override fun equals(other: Any?): Boolean = other is TrackingSelection &&
        other.mode == mode && other.budget == budget && other.noradIds.contentEquals(noradIds)

    override fun hashCode(): Int = (mode.hashCode() * 31 + budget.hashCode()) * 31 + noradIds.contentHashCode()

    companion object {
        val EMPTY: TrackingSelection =
            TrackingSelection(TrackingMode.CURATED, TrackingBudget.default, IntArray(0))
    }
}

/**
 * The user's answer to "which satellites am I tracking".
 *
 * Kept out of `SatelliteRepository` because it is a different question with a different lifetime:
 * the catalog is data the app fetches, and this is a decision the user makes about it. It is also
 * the one thing in the app that can turn a screen from cheap into expensive, so having it behind a
 * single named port makes it possible to see — in one place — everything that can grow the working
 * set.
 */
interface TrackingSelectionRepository {
    /** What the engine propagates. Resolves [TrackingMode] against the stored selection. */
    fun selection(): Flow<TrackingSelection>

    /** Catalog numbers the user has ticked, regardless of the active mode. */
    fun observeSelectedIds(): Flow<List<Int>>

    fun observeSelectedCount(): Flow<Int>

    fun observeCatalogCount(): Flow<Int>

    /** Population per class, for the counts beside the class chips. */
    fun observeClassCounts(): Flow<Map<SatClass, Int>>

    /**
     * A page of the catalog under [query].
     *
     * Paged rather than complete: the full catalog is tens of thousands of rows, and a `Flow` that
     * carries all of them re-marshals all of them every time one checkbox changes.
     */
    fun browse(query: CatalogQuery, limit: Int, offset: Int): Flow<List<Satellite>>

    /** How many rows [browse] would return unpaged. */
    fun countMatching(query: CatalogQuery): Flow<Int>

    suspend fun setMode(mode: TrackingMode)

    suspend fun setBudget(budget: TrackingBudget)

    suspend fun setTracked(noradIds: Collection<Int>, tracked: Boolean)

    /** Selects — or deselects — everything matching [query]. Returns how many rows changed. */
    suspend fun setTrackedForQuery(query: CatalogQuery, tracked: Boolean): Int

    /** Ticks every object in the catalog. Returns the resulting count. */
    suspend fun selectAll(): Int

    suspend fun clearSelection()

    /** Ticks the bundled starter set — the "sensible default" escape hatch from a huge selection. */
    suspend fun selectCurated(): Int

    /** Ticks everything with a receivable downlink. */
    suspend fun selectWithDownlink(): Int
}

class DefaultTrackingSelectionRepository(
    private val satelliteDao: SatelliteDao,
    private val settings: SettingsRepository,
    private val dispatchers: AppDispatchers,
) : TrackingSelectionRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun selection(): Flow<TrackingSelection> = settings.settings
        .map { it.trackingMode to it.trackingBudget }
        .distinctUntilChanged()
        .flatMapLatest { (mode, budget) ->
            val ids: Flow<List<Int>> = when (mode) {
                // Curated is a stored flag rather than a fixed list, so it follows the bundled
                // snapshot as that is updated rather than being frozen at whatever shipped.
                TrackingMode.CURATED -> satelliteDao.observeCurated().map { rows -> rows.map { it.noradId } }

                TrackingMode.SELECTED -> satelliteDao.observeTrackedIds()

                TrackingMode.ALL -> satelliteDao.observeAllIds()
            }
            ids.map { list ->
                // Sorted because `LiveSky` binary-searches it, and the engine diffs it against the
                // previous selection to decide what to rebuild. Both need a canonical order.
                val sorted = list.toIntArray().also { it.sort() }
                TrackingSelection(mode, budget, sorted)
            }
        }
        .distinctUntilChanged()

    override fun observeSelectedIds(): Flow<List<Int>> = satelliteDao.observeTrackedIds()

    override fun observeSelectedCount(): Flow<Int> = satelliteDao.observeTrackedCount()

    override fun observeCatalogCount(): Flow<Int> = satelliteDao.observeCount()

    override fun observeClassCounts(): Flow<Map<SatClass, Int>> = satelliteDao.observeClassCounts().map { rows ->
        rows.mapNotNull { row ->
            val satClass = SatClass.entries.firstOrNull { it.name == row.classType }
            satClass?.let { it to row.total }
        }.toMap()
    }

    override fun browse(query: CatalogQuery, limit: Int, offset: Int): Flow<List<Satellite>> = satelliteDao.browse(
        query = query.text.trim(),
        classTypes = query.classNames,
        classCount = query.classes.size,
        trackedOnly = query.trackedOnly,
        withDownlinkOnly = query.withDownlinkOnly,
        limit = limit,
        offset = offset,
    ).map { rows -> rows.map { it.toDomain() } }

    override fun countMatching(query: CatalogQuery): Flow<Int> = satelliteDao.browseCount(
        query = query.text.trim(),
        classTypes = query.classNames,
        classCount = query.classes.size,
        trackedOnly = query.trackedOnly,
        withDownlinkOnly = query.withDownlinkOnly,
    )

    override suspend fun setMode(mode: TrackingMode) {
        settings.setTrackingMode(mode)
        // Switching to a custom selection with nothing ticked would silently blank the sky. The
        // starter set is the honest thing to land on: it is what the user was already seeing.
        if (mode == TrackingMode.SELECTED && satelliteDao.trackedIds().isEmpty()) {
            selectCurated()
        }
    }

    override suspend fun setBudget(budget: TrackingBudget) {
        settings.setTrackingBudget(budget)
    }

    override suspend fun setTracked(noradIds: Collection<Int>, tracked: Boolean) = withContext(dispatchers.io) {
        // SQLite binds at most 999 parameters by default, and "select everything I filtered to"
        // routinely runs to thousands. Chunking is not an optimisation here; without it the
        // statement fails outright.
        noradIds.chunked(BIND_LIMIT).forEach { satelliteDao.setTracked(it, tracked) }
    }

    override suspend fun setTrackedForQuery(query: CatalogQuery, tracked: Boolean): Int = withContext(dispatchers.io) {
        val ids = satelliteDao.browseIds(
            query = query.text.trim(),
            classTypes = query.classNames,
            classCount = query.classes.size,
            trackedOnly = query.trackedOnly,
            withDownlinkOnly = query.withDownlinkOnly,
        )
        ids.chunked(BIND_LIMIT).forEach { satelliteDao.setTracked(it, tracked) }
        ids.size
    }

    override suspend fun selectAll(): Int = withContext(dispatchers.io) {
        // One statement over the table rather than a list of ids round-tripped through Kotlin.
        // For a full catalog that is the difference between an update and thirty thousand binds.
        satelliteDao.setTrackedForAll(tracked = true)
        satelliteDao.count()
    }

    override suspend fun clearSelection() = withContext(dispatchers.io) {
        satelliteDao.setTrackedForAll(tracked = false)
    }

    override suspend fun selectCurated(): Int = withContext(dispatchers.io) {
        satelliteDao.setTrackedForAll(tracked = false)
        satelliteDao.setTrackedForCurated(tracked = true)
        satelliteDao.trackedIds().size
    }

    override suspend fun selectWithDownlink(): Int = withContext(dispatchers.io) {
        satelliteDao.setTrackedForTransmitting(tracked = true)
        satelliteDao.trackedIds().size
    }

    private companion object {
        /** SQLite's default `SQLITE_MAX_VARIABLE_NUMBER`, minus room for the statement's own binds. */
        const val BIND_LIMIT = 900
    }
}

/**
 * Combines the selection with what is actually live, for the tracking screen's header.
 *
 * A convenience rather than a port: it exists so the screen can say "4,812 selected · 400 live ·
 * 37 up now" without three separate subscriptions in the view model.
 */
fun TrackingSelectionRepository.summary(sky: Flow<LiveSky>): Flow<TrackingSummary> = combine(
    selection(),
    observeSelectedCount(),
    observeCatalogCount(),
    sky,
) { selection, selected, catalog, live ->
    TrackingSummary(
        mode = selection.mode,
        budget = selection.budget,
        trackingCount = selection.size,
        selectedCount = selected,
        catalogCount = catalog,
        upNowCount = live.countUp(),
        refreshedPerTick = live.refreshedCount,
    )
}

data class TrackingSummary(
    val mode: TrackingMode,
    val budget: TrackingBudget,
    /** Objects the engine has been asked to keep live. */
    val trackingCount: Int,
    /** Objects the user has ticked, whether or not the active mode uses them. */
    val selectedCount: Int,
    val catalogCount: Int,
    val upNowCount: Int,
    /** Objects propagated on the most recent tick — the honest cost of the current selection. */
    val refreshedPerTick: Int,
)
