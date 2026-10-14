package com.developerstring.satride.core.data

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.ObserverPosition
import com.developerstring.satride.core.model.SatelliteSnapshot
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.model.Topocentric
import com.developerstring.satride.core.orbit.Orbit
import com.developerstring.satride.core.orbit.OrbitingSatellite
import com.developerstring.satride.core.sensors.AttitudeFrames
import com.developerstring.satride.core.sensors.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The live picture of the sky, as consumers see it.
 *
 * An interface because [TrackingEngine] runs a perpetual tick loop: correct in the app, impossible
 * under a test's virtual clock, which can never reach idle while a `while (true)` is scheduling
 * delays. Screens want positions, a follow toggle and a status line, so that is what they depend
 * on, and a test supplies those directly.
 */
public interface SatelliteTracker {
    public val observer: StateFlow<ObserverPosition>

    /**
     * Live positions for everything being tracked.
     *
     * Column-wise rather than a map of objects — see [LiveSky] for why that is the difference
     * between tracking three dozen satellites and tracking thirty thousand.
     */
    public val sky: StateFlow<LiveSky>

    /**
     * Satellites the user has explicitly pinned.
     *
     * Small by construction, and distinct from what is being tracked: the tracked set is a
     * selection that may run to the whole catalog, while this is the handful somebody tapped a
     * star on. Conflating the two made "followed only" on the pass timeline mean "everything".
     */
    public val followedIds: StateFlow<Set<Int>>

    /** How much of the selection is armed and what a tick currently costs. */
    public val status: StateFlow<TrackingStatus>

    public fun follow(noradId: Int)

    public fun unfollow(noradId: Int)

    public fun isFollowed(noradId: Int): Boolean

    /**
     * Asks the location provider for a fresh fix.
     *
     * Exposed on the tracker so a screen can prompt one the instant location permission is granted,
     * rather than waiting for the provider's own retry to come round. Features already depend on
     * the tracker; routing this through it avoids giving every screen a second location dependency.
     */
    public suspend fun requestFix()

    /** Position at an arbitrary instant, for the AR view's frame-rate reads. */
    public fun interpolated(noradId: Int, time: Instant): Topocentric? {
        val out = DoubleArray(2)
        if (!sky.value.interpolateInto(noradId, time.toEpochMicroseconds(), out)) return null
        val snapshot = sky.value.snapshotOf(noradId) ?: return null
        return Topocentric(out[0], out[1], snapshot.topo.rangeKm, snapshot.topo.rangeRateKmS)
    }

    /**
     * [interpolated] without allocating, for the AR renderer's inner loop.
     *
     * Writes azimuth into `out[0]` and elevation into `out[1]`, in degrees, and returns false if
     * the satellite has no fixes yet. Range and range-rate are omitted because the sky view does
     * not draw them.
     *
     * This exists for one reason: the sky view calls it for every tracked satellite on every
     * frame. At a thousand satellites and thirty frames a second, returning an object here would
     * be thirty thousand short-lived allocations a second — individually trivial, collectively the
     * difference between a flat heap and a sawtooth one.
     */
    public fun interpolateInto(noradId: Int, time: Instant, out: DoubleArray): Boolean =
        sky.value.interpolateInto(noradId, time.toEpochMicroseconds(), out)

    /**
     * The live tier propagated to an arbitrary instant.
     *
     * For the sky view's orbit trails, which need where a satellite *was* and *will be* rather
     * than where it is, and for time travel. Genuine propagation rather than interpolation, so it
     * suspends and belongs nowhere near a frame loop — and it is deliberately limited to the live
     * tier, because a trail for a satellite nobody can see is work nobody asked for.
     *
     * Defaults to empty so a fake tracker need not implement it.
     */
    public suspend fun snapshotsAt(time: Instant): List<SatelliteSnapshot> = emptyList()
}

/** Epoch microseconds — the unit [LiveSky] interpolates in. */
internal fun Instant.toEpochMicroseconds(): Long = epochSeconds * 1_000_000L + nanosecondsOfSecond / 1_000L

