package com.developerstring.satride.feature.satellites

import com.developerstring.satride.core.data.CatalogQuery
import com.developerstring.satride.core.data.CatalogRefresher
import com.developerstring.satride.core.data.LiveSky
import com.developerstring.satride.core.data.PassRepository
import com.developerstring.satride.core.data.RefreshProgress
import com.developerstring.satride.core.data.RefreshResult
import com.developerstring.satride.core.data.RefreshState
import com.developerstring.satride.core.data.SatelliteRepository
import com.developerstring.satride.core.data.SatelliteTracker
import com.developerstring.satride.core.data.TleFreshness
import com.developerstring.satride.core.data.TleRepository
import com.developerstring.satride.core.data.TrackingSelection
import com.developerstring.satride.core.data.TrackingSelectionRepository
import com.developerstring.satride.core.data.TrackingStatus
import com.developerstring.satride.core.data.TransmitterRepository
import com.developerstring.satride.core.datastore.TrackingBudget
import com.developerstring.satride.core.datastore.TrackingMode
import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.ObserverPosition
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Satellite
import com.developerstring.satride.core.model.SatelliteSnapshot
import com.developerstring.satride.core.model.TimedTopo
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.model.Topocentric
import com.developerstring.satride.core.model.Transmitter
import com.developerstring.satride.core.model.TxMode
import com.developerstring.satride.core.model.TxStatus
import com.developerstring.satride.core.network.GpGroup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Fakes rather than mocks.
 *
 * Each holds real state and can be driven from a test, so the view model is exercised against
 * something that behaves like the thing it will meet in production. A mock would only prove that
 * the view model calls the methods this test already assumes it calls.
 */
internal class FakeSatelliteRepository(satellites: List<Satellite> = emptyList()) : SatelliteRepository {
    val all = MutableStateFlow(satellites)
    val transmitterOwners = MutableStateFlow<Set<Int>>(emptySet())
    var lastQuery: String? = null
        private set

    override fun observeCurated(): Flow<List<Satellite>> = all.map { list -> list.filter { it.curated } }
    override fun observeAll(): Flow<List<Satellite>> = all
    override fun observeTracked(): Flow<List<Satellite>> = all.map { list -> list.filter { it.tracked } }
    override fun observe(noradId: Int): Flow<Satellite?> =
        all.map { list -> list.firstOrNull { it.noradId == noradId } }

    override fun search(query: String): Flow<List<Satellite>> {
        lastQuery = query
        return all.map { list ->
            list.filter {
                it.name.contains(query, ignoreCase = true) || it.noradId.toString().startsWith(query)
            }
        }
    }

    override fun observeByClass(classType: SatClass): Flow<List<Satellite>> =
        all.map { list -> list.filter { it.classType == classType } }

    override fun observeWithTransmitters(): Flow<List<Satellite>> =
        all.map { list -> list.filter { it.noradId in transmitterOwners.value } }

    override suspend fun find(noradId: Int): Satellite? = all.value.firstOrNull { it.noradId == noradId }
    override suspend fun count(): Int = all.value.size

    var importResult: Result<Int> = Result.success(1)
    var importedText: String? = null
        private set

    override suspend fun importTle(text: String): Result<Int> {
        importedText = text
        return importResult
    }
}

internal class FakeTransmitterRepository(transmitters: List<Transmitter> = emptyList()) : TransmitterRepository {
    val all = MutableStateFlow(transmitters)

    override fun observeForSatellite(noradId: Int): Flow<List<Transmitter>> =
        all.map { list -> list.filter { it.noradId == noradId } }

    override fun observeAll(): Flow<List<Transmitter>> = all
    override suspend fun forSatellite(noradId: Int): List<Transmitter> = all.value.filter { it.noradId == noradId }
    override suspend fun refresh(): Result<Int> = Result.success(all.value.size)
}

internal class FakePassRepository(passes: List<Pass> = emptyList()) : PassRepository {
    val upcoming = MutableStateFlow(passes)

    override fun upcoming(window: Duration): Flow<List<Pass>> = upcoming
    override suspend fun passesFor(noradId: Int, window: Duration): List<Pass> =
        upcoming.value.filter { it.noradId == noradId }

    override suspend fun passesFor(noradId: Int, observer: GeoPos, window: Duration): List<Pass> =
        passesFor(noradId, window)

