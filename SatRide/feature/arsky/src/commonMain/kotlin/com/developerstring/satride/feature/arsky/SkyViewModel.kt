package com.developerstring.satride.feature.arsky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.SkyGuideRequests
import com.developerstring.satride.core.common.runCatchingCancellable
import com.developerstring.satride.core.data.LiveSky
import com.developerstring.satride.core.data.SatelliteRepository
import com.developerstring.satride.core.data.SatelliteTracker
import com.developerstring.satride.core.data.TransmitterRepository
import com.developerstring.satride.core.designsystem.component.SatelliteGlyphKind
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Satellite
import com.developerstring.satride.core.orbit.math.Quaternion
import com.developerstring.satride.core.permissions.PermissionStatus
import com.developerstring.satride.core.sensors.AttitudeProvider
import com.developerstring.satride.core.sensors.Haptics
import com.developerstring.satride.feature.arsky.guide.GuideFeedback
import com.developerstring.satride.feature.arsky.render.SkyPositionSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The sky view.
 *
 * Follows the pattern `SatellitesViewModel` set in P4 — one state flow, one `onEvent`, one effects
 * channel — with one deliberate exception, which is the whole architecture of this screen: **live
 * positions do not go through the state**.
 *
 * A satellite moves continuously and the renderer draws at thirty hertz. Emitting positions
 * through `StateFlow` would recompose the screen thirty times a second to move a handful of dots,
 * which is exactly the cost the two-rate tracking design exists to avoid. So the state carries
 * only what changes at human speed — which satellites exist, what they are called, whether one is
 * selected — and the renderer reads positions itself through [positions], at frame rate,
 * allocating nothing.
 */