/**
 * What the engine is doing, in numbers a user could be shown.
 *
 * Surfaced because the cost of a large selection has to be visible. An app that silently drops to
 * a third of the satellites it was asked for is worse than one that says how many it is keeping
 * live and lets the user raise the budget.
 */
public data class TrackingStatus(
    /** Objects in the current selection. */
    public val selected: Int = 0,
    /** Objects with a usable element set and an initialised propagator. */
    public val armed: Int = 0,
    /** Objects refreshed on the last tick: the live tier plus the slow rotation's slice. */
    public val refreshedPerTick: Int = 0,
    /** Objects above the horizon at the last tick. */
    public val up: Int = 0,
    /** True while element sets are still being loaded and propagators built. */
    public val preparing: Boolean = false,
    public val observerKnown: Boolean = false,
) {
    /** How far through arming the selection is; 1 when there is nothing left to do. */
    public val armedFraction: Float
        get() = if (selected <= 0) 1f else (armed.toFloat() / selected).coerceIn(0f, 1f)
}

/**
 * The app's live picture of the sky.
 *
 * One instance, shared by the AR view, the catalog rows and the radio's Doppler correction, so
 * that all three agree about where a satellite is — three independent propagators would drift
 * apart within a frame and the AR glyph would not line up with the frequency being tuned.
 *
 * ## Two rates, and then a third
 *
 * Propagation runs at **1 Hz**; the AR view reads [interpolateInto] at frame rate. A low-orbit
 * satellite moves at most about a degree per second across the sky, so linear interpolation
 * between one-second fixes is visually perfect, and running SGP4 at 60 Hz would burn battery to
 * compute numbers indistinguishable from the interpolated ones.
 *
 * That was enough while the tracked set was three dozen objects. Tracking the catalog needs a
 * third rate. Propagating thirty thousand orbits every second is roughly a second of CPU per
 * second of wall clock — the device would be pinned, hot, and still behind. So the selection is
 * split into two tiers, each tick:
 *
 * - the **live tier** — everything followed, everything above the horizon or about to be, and
 *   anything not yet propagated at all — refreshed every tick, capped by `TrackingBudget`;
 * - the **rotation** — everything else, refreshed round-robin, a slice per tick.
 *
 * A satellite is promoted out of the rotation the moment its rotation sample shows it climbing
 * toward the horizon, so nothing can rise unnoticed. And a satellite in the rotation is, by
 * definition, one that is below the horizon and behind the camera: it is drawn as a ghost or not
 * at all, and refreshing it a few seconds late is not observable. That is what makes "track
 * everything" a real feature rather than a checkbox that ruins the app.
 *
 * ## Why it stops
 *
 * [sky] is shared with `WhileSubscribed`, so with no collectors nothing propagates at all. A
 * satellite tracker left open in the background must not quietly run SGP4 forever.
 */