    override suspend fun nextPass(noradId: Int, observer: GeoPos): Pass? =
        passesFor(noradId, window = Duration.ZERO).firstOrNull()
    override suspend fun prune() = Unit

    /** Added to the contract in P6 so the passes timeline can widen the precomputed window. */
    override suspend fun refreshUpcoming(window: Duration): Int = upcoming.value.size
}

internal class FakeTleRepository(freshness: TleFreshness = TleFreshness.Empty) : TleRepository {
    val freshness = MutableStateFlow(freshness)
    var elementAge: Duration? = null

    override suspend fun tleFor(noradId: Int): Tle? = null
    override suspend fun tlesFor(noradIds: List<Int>): Map<Int, Tle> = emptyMap()
    override fun freshness(): Flow<TleFreshness> = freshness
    override suspend fun elementAgeOf(noradId: Int): Duration? = elementAge
    override suspend fun refresh(groups: Set<GpGroup>, onProgress: (RefreshProgress) -> Unit): RefreshResult =
        RefreshResult.empty()
    override suspend fun refreshIfStale(groups: Set<GpGroup>): RefreshResult? = null
}

/**
 * The tracking selection, in memory.
 *
 * Backed by a set rather than by the repository's own `tracked` column, because a view-model test
 * cares that ticking a satellite reaches storage and comes back — not how Room spells it.
 */
internal class FakeTrackingSelectionRepository(
    initiallySelected: Set<Int> = emptySet(),
    private val catalog: MutableStateFlow<List<Satellite>> = MutableStateFlow(emptyList()),
) : TrackingSelectionRepository {
    val selected = MutableStateFlow(initiallySelected)
    val mode = MutableStateFlow(TrackingMode.CURATED)
    val budget = MutableStateFlow(TrackingBudget.default)

    override fun selection(): Flow<TrackingSelection> = combineSelection()

    private fun combineSelection(): Flow<TrackingSelection> =
        selected.map { TrackingSelection(mode.value, budget.value, it.sorted().toIntArray()) }

    override fun observeSelectedIds(): Flow<List<Int>> = selected.map { it.sorted() }
    override fun observeSelectedCount(): Flow<Int> = selected.map { it.size }
    override fun observeCatalogCount(): Flow<Int> = catalog.map { it.size }
    override fun observeClassCounts(): Flow<Map<SatClass, Int>> =
        catalog.map { list -> list.groupingBy { it.classType }.eachCount() }

    /**
     * A page, with `tracked` stamped from the selection.
     *
     * The stamping matters: the real query reads the column the selection writes, so a row's flag
     * and the selection can never disagree. A fake that returned the catalog's own (always false)
     * flag would make every toggle read as "not tracked" and turn a remove into an add — which is
     * exactly the bug this fidelity buys.
     */
    override fun browse(query: CatalogQuery, limit: Int, offset: Int): Flow<List<Satellite>> =
        combine(catalog, selected) { list, selected ->
            list.drop(offset).take(limit).map { it.copy(tracked = it.noradId in selected) }
        }

    override fun countMatching(query: CatalogQuery): Flow<Int> = catalog.map { it.size }

    override suspend fun setMode(mode: TrackingMode) {
        this.mode.value = mode
    }

    override suspend fun setBudget(budget: TrackingBudget) {
        this.budget.value = budget
    }

    override suspend fun setTracked(noradIds: Collection<Int>, tracked: Boolean) {
        selected.value = if (tracked) selected.value + noradIds else selected.value - noradIds.toSet()
    }

    override suspend fun setTrackedForQuery(query: CatalogQuery, tracked: Boolean): Int {
        val ids = catalog.value.map { it.noradId }
        setTracked(ids, tracked)
        return ids.size
    }

    override suspend fun selectAll(): Int {
        selected.value = catalog.value.map { it.noradId }.toSet()
        return selected.value.size
    }

    override suspend fun clearSelection() {
        selected.value = emptySet()
    }

    override suspend fun selectCurated(): Int {
        selected.value = catalog.value.filter { it.curated }.map { it.noradId }.toSet()
        return selected.value.size
    }

    override suspend fun selectWithDownlink(): Int = selected.value.size
}

/**
 * A tracker whose live positions the test sets directly.
 *
 * The real engine runs a perpetual one-second loop, which a virtual clock can never advance past.
 */
