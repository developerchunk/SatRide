package com.developerstring.satride.feature.play

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.data.SatelliteRepository
import com.developerstring.satride.core.data.TleRepository
import com.developerstring.satride.core.data.TrackingSelection
import com.developerstring.satride.core.data.TrackingSelectionRepository
import com.developerstring.satride.core.datastore.TrackingBudget
import com.developerstring.satride.core.model.Satellite
import com.developerstring.satride.core.orbit.frames.Frames
import com.developerstring.satride.feature.play.engine.GameClock
import com.developerstring.satride.feature.play.engine.SatelliteField
import com.developerstring.satride.feature.play.render.CityLights
import com.developerstring.satride.feature.play.render.OrbitRingData
import com.developerstring.satride.feature.play.render.VectorEarth
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.PI
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Owns the Play scene: the simulated clock, the propagator pool and the selection.
 *
 * The split with the renderer is deliberate: this class produces *state at human rate*
 * (UiState, HUD numbers, the sampled orbit ring), while [field] and [gameClock] are stable
 * handles the render loop reads at frame rate without touching a flow.
 */
@OptIn(FlowPreview::class)
class PlayViewModel(
    private val satelliteRepository: SatelliteRepository,
    private val tleRepository: TleRepository,
    private val selectionRepository: TrackingSelectionRepository,
    private val textureSource: EarthTextureSource,
    private val dispatchers: AppDispatchers,
    clock: Clock,
) : ViewModel() {

    private val logger = Logger.withTag("PlayViewModel")

    val gameClock = GameClock(clock)
    val field = SatelliteField()

    /** Decoded once, read by the renderer every frame. */
    var dayTexture: ImageBitmap? by mutableStateOf(null)
        private set
    var nightTexture: ImageBitmap? by mutableStateOf(null)
        private set

    /** The vector surface; while null the renderer falls back to the bitmap globe. */
    var vectorEarth: VectorEarth? by mutableStateOf(null)

    var cityLights: CityLights? by mutableStateOf(null)
        private set

    /** One sampled revolution of the selected orbit; null until computed. */
    var orbitRing: OrbitRingData? by mutableStateOf(null)
        private set

    private val _uiState = MutableStateFlow(PlayUiState())
    val uiState: StateFlow<PlayUiState> = _uiState.asStateFlow()

    /**
     * Whether the Play screen is currently composed.
     *
     * Set by [PlayScreen] for as long as it is on screen. Tab state restoration keeps this view
     * model alive across tab switches, which is what makes returning to Play instant — and which
     * also means its propagation loops would otherwise keep running forever behind whatever tab
     * the user actually moved to. Nothing here is cancelled on hide; the loops simply park.
     */
    private val visible = MutableStateFlow(false)

    fun onVisibilityChanged(onScreen: Boolean) {
        visible.value = onScreen
    }

    private var ringJob: Job? = null

    /** Whether the "empty field, ask the catalogs once" path has already run this process. */
    private var triedRefresh: Boolean = false

    /**
     * How many satellites a tick may propagate.
     *
     * Read from the tracking budget rather than fixed, because the globe now carries whatever the
     * user asked to track — which can be the whole catalog. See [SatelliteField] for why a slice is
     * enough.
     */
    private var tickSlice: Int = TrackingBudget.default.liveObjects + TrackingBudget.default.rotationObjects

    init {
        loadTextures()
        loadCatalog()
        runTicker()
        runHud()
    }

    fun onEvent(event: PlayUiEvent) {
        when (event) {
            is PlayUiEvent.Select -> select(event.noradId)

            is PlayUiEvent.SetGroup -> _uiState.update { it.copy(group = event.group) }

            is PlayUiEvent.SetTimeScale -> {
                // Ignored in Real mode rather than silently switching modes: the gears are not on
                // screen there, so an event arriving is a bug, not a user intention.
                if (_uiState.value.mode == PlayMode.PLAY) {
                    gameClock.setSpeed(event.scale.factor)
                    _uiState.update { it.copy(timeScale = event.scale) }
                }
            }

            is PlayUiEvent.SetMode -> {
                if (event.mode == PlayMode.REAL) {
                    // Both calls are needed. `setSpeed(1.0)` stops time running fast; `resync()`
                    // discards the *offset* that fast-forwarding accumulated. Without the resync,
                    // Real mode would tick at the right rate from the wrong moment — a globe that
                    // looks entirely plausible and is hours wrong.
                    gameClock.setSpeed(TimeScale.REAL.factor)
                    gameClock.resync()
                }
                _uiState.update {
                    it.copy(
                        mode = event.mode,
                        timeScale = if (event.mode == PlayMode.REAL) TimeScale.REAL else it.timeScale,
                    )
                }
            }

            PlayUiEvent.ToggleRide -> toggleRide()

            PlayUiEvent.Retry -> loadCatalog()
        }
    }

    /** Starts or ends a ride on the selected satellite. */
    private fun toggleRide() {
        val state = _uiState.value
        _uiState.update { it.copy(riding = state.selected != null && !state.riding) }
    }

    private fun select(noradId: Int) {
        val current = _uiState.value.selected?.noradId
        if (noradId <= 0) {
            // A tap on empty space deselects — but not while riding, where taps on space are
            // just the user looking around and losing the satellite would eject them mid-flight.
            if (!_uiState.value.riding) {
                _uiState.update { it.copy(selected = null) }
                orbitRing = null
            }
            return
        }
        if (noradId == current) return

        val index = field.indexOf(noradId)
        if (index < 0) return
        _uiState.update {
            it.copy(
                selected = SelectedSatellite(
                    noradId = noradId,
                    name = field.names[index],
                    classType = field.classes[index],
                ),
            )
        }
        computeOrbitRing(noradId)
    }

    private fun loadTextures() {
        viewModelScope.launch(dispatchers.default) {
            dayTexture = decode(EARTH_DAY_TEXTURE)
            nightTexture = decode(EARTH_NIGHT_TEXTURE)
        }
        viewModelScope.launch(dispatchers.default) {
            val files = EARTH_VECTOR_FILES.map { name ->
                textureSource.read(name) ?: run {
                    logger.w { "missing vector surface file $name" }
                    return@launch
                }
            }
            vectorEarth = VectorEarth.parse(files)
                .also { if (it == null) logger.w { "vector surface files failed to parse" } }

            // Independent of the surface: a missing or unparseable lights file leaves the globe
            // dark on the night side rather than failing the surface with it.
            cityLights = textureSource.read(EARTH_LIGHTS_FILE)?.let { CityLights.parse(it) }
                .also { if (it == null) logger.w { "city lights missing or unparseable" } }
        }
    }

    private suspend fun decode(fileName: String): ImageBitmap? =
        runCatching { textureSource.read(fileName)?.decodeToImageBitmap() }
            .onFailure { logger.w(it) { "cannot decode $fileName" } }
            .getOrNull()

    /**
     * Keeps the globe's field level with what the user has asked to track.
     *
     * The globe used to draw the bundled starter set and nothing else, which was the right call
     * when that was all the app tracked and quite wrong once the catalog was unlocked: turning on
     * "track everything" left Play showing the same two hundred and sixty objects, with no
     * explanation.
     *
     * Reloaded on every change to the selection rather than once at startup, so ticking a
     * satellite anywhere in the app puts it on the globe. `collectLatest` because building a
     * propagator per satellite is seconds of work for a full catalog, and a second change while
     * the first is still loading must abandon it rather than queue behind it.
     *
     * Debounced because a catalog download changes the selection continuously. In
     * `TrackingMode.ALL` the selection *is* the catalog, so every batch of element sets that lands
     * during a sync is a new selection — measured at dozens over a single full-catalog fetch, each
     * one asking for the whole propagator pool to be rebuilt. Waiting for the changes to stop
     * turns that into one rebuild when the download settles.
     */
    private fun loadCatalog() {
        viewModelScope.launch {
            selectionRepository.selection().debounce(SELECTION_SETTLE_MS).collectLatest { selection ->
                tickSlice = selection.budget.liveObjects + selection.budget.rotationObjects
                _uiState.update { it.copy(loading = true, emptyCatalog = false) }

                val satellites = satellitesFor(selection)
                var tles = tleRepository.tlesFor(satellites.map { it.noradId })
                withContext(dispatchers.default) {
                    field.load(satellites, tles)
                    field.tick(gameClock.now())
                }

                // Fresh install that has not seeded or refreshed yet: try once, quietly, and only
                // once per process. It used to run whenever the field came up empty, which with a
                // selection that changes during a download meant asking the catalogs again every
                // time — a request pattern that gets the large groups answered with 403.
                if (field.count == 0 && satellites.isNotEmpty() && !triedRefresh) {
                    triedRefresh = true
                    runCatching { tleRepository.refreshIfStale() }
                        .onFailure { logger.w(it) { "catalog refresh failed" } }
                    tles = tleRepository.tlesFor(satellites.map { it.noradId })
                    withContext(dispatchers.default) {
                        field.load(satellites, tles)
                        field.tick(gameClock.now())
                    }
                }

                _uiState.update {
                    it.copy(
                        loading = false,
                        emptyCatalog = field.count == 0,
                        satelliteCount = field.count,
                        selectionCount = selection.size,
                    )
                }
            }
        }
    }

    /**
     * Names and classes for the selected catalog numbers.
     *
     * Matched against the sorted selection by binary search rather than by building a `Set<Int>`:
     * the selection can be the whole catalog, and boxing thirty thousand integers into a hash set
     * on every reload is the kind of cost this whole change exists to avoid.
     */
    private suspend fun satellitesFor(selection: TrackingSelection): List<Satellite> {
        if (selection.size == 0) return emptyList()
        val all = satelliteRepository.observeAll().first()
        return withContext(dispatchers.default) {
            all.filter { it.noradId in selection }
        }
    }

    /**
     * The propagation heartbeat. Faster simulated time means shorter wall-clock ticks, so the
     * interpolation chord over one fix interval stays too short to see: even at 600× the step
     * is a minute of orbit, a fraction of a degree of arc at global scale.
     *
     * Suspended while the screen is off. This view model outlives the composition — that is the
     * point of tab state restoration — so without the gate, propagating the whole field went on
     * running behind every other tab, for the rest of the session, to move dots nobody was
     * looking at. On resume [GameClock] is asked for the current instant, so the field jumps
     * straight to now rather than replaying the time it was away.
     */
    private fun runTicker() {
        viewModelScope.launch(dispatchers.default) {
            while (isActive) {
                visible.first { it }
                // A slice rather than the whole field: see [SatelliteField.tick]. Scaled so the
                // rotation keeps up when simulated time is running fast — at 600× a tick covers a
                // minute of orbit, and a satellite refreshed a rotation later would be visibly
                // behind the rest.
                if (field.count > 0) field.tick(gameClock.now(), sliceFor(gameClock.speed))
                val speed = gameClock.speed
                delay(
                    when {
                        speed <= 1.0 -> 1000L
                        speed <= 60.0 -> 400L
                        else -> 120L
                    },
                )
            }
        }
    }

    /** Once-a-second HUD numbers for the followed satellite; exact propagation, off-frame. */
    private fun runHud() {
        viewModelScope.launch(dispatchers.default) {
            while (isActive) {
                visible.first { it }
                val selected = _uiState.value.selected
                if (selected != null) {
                    val propagator = field.propagatorFor(selected.noradId)
                    if (propagator != null) {
                        val now = gameClock.now()
                        runCatching {
                            val state = propagator.stateAt(now)
                            val subPoint = Frames.subPoint(state)
                            _uiState.update { ui ->
                                val sel = ui.selected
                                if (sel?.noradId != selected.noradId) return@update ui
                                ui.copy(
                                    selected = sel.copy(
                                        altitudeKm = subPoint.altM / 1000.0,
                                        speedKmS = state.velocity.magnitude,
                                        periodMin = 2.0 * PI / propagator.elements.meanMotionRadPerMin,
                                        latDeg = subPoint.latDeg,
                                        lonDeg = subPoint.lonDeg,
                                    ),
                                )
                            }
                        }
                        // The ring is an ellipse fixed in inertial space; it only drifts with
                        // precession, so refreshing every half hour of simulated time is plenty.
                        val ring = orbitRing
                        if (ring != null && ring.noradId == selected.noradId) {
                            val ageMs = now.toEpochMilliseconds() - ring.epochMs
                            if (ageMs > 30.minutes.inWholeMilliseconds) computeOrbitRing(selected.noradId)
                        }
                    }
                }
                delay(1000L)
            }
        }
    }

    private fun computeOrbitRing(noradId: Int) {
        ringJob?.cancel()
        ringJob = viewModelScope.launch(dispatchers.default) {
            val propagator = field.propagatorFor(noradId) ?: return@launch
            val periodMin = 2.0 * PI / propagator.elements.meanMotionRadPerMin
            val now = gameClock.now()
            val points = RING_POINTS
            val teme = DoubleArray(points * 3)
            for (k in 0 until points) {
                val t = now + (periodMin * (k.toDouble() / points)).minutes
                val state = runCatching { propagator.stateAt(t) }.getOrNull() ?: continue
                teme[k * 3] = state.position.x
                teme[k * 3 + 1] = state.position.y
                teme[k * 3 + 2] = state.position.z
            }
            orbitRing = OrbitRingData(noradId, teme, now.toEpochMilliseconds().toDouble())
        }
    }

    /**
     * How many satellites this tick propagates, given how fast simulated time is running.
     *
     * At real speed the budget's own slice is plenty — a full rotation of thirty thousand objects
     * takes about twenty seconds, over which the interpolated chord deviates from the true arc by
     * well under a kilometre. Fast-forward compresses that: at 600× the same rotation covers three
     * hours of orbit, which would be visible, so the slice grows with the speed until it hits a
     * ceiling that keeps a tick inside its own interval.
     */
    private fun sliceFor(speed: Double): Int {
        if (speed <= 1.0) return tickSlice
        val scaled = (tickSlice * speed).toInt()
        return scaled.coerceIn(tickSlice, MAX_TICK_SLICE)
    }

    private companion object {
        const val RING_POINTS = 240

        /**
         * How long the selection must stop changing before the field is rebuilt.
         *
         * Long enough to sit out a catalog download's stream of writes, short enough that ticking
         * a satellite on another screen puts it on the globe before the user gets back here.
         */
        const val SELECTION_SETTLE_MS = 1_500L

        /**
         * The most satellites one tick will ever evaluate.
         *
         * SGP4 costs roughly ten microseconds each, so this is about forty milliseconds of one
         * background core — noticeable but bounded, and only reached by someone running the globe
         * at six hundred times real speed with a very large selection.
         */
        const val MAX_TICK_SLICE = 4_000
    }
}