public class TrackingEngine(
    private val tleRepository: TleRepository,
    private val selectionRepository: TrackingSelectionRepository,
    private val locationProvider: LocationProvider,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val config: TrackingConfig = TrackingConfig(),
) : SatelliteTracker {

    private val logger = Logger.withTag("TrackingEngine")

    private val followed = MutableStateFlow<Set<Int>>(emptySet())
    override val followedIds: StateFlow<Set<Int>> get() = followed.asStateFlow()

    private val _status = MutableStateFlow(TrackingStatus())
    override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    override val observer: StateFlow<ObserverPosition> = locationProvider.position

    /**
     * Everything the tick loop touches, replaced wholesale when the selection changes.
     *
     * A single object rather than a set of parallel fields so that a swap is atomic from a
     * reader's point of view: the propagator array, the epoch array and the [LiveSky] are
     * index-aligned, and a reader that caught them half-swapped would index one selection's
     * positions with another's propagators.
     */
    @Volatile
    private var armed: ArmedSelection = ArmedSelection.EMPTY

    private var armingJob: Job? = null

    private var elementWatcher: Job? = null

    override val sky: StateFlow<LiveSky> = flow {
        var lastSelection: TrackingSelection? = null
        val selections = selectionRepository.selection().distinctUntilChanged()
        val collector = scope.launch(dispatchers.io) {
            selections.collect { selection ->
                if (selection == lastSelection) return@collect
                lastSelection = selection
                rearm(selection)
            }
        }

        // A refresh replaces element sets underneath us, and a propagator is built from the set it
        // saw. Without this the engine would keep integrating yesterday's orbits until the app was
        // restarted — accurate enough that nobody would notice, and wrong in exactly the way this
        // app exists to be right about. Freshness changes only when a fetch actually stores
        // something, so this fires roughly daily rather than on a timer.
        elementWatcher = scope.launch(dispatchers.io) {
            tleRepository.freshness().distinctUntilChanged().drop(1).collect { invalidateElements() }
        }

        try {
            while (true) {
                val position = locationProvider.position.value.geoPos
                if (position == null) {
                    // Nothing is knowable without an observer, and emitting stale positions from
                    // the last known location would be worse than emitting none.
                    _status.value = _status.value.copy(observerKnown = false, refreshedPerTick = 0)
                    emit(LiveSky.EMPTY)
                    delay(config.tickInterval)
                    continue
                }

                val current = armed
                if (current.sky.size == 0) {
                    _status.value = _status.value.copy(observerKnown = true, refreshedPerTick = 0)
                    emit(LiveSky.EMPTY)
                    delay(config.tickInterval)
                    continue
                }

                runCatching { tick(current, position) }
                    .onFailure { logger.w(it) { "tracking tick failed" } }

                emit(current.sky)
                delay(config.tickInterval)
            }
        } finally {
            collector.cancel()
            elementWatcher?.cancel()
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 2_000), LiveSky.EMPTY)

    /** Adds a satellite to the live tier regardless of whether it is above the horizon. */
    override fun follow(noradId: Int) {
        followed.value = followed.value + noradId
    }

    override fun unfollow(noradId: Int) {
        followed.value = followed.value - noradId
    }

    override fun isFollowed(noradId: Int): Boolean = noradId in followed.value

    override suspend fun requestFix() {
        locationProvider.requestFix()
    }

    override suspend fun snapshotsAt(time: Instant): List<SatelliteSnapshot> = at(time)

    /**
     * The live tier propagated to an arbitrary instant.
     *
     * Bounded to the live tier on purpose: this is called for trails and time travel, both of which
     * are about what the user can see, and running the whole selection through SGP4 for a scrub
     * gesture would freeze the gesture.
     */
    public suspend fun at(time: Instant, observerPosition: GeoPos? = observer.value.geoPos): List<SatelliteSnapshot> {
        val position = observerPosition ?: return emptyList()
        val current = armed
        if (current.sky.size == 0) return emptyList()

        val indices = current.liveTierIndices()
        if (indices.isEmpty()) return emptyList()

        return withContext(dispatchers.default) {
            val snapshots = ArrayList<SatelliteSnapshot>(indices.size)
            for (index in indices) {
                runCatching { current.propagators[index]?.snapshot(time, position) }
                    .getOrNull()
                    ?.let { snapshots += it }
            }
            snapshots
        }
    }

    /**
     * How far the phone is from pointing at a satellite, in degrees.
     *
     * Lives here rather than in the AR feature because it is the same question the pass
     * notifications and the radio's "point here" hint ask.
     */
    public fun separationDeg(
        noradId: Int,
        attitude: com.developerstring.satride.core.orbit.math.Quaternion,
        time: Instant,
    ): Double? {
        val out = DoubleArray(2)
        if (!sky.value.interpolateInto(noradId, time.toEpochMicroseconds(), out)) return null
        return AttitudeFrames.angularSeparationDeg(
            attitude,
            Topocentric(out[0], out[1], rangeKm = 0.0, rangeRateKmS = 0.0),
        )
    }

    // --- Arming ------------------------------------------------------------------------------

    /**
     * Rebuilds the propagator set for a new selection, progressively.
     *
     * Deliberately not atomic. Arming a full catalog means loading tens of thousands of element
     * sets and running SGP4's initialiser on each — seconds of work, and doing it before the first
     * tick would mean the sky went blank for those seconds every time the selection changed. So
     * the new [LiveSky] inherits every position it can from the old one, is published immediately,
     * and fills in as the chunks land. The user sees their existing satellites the whole time and
     * new ones appear as they arm.
     */
    private suspend fun rearm(selection: TrackingSelection) {
        armingJob?.cancel()
        val previous = armed

        val ids = selection.noradIds
        val sky = LiveSky(ids)
        sky.inherit(previous.sky)

        val propagators = arrayOfNulls<OrbitingSatellite>(ids.size)
        val epochMicros = LongArray(ids.size)

        // Carry over every propagator the new selection still wants. Rebuilding SGP4 for a
        // satellite that was already armed is the single most expensive thing this class does, and
        // adding one satellite to a selection must not pay for the other twenty thousand.
        var from = 0
        var into = 0
        var inherited = 0
        while (from < previous.sky.size && into < ids.size) {
            val previousId = previous.sky.ids[from]
            val id = ids[into]
            when {
                previousId < id -> from++

                previousId > id -> into++

                else -> {
                    previous.propagators[from]?.let {
                        propagators[into] = it
                        epochMicros[into] = previous.epochMicros[from]
                        inherited++
                    }
                    from++
                    into++
                }
            }
        }

        val next = ArmedSelection(sky, propagators, epochMicros, selection.budget)
        armed = next
        _status.value = _status.value.copy(
            selected = ids.size,
            armed = inherited,
            preparing = inherited < ids.size,
        )

        if (inherited >= ids.size) return

        armingJob = scope.launch(dispatchers.io) {
            var armedCount = inherited
            val missing = ArrayList<Int>(config.armChunk)
            var index = 0
            while (index < ids.size) {
                missing.clear()
                var scanned = 0
                while (index < ids.size && scanned < config.armChunk) {
                    if (propagators[index] == null) missing += ids[index]
                    index++
                    scanned++
                }
                if (missing.isEmpty()) continue

                val elements = runCatching { tleRepository.tlesFor(missing) }
                    .onFailure { logger.w(it) { "loading element sets failed" } }
                    .getOrDefault(emptyMap())

                withContext(dispatchers.default) {
                    elements.forEach { (noradId, tle) ->
                        val slot = next.sky.indexOf(noradId)
                        if (slot < 0) return@forEach
                        buildPropagator(tle)?.let {
                            propagators[slot] = it
                            epochMicros[slot] = tle.epoch.toEpochMicroseconds()
                            armedCount++
                        }
                    }
                }

                _status.value = _status.value.copy(
                    armed = armedCount,
                    preparing = index < ids.size,
                )
                // Hand the thread back between chunks. Arming a full catalog is seconds of work
                // and it must not be seconds during which the tick loop cannot run.
                yield()
            }
            _status.value = _status.value.copy(armed = armedCount, preparing = false)
        }
    }

    private fun buildPropagator(tle: Tle): OrbitingSatellite? = runCatching { Orbit.satellite(tle) }
        .onFailure { logger.w(it) { "cannot build a propagator for ${tle.noradId}" } }
        .getOrNull()

    /**
     * Rebuilds propagators whose element sets have been replaced by a catalog refresh.
     *
     * Called on the sweep interval rather than per tick: element sets change roughly daily, and
     * checking thirty thousand epochs against the database every second would be the most
     * expensive thing in the app by a wide margin.
     */
    public fun invalidateElements() {
        val current = armed
        if (current.sky.size == 0) return
        scope.launch(dispatchers.io) {
            current.sky.ids.toList().chunked(config.armChunk).forEach { chunk ->
                val elements = runCatching { tleRepository.tlesFor(chunk) }.getOrDefault(emptyMap())
                withContext(dispatchers.default) {
                    elements.forEach { (noradId, tle) ->
                        val slot = current.sky.indexOf(noradId)
                        if (slot < 0) return@forEach
                        val epoch = tle.epoch.toEpochMicroseconds()
                        if (current.epochMicros[slot] == epoch) return@forEach
                        buildPropagator(tle)?.let {
                            current.propagators[slot] = it
                            current.epochMicros[slot] = epoch
                        }
                    }
                }
                yield()
            }
        }
    }

    // --- The tick ----------------------------------------------------------------------------

    private suspend fun tick(current: ArmedSelection, position: GeoPos) {
        val now = clock.now()
        val nowMicros = now.toEpochMicroseconds()
        val sky = current.sky
        val version = sky.version + 1

        val count = buildWorkList(current, version)
        if (count == 0) {
            sky.version = version
            sky.time = now
            sky.refreshedCount = 0
            return
        }

        propagate(current, position, now, nowMicros, count)

        sky.version = version
        sky.time = now
        sky.refreshedCount = count

        _status.value = _status.value.copy(
            refreshedPerTick = count,
            up = sky.countUp(),
            observerKnown = true,
        )
    }

    /**
     * Chooses what to propagate this tick: the live tier first, then a slice of the rotation.
     *
     * A few linear scans over the selection, which for a full catalog is a few tens of thousands of
     * array reads — tens of microseconds, and nothing allocated. The expensive part of a tick is
     * SGP4, and the point of this function is to make sure the expensive part is spent on
     * satellites somebody could actually see.
     *
     * ## The order of the passes is the design
     *
     * 1. **Followed**, unconditionally. Following something means its numbers keep ticking whether
     *    it is up, down, or on the far side of the Earth.
     * 2. **Never propagated**, so nothing waits on the rotation for its very first position.
     * 3. **Above the horizon**, highest priority among the rest.
     * 4. **Within the horizon margin** — a few degrees below, which at low-orbit rates is about a
     *    minute of warning, so nothing pops into existence already ten degrees up.
     * 5. **The rotation**: everything else, a slice per tick, resuming where the last tick left off.
     *
     * Three and four are separate passes rather than one because with a full catalog the live
     * budget genuinely binds: four hundred-odd objects are above the horizon at any moment, which
     * is the same order as the budget itself. A single pass in catalog order would fill the tier
     * with whatever happened to have low catalog numbers and demote satellites that are actually
     * overhead into the slow rotation. Split, an overflowing budget costs warning time on something
     * about to rise rather than fidelity on something the user is looking at.
     */
    private fun buildWorkList(current: ArmedSelection, version: Long): Int {
        val sky = current.sky
        val size = sky.size
        val budget = current.budget
        val workList = current.workList
        val touched = current.touchedAtVersion
        val warmingUp = current.ticks < config.warmupTicks
        var count = 0

        // 1. Followed satellites.
        for (noradId in followed.value) {
            val index = sky.indexOf(noradId)
            if (index < 0 || current.propagators[index] == null) continue
            if (touched[index] == version) continue
            touched[index] = version
            workList[count++] = index
        }

        val liveCap = if (warmingUp) size else budget.liveObjects
        var live = count

        // 2, 3, 4: the live tier, in priority order.
        for (pass in LiveTier.entries) {
            for (index in 0 until size) {
                if (live >= liveCap) break
                if (touched[index] == version) continue
                if (current.propagators[index] == null) continue
                if (!pass.accepts(sky, index, config.horizonMarginDeg)) continue
                touched[index] = version
                workList[count++] = index
                live++
            }
            if (live >= liveCap) break
        }

        // 5. The rotation, so every satellite in the selection is refreshed within
        //    `size / rotationObjects` seconds however large the selection is. The first ticks after
        //    arming take everything instead; see `ArmedSelection.ticks`.
        var rotated = 0
        val rotationCap = if (warmingUp) size else minOf(budget.rotationObjects, size)
        var scanned = 0
        while (rotated < rotationCap && scanned < size && count < workList.size) {
            val index = current.rotationCursor
            current.rotationCursor = if (index + 1 >= size) 0 else index + 1
            scanned++
            if (touched[index] == version) continue
            if (current.propagators[index] == null) continue
            touched[index] = version
            workList[count++] = index
            rotated++
        }

        current.ticks++
        return count
    }

    /** The live tier's priority bands, in the order they are filled. */
    private enum class LiveTier {
        /** No position at all yet — nothing should wait on the rotation for its first fix. */
        UNSAMPLED {
            override fun accepts(sky: LiveSky, index: Int, marginDeg: Double): Boolean = sky.samples[index].toInt() == 0
        },

        /** Above the horizon: what the user can actually see. */
        UP {
            override fun accepts(sky: LiveSky, index: Int, marginDeg: Double): Boolean =
                sky.samples[index] > 0 && sky.elevationDeg[index] > 0.0
        },

        /** Just below it, so a rise is never a surprise. */
        RISING {
            override fun accepts(sky: LiveSky, index: Int, marginDeg: Double): Boolean =
                sky.samples[index] > 0 && sky.elevationDeg[index] >= -marginDeg
        },
        ;

        abstract fun accepts(sky: LiveSky, index: Int, marginDeg: Double): Boolean
    }

    /**
     * Runs SGP4 for this tick's work list, across every core.
     *
     * Partitioned by stride rather than by block so the shards finish together: a block partition
     * hands one worker the live tier — the satellites that are up, which cost marginally more to
     * turn into topocentric coordinates — and another the rotation, and the tick then takes as
     * long as the slowest shard.
     */
    private suspend fun propagate(
        current: ArmedSelection,
        position: GeoPos,
        now: Instant,
        nowMicros: Long,
        count: Int,
    ) {
        val workers = if (count < config.parallelThreshold) 1 else config.workers
        if (workers == 1) {
            withContext(dispatchers.default) {
                propagateStride(current, position, now, nowMicros, count, offset = 0, stride = 1)
            }
            return
        }

        withContext(dispatchers.default) {
            coroutineScope {
                (0 until workers).map { worker ->
                    async {
                        propagateStride(current, position, now, nowMicros, count, worker, workers)
                    }
                }.awaitAll()
            }
        }
    }

    private fun propagateStride(
        current: ArmedSelection,
        position: GeoPos,
        now: Instant,
        nowMicros: Long,
        count: Int,
        offset: Int,
        stride: Int,
    ) {
        var cursor = offset
        while (cursor < count) {
            val index = current.workList[cursor]
            val propagator = current.propagators[index]
            if (propagator != null) {
                // A decayed or corrupt element set throws from deep inside SGP4. One satellite's
                // bad elements must not stop the other twenty thousand, and the failure is silent
                // by design: it would otherwise log once a second, forever, for an object the user
                // has never heard of.
                val snapshot = runCatching { propagator.snapshot(now, position) }.getOrNull()
                if (snapshot != null) current.sky.write(index, snapshot, nowMicros)
            }
            cursor += stride
        }
    }

    /**
     * The tracked set and everything index-aligned with it.
     *
     * Swapped as one reference so a reader can never see a propagator array from one selection
     * beside a [LiveSky] from another.
     */
    private class ArmedSelection(
        val sky: LiveSky,
        val propagators: Array<OrbitingSatellite?>,
        val epochMicros: LongArray,
        val budget: com.developerstring.satride.core.datastore.TrackingBudget,
    ) {
        /**
         * Scratch for a tick's work list, and the rotation's place in the selection.
         *
         * They live here rather than on the engine so that swapping the selection swaps them with
         * it. Held separately, a tick that had already read the old selection could index the old
         * work list against a new, shorter one — an out-of-bounds crash that would only ever
         * happen while the user was changing what they track, which is to say almost never in
         * testing and reliably in the field.
         */
        val workList: IntArray = IntArray(sky.size)

        /** Version stamp per index, so "already in this tick's work list" costs no clearing pass. */
        val touchedAtVersion: LongArray = LongArray(sky.size)

        var rotationCursor: Int = 0

        /**
         * Ticks since this selection was armed.
         *
         * The first two ignore the budget and propagate everything. A satellite needs *two*
         * samples before it can be interpolated — one gives a position but no rate, so it holds
         * still — and with a large selection the rotation takes tens of seconds to reach the far
         * end. Without this, arming the full catalog left most of the sky frozen for a rotation
         * and then twitching into motion, which is exactly what the two-rate design exists to
         * avoid. Two extra full sweeps cost a fraction of a second, once.
         */
        var ticks: Int = 0

        /** Indices currently worth propagating on demand: up, or close to it. */
        fun liveTierIndices(): IntArray {
            val found = ArrayList<Int>(64)
            for (index in 0 until sky.size) {
                if (propagators[index] == null) continue
                if (sky.samples[index].toInt() == 0) continue
                if (sky.elevationDeg[index] < -20.0) continue
                found += index
            }
            return found.toIntArray()
        }

        companion object {
            val EMPTY = ArmedSelection(
                sky = LiveSky.EMPTY,
                propagators = arrayOfNulls(0),
                epochMicros = LongArray(0),
                budget = com.developerstring.satride.core.datastore.TrackingBudget.default,
            )
        }
    }
}