internal class SkyViewModel(
    private val satelliteRepository: SatelliteRepository,
    transmitterRepository: TransmitterRepository,
    private val tracker: SatelliteTracker,
    attitudeProvider: AttitudeProvider,
    private val haptics: Haptics,
    private val guideRequests: SkyGuideRequests,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    private val timeTravelEnabled: Boolean = false,
) : ViewModel() {

    private val mode = MutableStateFlow(SkyMode.PLANETARIUM)
    private val cameraPermission = MutableStateFlow(PermissionStatus.NOT_REQUESTED)
    private val locationPermission = MutableStateFlow(PermissionStatus.NOT_REQUESTED)
    private val selectedId = MutableStateFlow<Int?>(null)
    private val guideTargetId = MutableStateFlow<Int?>(null)
    private val calibrationOpen = MutableStateFlow(false)
    private val timeTravel = MutableStateFlow(
        if (timeTravelEnabled) TimeTravelUi.LIVE else TimeTravelUi.DISABLED,
    )
    private val trails = MutableStateFlow<Map<Int, SkyTrail>>(emptyMap())

    /**
     * Whether the user wants the camera feed behind the augmented sky.
     *
     * Separate from [mode], because the augmented view is useful without it: pointing the phone at
     * the sky and seeing satellites over a dark background works indoors, on a simulator, and with
     * the camera denied. Starts true so that granting the camera shows it without a second tap.
     */
    private val cameraPreferred = MutableStateFlow(true)

    /**
     * Whether the sky is currently composed. Set by `SkyScreen`; see [refreshTrailsPeriodically]
     * for why a view model that outlives its screen has to know.
     */
    private val visible = MutableStateFlow(false)

    private val effectChannel = Channel<SkyEffect>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: Flow<SkyEffect> = effectChannel.receiveAsFlow()

    private val guide = GuideFeedback()

    /**
     * Highest guide-request sequence this view model has already acted on.
     *
     * Nothing clears a request from the bus — see [SkyGuideRequests] — because the app shell has to
     * see it too, and consuming raced the shell and lost. Remembering what has been handled makes
     * acting on it idempotent instead.
     */
    private var lastHandledGuideSequence = 0L

    /**
     * Device orientation, as a cold flow rather than state.
     *
     * Handed to the screen to collect into a `MutableState` that only the draw phase reads. It
     * deliberately does not live in [state]: at fifty samples a second it would make the whole
     * screen recompose faster than it redraws.
     */
    val attitude: Flow<Quaternion> = attitudeProvider.attitude.map { it.rotation }

    /** Which satellites carry a receivable downlink, so the sky can show the radio link. */
    private val downlinkIds = transmitterRepository.observeAll()
        .map { transmitters -> transmitters.map { it.noradId }.toSet() }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptySet())

    /**
     * Names and classes for whatever the sky ends up drawing, as a lookup.
     *
     * The whole catalog rather than the starter set, because the tracked selection can now be the
     * whole catalog — a sky drawing thirty thousand objects with names for forty of them would be
     * worse than useless. Indexed once here rather than per emission: this re-emits only when the
     * satellites table changes, which is roughly daily, while the sky rebuilds its rows every
     * second, and `associateBy` over thirty thousand rows once a second is precisely the kind of
     * quiet cost that turns into a dropped frame.
     */
    private val catalog = satelliteRepository.observeAll()
        .map { list -> list.associateBy { it.noradId } }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyMap())

    val state: StateFlow<SkyUiState> = combine(
        combine(tracker.sky, catalog, downlinkIds, trails, selectedId, ::Renderable),
        combine(
            combine(mode, cameraPreferred, cameraPermission, ::CameraControls),
            locationPermission,
            calibrationOpen,
            timeTravel,
            ::Controls,
        ),
        combine(
            attitudeProvider.accuracy,
            tracker.observer,
            selectedId,
            guideTargetId,
            ::Context,
        ),
    ) { renderable, controls, context ->
        SkyUiState(
            satellites = renderable.toUi(context.guideTargetId),
            mode = controls.camera.mode,
            cameraOn = controls.camera.feedOn,
            cameraPermission = controls.camera.permission,
            locationPermission = controls.location,
            accuracy = context.accuracy,
            attitudeAvailable = attitudeProvider.isAvailable,
            observerKnown = context.observerKnown,
            guideTargetId = context.guideTargetId,
            selectedId = context.selectedId,
            calibrationCardOpen = controls.calibrationOpen,
            timeTravel = controls.timeTravel,
        )
    }
        // `renderable.toUi(...)` rebuilds a UI row per tracked satellite on every tracking
        // snapshot. `stateIn(viewModelScope, …)` would run that on `Dispatchers.Main.immediate`,
        // where it competes with the very frames the sky is trying to draw.
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), SkyUiState())

    // Deliberately the last thing in the class body.
    //
    // Kotlin runs initialisers in declaration order, and `viewModelScope.launch` on
    // `Dispatchers.Main.immediate` starts running *synchronously* when it is already on the main
    // thread. A pending guide request therefore reaches `onEvent` during construction — and a cold
    // start from a notification always has one. Placed higher up, that call touched `guide` and
    // `trails` before their initialisers had run, and the app died on launch with a
    // `NullPointerException` on a field that is declared non-null. Found by opening a deep link on
    // a device; no unit test then existing could have caught it, because they construct the view
    // model with an empty bus.
    init {
        refreshTrailsPeriodically()

        // A pass notification, a `satride://` link and the pass detail screen's [Open in Sky] all
        // arrive here, as a request left on an application-scoped bus. It is a bus rather than a
        // navigation argument because two of the three are delivered before this screen exists —
        // the request has to wait until something is there to act on it.
        viewModelScope.launch {
            guideRequests.pending.collect { request ->
                if (request == null || request.sequence <= lastHandledGuideSequence) return@collect
                lastHandledGuideSequence = request.sequence
                if (request.guide) {
                    onEvent(SkyEvent.GuideRequested(request.noradId))
                } else {
                    onEvent(SkyEvent.SatelliteTapped(request.noradId))
                }
            }
        }
    }

    /**
     * The renderer's read path: current positions, at frame rate, without allocating.
     *
     * The clock is read once per frame in [SkyPositionSource.beginFrame] rather than once per
     * satellite, because `Instant` is an object and fifty of them a frame is fifteen hundred a
     * second for no benefit — every satellite in a frame should be drawn at the *same* instant
     * anyway.
     */
    val positions: SkyPositionSource = object : SkyPositionSource {
        private var frameTime: Instant = clock.now()

        override fun beginFrame() {
            frameTime = clock.now() + timeTravel.value.offset
        }

        override fun readInto(noradId: Int, out: DoubleArray): Boolean =
            tracker.interpolateInto(noradId, frameTime, out)
    }

    fun onVisibilityChanged(onScreen: Boolean) {
        visible.value = onScreen
    }

    fun onEvent(event: SkyEvent) {
        when (event) {
            SkyEvent.ModeToggled -> toggleMode()

            SkyEvent.CameraToggled -> toggleCamera()

            is SkyEvent.SatelliteTapped -> {
                selectedId.value = event.noradId
                // Trails are only kept for satellites above the horizon, so selecting one below it
                // would otherwise dim the sky and animate an arc that does not exist — for up to a
                // whole refresh interval. Rebuild now that the selection has changed what matters.
                refreshTrails()
            }

            SkyEvent.SelectionDismissed -> selectedId.value = null

            is SkyEvent.GuideRequested -> {
                guide.reset()
                guideTargetId.value = event.noradId
                // Following keeps it propagated even once it sets, so the guide arrow can still
                // point at something that has gone below the horizon.
                tracker.follow(event.noradId)
                selectedId.value = null
                refreshTrails()
            }

            SkyEvent.GuideCleared -> {
                guide.reset()
                guideTargetId.value = null
                // The request that started this guide is never cleared from the bus, so it has to
                // be marked handled here too — otherwise a view model recreated after this screen
                // left composition would read it again and undo the user's [Stop].
                guideRequests.pending.value?.let { lastHandledGuideSequence = it.sequence }
            }

            SkyEvent.CalibrationOpened -> calibrationOpen.value = true

            SkyEvent.CalibrationDismissed -> calibrationOpen.value = false

            SkyEvent.SearchRequested -> effectChannel.trySend(SkyEffect.OpenSearch)

            is SkyEvent.TimeScrubbed -> {
                if (!timeTravelEnabled) return
                timeTravel.value = timeTravel.value.copy(
                    offset = event.offset.coerceIn(-MAX_TIME_TRAVEL, MAX_TIME_TRAVEL),
                    scrubbing = true,
                )
            }

            SkyEvent.TimeScrubEnded -> {
                timeTravel.value = timeTravel.value.copy(scrubbing = false)
                refreshTrails()
            }

            SkyEvent.TimeReset -> {
                timeTravel.value = timeTravel.value.copy(offset = Duration.ZERO, scrubbing = false)
                refreshTrails()
            }
        }
    }

    /** Reports a permission the composition owns, so the state can reflect it. */
    fun onCameraPermission(status: PermissionStatus) {
        val wasGranted = cameraPermission.value.isGranted
        cameraPermission.value = status
        // Only on the *transition* into granted, never on every report. The composition re-reports
        // the same status every time the tab is opened, and acting on that turned the camera back
        // on for a user who had deliberately switched it off, with nothing on screen to say why.
        if (status.isGranted && !wasGranted) {
            cameraPreferred.value = true
            mode.value = SkyMode.AUGMENTED
        }
    }

    fun onLocationPermission(status: PermissionStatus) {
        val wasGranted = locationPermission.value.isGranted
        locationPermission.value = status
        // The provider retries on its own, but it has just backed off — and this is the exact
        // moment the user is looking at the screen having granted permission. Asking immediately is
        // the difference between the sky filling in at once and filling in a few seconds later for
        // no reason the user can see.
        if (status.isGranted && !wasGranted) {
            viewModelScope.launch { tracker.requestFix() }
        }
    }

    /**
     * Called when the camera reports it cannot show a preview after all.
     *
     * Only the feed is switched off; the augmented view stays. The device still knows which way it
     * is pointing, which is the part that makes the mode worth being in.
     */
    fun onCameraUnavailable() {
        cameraPreferred.value = false
    }

    /**
     * Advances the guide metronome and plays whatever it asks for.
     *
     * Driven from the screen's frame loop rather than from a flow: the decision depends on the
     * live pointing error, which changes every frame and never goes through the state.
     */
    fun onGuideFrame(errorDeg: Double, nowNanos: Long) {
        if (!haptics.isAvailable) return
        when (guide.onFrame(errorDeg, nowNanos)) {
            GuideFeedback.Pulse.TICK -> haptics.tick()
            GuideFeedback.Pulse.LOCK -> haptics.lock()
            GuideFeedback.Pulse.NONE -> Unit
        }
    }

    /**
     * Switches between the drawn sky and the augmented one.
     *
     * No permission is involved. The augmented view is device orientation, not a camera feed, and
     * it is fully usable without one — refusing to enter AR because the camera was denied made a
     * working mode unreachable for the sake of an optional backdrop.
     */
    private fun toggleMode() {
        mode.value = when (mode.value) {
            SkyMode.AUGMENTED -> SkyMode.PLANETARIUM
            SkyMode.PLANETARIUM -> SkyMode.AUGMENTED
        }
    }

    /**
     * Switches the camera feed behind the augmented sky.
     *
     * Asking for the camera without permission raises [SkyEffect.RequestCameraPermission] rather
     * than refusing with a message: the preference is recorded either way, so granting it shows the
     * feed immediately, and declining leaves the augmented sky drawn on its own — which still works.
     */
    private fun toggleCamera() {
        val wanted = !cameraPreferred.value
        cameraPreferred.value = wanted
        if (!wanted) return

        if (!cameraPermission.value.isGranted) {
            if (cameraPermission.value.canRequest) {
                effectChannel.trySend(SkyEffect.RequestCameraPermission)
            } else {
                effectChannel.trySend(
                    SkyEffect.ShowMessage("Camera access is blocked in system settings; the sky stays drawn."),
                )
            }
        }
    }

    /**
     * Recomputes orbit trails on a slow timer.
     *
     * Trails are genuine propagation — twenty-one SGP4 runs per satellite — so they are rebuilt
     * every fifteen seconds rather than every second. A LEO satellite covers about fifteen degrees
     * in that time, which is a small fraction of an arc that spans ten minutes, so the trail is
     * never visibly stale even though it is far from current.
     *
     * ## Off the main thread, and only while the sky is on screen
     *
     * Two things were wrong with the original. It ran on `viewModelScope`'s dispatcher, which is
     * `Dispatchers.Main.immediate`, so a full-catalog SGP4 propagation — [TRAIL_SAMPLES] of them,
     * back to back — happened *on the UI thread* every fifteen seconds. And it never stopped: this
     * view model survives tab switches, so it went on doing that behind every other tab for the
     * rest of the session. That is a periodic main-thread stall with no visible cause, which is
     * exactly what "the app randomly hitches" looks like from the outside.
     */
    private fun refreshTrailsPeriodically() {
        viewModelScope.launch(dispatchers.default) {
            while (true) {
                visible.first { it }
                refreshTrailsNow()
                delay(TRAIL_REFRESH)
            }
        }
    }

    private fun refreshTrails() {
        viewModelScope.launch(dispatchers.default) { refreshTrailsNow() }
    }

    private suspend fun refreshTrailsNow() {
        val now = clock.now() + timeTravel.value.offset

        // Trails only for what is actually up, plus whatever the user is being guided to.
        //
        // The tracker propagates the whole catalog so that below-horizon satellites can be drawn
        // as ghosts, but an arc for something on the far side of the Earth is both meaningless and
        // expensive: a few hundred trails at twenty segments each is thousands of draw calls a
        // frame, which is the one thing that would actually break the frame budget.
        val worthTrailing = tracker.sky.value.upNow(limit = MAX_TRAILS).toSet() +
            setOfNotNull(guideTargetId.value, selectedId.value)

        val built = runCatchingCancellable {
            // Azimuth and elevation per satellite, one sample at a time, transposed at the end.
            val samples = mutableMapOf<Int, Pair<MutableList<Float>, MutableList<Float>>>()
            for (step in 0 until TRAIL_SAMPLES) {
                val offset = TRAIL_SPAN * (step.toDouble() / (TRAIL_SAMPLES - 1) - 0.5) * 2.0
                tracker.snapshotsAt(now + offset).forEach { snapshot ->
                    if (snapshot.noradId !in worthTrailing) return@forEach
                    val lists = samples.getOrPut(snapshot.noradId) {
                        ArrayList<Float>(TRAIL_SAMPLES) to ArrayList(TRAIL_SAMPLES)
                    }
                    lists.first += snapshot.topo.azimuthDeg.toFloat()
                    lists.second += snapshot.topo.elevationDeg.toFloat()
                }
            }
            samples.mapValues { (_, lists) ->
                SkyTrail(lists.first.toFloatArray(), lists.second.toFloatArray())
            }
        }.getOrNull() ?: return

        trails.value = built
    }

    /**
     * Everything the sky draws, and the one place the tracked set is turned into UI rows.
     *
     * ## Why this is capped
     *
     * The tracked set can now be the entire catalog. Building a row per tracked satellite once a
     * second would allocate thirty thousand objects a second and sort them — for a screen that can
     * legibly show a few hundred glyphs and a dozen labels. So [toUi] takes the satellites worth
     * drawing and stops: everything above the horizon, the ones being guided to or selected, and
     * then the nearest ghosts below the horizon up to [MAX_DRAWN].
     *
     * Nothing visible is lost by that. Ghosts exist so that pointing the phone at the ground shows
     * what is coming round, and the ones worth seeing are the ones closest to rising — which is
     * exactly the order this keeps.
     */
    private data class Renderable(
        val sky: LiveSky,
        val catalog: Map<Int, Satellite>,
        val downlinkIds: Set<Int>,
        val trails: Map<Int, SkyTrail>,
        val selectedId: Int?,
    ) {
        /**
         * The drawable set as rows, **already in label-priority order**.
         *
         * Sorted here rather than in the renderer because the renderer runs thirty times a second
         * and this runs once a second: sorting per frame would allocate a comparator and a list
         * every frame for an order that barely ever changes.
         */
        fun toUi(guideTargetId: Int?): List<SkySatelliteUi> {
            if (sky.size == 0 || catalog.isEmpty()) return emptyList()

            // One pass over the column-wise table, collecting indices and their scores. Indices
            // rather than objects, so nothing is allocated for a satellite that does not make the
            // cut — which, with the whole catalog tracked, is most of them.
            val scored = ArrayList<Long>(minOf(sky.size, MAX_DRAWN * 2))
            for (index in 0 until sky.size) {
                if (!sky.hasPositionAt(index)) continue
                val elevation = sky.elevationDegAt(index)
                val id = sky.idAt(index)
                val pinned = id == guideTargetId || id == selectedId
                if (!pinned && elevation < GHOST_FLOOR_DEG) continue
                // Packed as (score << 32) | index so the sort is over primitives: sorting boxed
                // pairs is the single largest allocation this method could make.
                val score = ((elevation + 90.0) * 10.0).toInt() +
                    (if (pinned) PINNED_BONUS else 0) +
                    (if (id in downlinkIds) DOWNLINK_BONUS else 0)
                scored += (score.toLong() shl 32) or (index.toLong() and 0xFFFFFFFFL)
            }
            scored.sortDescending()

            val rows = ArrayList<SkySatelliteUi>(minOf(scored.size, MAX_DRAWN))
            for (packed in scored) {
                if (rows.size >= MAX_DRAWN) break
                val index = (packed and 0xFFFFFFFFL).toInt()
                val id = sky.idAt(index)
                val satellite = catalog[id] ?: continue
                rows += SkySatelliteUi(
                    noradId = id,
                    name = satellite.name,
                    glyph = satellite.classType.toGlyph(),
                    hasDownlink = id in downlinkIds,
                    followed = id == guideTargetId,
                    sunlit = sky.isSunlitAt(index),
                    trail = trails[id],
                )
            }
            return rows
        }

        private companion object {
            /**
             * How many glyphs reach the renderer.
             *
             * Eight hundred is far more than can be told apart on a phone screen and still cheap
             * to project — the renderer's per-glyph cost is a quaternion rotation and a compare,
             * and it culls anything behind the camera before drawing. The cap exists to bound the
             * *allocation* here, not the drawing.
             */
            const val MAX_DRAWN = 800

            /** How far below the horizon a ghost is still worth drawing. */
            const val GHOST_FLOOR_DEG = -35.0

            /** Enough to outrank any elevation, so a guide target is never culled. */
            const val PINNED_BONUS = 100_000

            /** A satellite you can hear outranks one you can only look at, at equal elevation. */
            const val DOWNLINK_BONUS = 200
        }
    }

    private data class CameraControls(val mode: SkyMode, val preferred: Boolean, val permission: PermissionStatus) {
        /** The camera is only actually shown when all three agree. */
        val feedOn: Boolean get() = mode == SkyMode.AUGMENTED && preferred && permission.isGranted
    }

    private data class Controls(
        val camera: CameraControls,
        val location: PermissionStatus,
        val calibrationOpen: Boolean,
        val timeTravel: TimeTravelUi,
    )

    private data class Context(
        val accuracy: com.developerstring.satride.core.sensors.CompassAccuracy,
        val observer: com.developerstring.satride.core.model.ObserverPosition,
        val selectedId: Int?,
        val guideTargetId: Int?,
    ) {
        val observerKnown: Boolean get() = observer.geoPos != null
    }

    private companion object {
        /**
         * How many satellites get an orbit trail.
         *
         * A trail is twenty propagations and twenty line segments, so this is the one part of the
         * sky view whose cost is genuinely per-satellite. Sixty covers a busy sky comfortably —
         * more objects are above the horizon than that only when the whole catalog is tracked, and
         * at that density the trails would be an unreadable thicket anyway.
         */
        const val MAX_TRAILS = 60

        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

        /** How often orbit trails are rebuilt. */
        val TRAIL_REFRESH = 15.seconds

        /** How far either side of the present a trail reaches. */
        val TRAIL_SPAN = 5.minutes

        /**
         * Samples per trail.
         *
         * Twenty-one over ten minutes is one every thirty seconds. A LEO satellite turns about
         * thirty degrees between samples at its fastest, and the straight line between two points
         * of a great circle thirty degrees apart is visibly a chord rather than an arc — but at
         * the scale a trail is drawn, a few pixels from the true curve, which is well under the
         * pointing error the whole overlay carries.
         */
        const val TRAIL_SAMPLES = 21

        val MAX_TIME_TRAVEL = (24 * 60).minutes
    }
}

/** The catalog's class, as the shape the sky draws for it. */
internal fun SatClass.toGlyph(): SatelliteGlyphKind = when (this) {
    SatClass.CREWED -> SatelliteGlyphKind.CREWED
    SatClass.WEATHER -> SatelliteGlyphKind.WEATHER
    SatClass.AMATEUR -> SatelliteGlyphKind.AMATEUR
    SatClass.CUBESAT -> SatelliteGlyphKind.CUBESAT
    SatClass.NAVIGATION -> SatelliteGlyphKind.NAVIGATION
    SatClass.ROCKET_BODY -> SatelliteGlyphKind.ROCKET_BODY
    SatClass.OTHER -> SatelliteGlyphKind.OTHER
}