internal class FakeSatelliteTracker(observer: ObserverPosition = ObserverPosition.Manual(GeoPos(51.5, -0.12))) :
    SatelliteTracker {
    override val observer = MutableStateFlow(observer)
    override val sky = MutableStateFlow(LiveSky.EMPTY)
    override val status = MutableStateFlow(TrackingStatus())
    override val followedIds = MutableStateFlow<Set<Int>>(emptySet())

    private val snapshots = mutableMapOf<Int, SatelliteSnapshot>()

    override fun follow(noradId: Int) {
        followedIds.value = followedIds.value + noradId
    }

    override fun unfollow(noradId: Int) {
        followedIds.value = followedIds.value - noradId
    }

    override fun isFollowed(noradId: Int): Boolean = noradId in followedIds.value

    /** Nothing to refresh: these fakes hand out a fixed observer position. */
    override suspend fun requestFix() = Unit

    fun setUp(noradId: Int, elevationDeg: Double, azimuthDeg: Double = 180.0, sunlit: Boolean = false) {
        snapshots[noradId] = SatelliteSnapshot(
            noradId = noradId,
            time = Instant.parse("2026-08-02T12:00:00Z"),
            topo = Topocentric(azimuthDeg, elevationDeg, 800.0, -5.0),
            subPoint = GeoPos(0.0, 0.0, 420_000.0),
            footprintRadiusKm = 2300.0,
            speedKmS = 7.66,
            sunlit = sunlit,
        )
        publish()
    }

    fun clearPositions() {
        snapshots.clear()
        publish()
    }

    private fun publish() {
        sky.value = LiveSky.of(snapshots.values.toList(), version = sky.value.version + 1)
    }
}

/** A refresh a test can step through, so the progress bar can be watched mid-flight. */
internal class FakeRefreshController : CatalogRefresher {
    /**
     * Observable, so a test can *await* a refresh rather than assert it has already happened.
     *
     * The view models that start one do so after a settings write, and settings write on
     * DataStore's own real dispatcher — so the test scheduler reaches idle while the coroutine
     * that will call this is still suspended on a real thread. Awaiting is the only race-free way
     * to see it.
     */
    val callCount = MutableStateFlow(0)

    val calls: Int get() = callCount.value

    /** The groups the last refresh asked for; null means "whatever settings imply". */
    var lastGroups: Set<GpGroup>? = null
        private set

    private val _state = MutableStateFlow<RefreshState>(RefreshState.Idle)
    override val state: StateFlow<RefreshState> = _state

    override fun refresh(groups: Set<GpGroup>?) {
        if (_state.value.isRunning) return
        lastGroups = groups
        callCount.value += 1
        _state.value = RefreshState.Running(RefreshProgress(0, TOTAL_STEPS, "CelesTrak · Space stations"))
    }

    fun advanceTo(completed: Int, label: String) {
        _state.value = RefreshState.Running(RefreshProgress(completed, TOTAL_STEPS, label))
    }

    fun finish(result: RefreshResult?, message: String) {
        _state.value = RefreshState.Finished(result, message)
    }

    private companion object {
        const val TOTAL_STEPS = 11
    }
}

internal fun satellite(
    noradId: Int,
    name: String = "SAT $noradId",
    classType: SatClass = SatClass.OTHER,
    curated: Boolean = false,
) = Satellite(noradId = noradId, name = name, classType = classType, curated = curated)

internal fun transmitter(
    noradId: Int,
    id: String = "tx-$noradId",
    downlinkHz: Long = 145_800_000L,
    mode: TxMode = TxMode.FM,
) = Transmitter(
    id = id,
    noradId = noradId,
    description = "Downlink",
    downlinkLowHz = downlinkHz,
    mode = mode,
    status = TxStatus.ACTIVE,
)

internal fun pass(noradId: Int, aos: Instant, durationMinutes: Long = 10, maxElevationDeg: Double = 45.0): Pass {
    val los = aos + kotlin.time.Duration.parse("PT${durationMinutes}M")
    return Pass(
        noradId = noradId,
        aos = aos,
        tca = aos + kotlin.time.Duration.parse("PT${durationMinutes / 2}M"),
        los = los,
        maxElevationDeg = maxElevationDeg,
        aosAzimuthDeg = 200.0,
        losAzimuthDeg = 20.0,
        arc = listOf(TimedTopo(aos, Topocentric(200.0, 0.0, 2300.0, -6.5))),
    )
}