/** Tuning for [TrackingEngine]. */
public data class TrackingConfig(
    /** How often the live tier is propagated. */
    public val tickInterval: Duration = 1.seconds,

    /**
     * How far below the horizon a satellite stays in the live tier.
     *
     * A satellite in low orbit climbs roughly a degree a second near the horizon, so fifteen
     * degrees is about fifteen seconds of warning — enough that nothing ever appears already high,
     * and small enough that the live tier is not mostly satellites nobody can see.
     */
    public val horizonMarginDeg: Double = 15.0,

    /** How many element sets are loaded and armed per batch while a selection is being prepared. */
    public val armChunk: Int = 1_000,

    /** Below this many satellites a tick is cheaper on one thread than shared across several. */
    public val parallelThreshold: Int = 96,

    /**
     * How many coroutines share a tick.
     *
     * Four rather than the core count. SGP4 is pure arithmetic and scales nearly linearly, but the
     * device is simultaneously drawing the sky at thirty frames a second — saturating every core
     * with propagation would win the tick and lose the frame.
     */
    public val workers: Int = 4,

    /**
     * How many ticks after arming propagate the whole selection regardless of budget.
     *
     * See `ArmedSelection.ticks`: two, because interpolation needs two samples and the rotation
     * would otherwise take tens of seconds to give the far end of a large selection its second.
     */
    public val warmupTicks: Int = 2,
)

/**
 * The two most recent fixes for one satellite.
 *
 * Retained for its constants and its documentation; the storage itself now lives column-wise in
 * [LiveSky], which is what allows the same interpolation to run over a full catalog without
 * allocating a pair of objects per satellite per second.
 */
internal object FixPair {
    /**
     * How far past the newest fix prediction is allowed to run, in multiples of the gap between
     * fixes.
     *
     * ## Why interpolation extrapolates at all
     *
     * The render loop always asks for *now*, and now is always later than the newest fix — the
     * next tick has not happened yet. Clamping at the newest fix therefore returns the same value
     * for a whole second and then jumps, which is precisely the stutter the two-rate design exists
     * to avoid. So the line is followed past its end.
     *
     * That is safe because the motion is nearly straight over these intervals: a low-orbit
     * satellite sweeps at most about a degree per second, smoothly, so a second of linear
     * prediction is wrong by a fraction of a degree — far inside the several degrees of compass
     * error the AR view already carries, and far better than a visible jump.
     *
     * One span covers normal operation with margin: a frame can only ever be up to one tick ahead
     * of the last fix. More than that means ticks have stopped arriving, and holding still is
     * honest where flinging a glyph across the sky is not.
     *
     * Azimuth is interpolated through the *shortest* arc, not numerically. A satellite passing
     * north goes 359° → 1°, and the naive path runs backwards through 180° — due south, the one
     * direction it certainly is not. That would last a single frame, which is long enough to look
     * like a glitch and short enough never to be reproducible.
     */
    const val MAX_EXTRAPOLATION_SPANS: Double = 1.0
}
