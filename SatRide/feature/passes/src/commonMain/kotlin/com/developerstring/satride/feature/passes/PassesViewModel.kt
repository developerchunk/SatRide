package com.developerstring.satride.feature.passes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.runCatchingCancellable
import com.developerstring.satride.core.data.PassCoverage
import com.developerstring.satride.core.data.PassRepository
import com.developerstring.satride.core.data.SatelliteRepository
import com.developerstring.satride.core.data.SatelliteTracker
import com.developerstring.satride.core.data.TransmitterRepository
import com.developerstring.satride.core.designsystem.component.SkyPoint
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Satellite
import com.developerstring.satride.core.model.TxStatus
import com.developerstring.satride.core.notifications.AlertCapability
import com.developerstring.satride.core.notifications.AlertScheduler
import com.developerstring.satride.core.notifications.PassAlert
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The planning loop: what is coming over, when, and how good it will be.
 *
 * ## Why the clock is a stream
 *
 * Everything on this screen is relative to *now* — countdowns, the "today" header, which pass is
 * in progress. So a one-second tick is folded into the state rather than read imperatively in the
 * composables. It costs one recomposition a second of a list of a few dozen precomputed rows,
 * which is the same budget the catalog screen already runs on, and it makes the whole screen a
 * pure function of time instead of something that quietly goes stale when the user leaves it open.
 *
 * ## Why the window triggers work
 *
 * `PassRepository.upcoming` reads the cache, and the cache is only filled two days ahead at
 * startup. Choosing the seven-day window therefore has to *cause* the propagation, not merely ask
 * for more of it — otherwise a subscriber's week silently stops after 48 hours, which looks
 * exactly like a bug and is impossible to tell from one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PassesViewModel(
    private val passRepository: PassRepository,
    satelliteRepository: SatelliteRepository,
    transmitterRepository: TransmitterRepository,
    private val tracker: SatelliteTracker,
    private val alertScheduler: AlertScheduler,
    /**
     * How much of the selection has been predicted so far.
     *
     * A flow rather than the coordinator itself: this screen wants a number to display, not the
     * machinery that produces it — and depending on the coordinator would drag an
     * application-scoped background walk into every test of a progress line. Defaults to "nothing
     * in flight", which is what a screen with no coordinator behind it should say.
     */
    passCoverage: Flow<PassCoverage> = flowOf(PassCoverage()),
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val requestedWindow = MutableStateFlow(PassWindow.default)
    private val filters = MutableStateFlow(PassFilters.NONE)
    private val filterSheetOpen = MutableStateFlow(false)
    private val computing = MutableStateFlow(false)

    /**
     * Cached rather than read per emission.
     *
     * On Android `capability` is two binder calls — "are notifications enabled" and "may I schedule
     * an exact alarm" — and this screen rebuilds its state every second, so reading it inline cost
     * a couple of hundred binder round trips per minute on one screen (visible in `dumpsys alarm`
     * as `HAS_SCHEDULE_EXACT_ALARM: count=381`). Nothing changes it except the user granting or
     * revoking permission, which is exactly the two moments it is refreshed.
     */
    private val alertCapability = MutableStateFlow(alertScheduler.capability)

    private val effectChannel = Channel<PassesEffect>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: Flow<PassesEffect> = effectChannel.receiveAsFlow()

    /**
     * How much of the tracked selection has been predicted, as its own stream.
     *
     * Deliberately **not** part of [state]. Building the timeline means collapsing repeats, mapping
     * every pass to a row, regrading and regrouping by day — real work over what can be thousands
     * of passes. Folding a progress number into that state ran the whole derivation again on every
     * progress report, which over a full catalog is thousands of pointless re-renders and made the
     * screen look like it had locked up.
     *
     * The banner reads this directly, so a progress tick recomposes one line of text.
     */
    val coverage: StateFlow<PassCoverage> = passCoverage
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), PassCoverage())

    /** How far ahead the timeline looks. Every window is available to everyone. */
    private val window: StateFlow<PassWindow> =
        requestedWindow.stateIn(viewModelScope, SharingStarted.Eagerly, PassWindow.default)

    private val ticker: Flow<Instant> = flow {
        while (true) {
            emit(clock.now())
            delay(TICK)
        }
    }

    /**
     * Deliberately not `stateIn`-ed with an empty default.
     *
     * A `StateFlow` seeded with `emptyMap()` makes `combine` emit immediately, so the first frame
     * of the timeline would show every satellite as "Object 25544" with no radio badge and every
     * grade one step too low — and then correct itself a frame later. Left as a plain flow,
     * `combine` waits for the real answer, which is what the loading state is for.
     */
    private val satellitesById = satelliteRepository.observeAll()
        .map { list -> list.associateBy { it.noradId } }

    /**
     * Satellites with a transmitter SatNOGS still lists as alive.
     *
     * Status matters here in a way it does not in the catalog: the badge is a promise that there
     * is something to hear, and a dead transmitter would make that promise falsely on every pass
     * of every long-defunct CubeSat.
     */
    private val activeDownlinks = transmitterRepository.observeAll()
        .map { transmitters ->
            transmitters.filter { it.status == TxStatus.ACTIVE }.map { it.noradId }.toSet()
        }

    private val passes: Flow<List<Pass>> = window.flatMapLatest { passRepository.upcoming(it.duration) }

    private val alertedIds = alertScheduler.scheduled()
        .map { alerts -> alerts.map { it.id }.toSet() }

    val state: StateFlow<PassesUiState> = combine(
        combine(passes, satellitesById, activeDownlinks) { passes, satellites, downlinks ->
            // The polar thumbnail arc is a pure function of a pass's own samples, so it is
            // downsampled once here — when the pass *set* changes — rather than every second in
            // `toRow`, which the ticker drives. `Sources` does not depend on the ticker, so this
            // runs a handful of times per window switch instead of once per tick per pass.
            val arcs = passes.associateBy(
                keySelector = { passId(it.noradId, it.aos) },
                valueTransform = { it.arc.toThumbnailArc() },
            )
            Sources(passes, arcs, satellites, downlinks)
        },
        combine(filters, filterSheetOpen, computing, alertCapability) { filters, sheetOpen, computing, capability ->
            Controls(filters, sheetOpen, computing, capability)
        },
        combine(window, alertedIds, tracker.followedIds) { window, alerted, followed ->
            Context(window, alerted, followed)
        },
        ticker,
        tracker.observer,
    ) { sources, controls, context, now, observer ->
        val rows = sources.passes
            // Geostationary and other never-setting objects are dropped before anything else.
            // A pass search over a two-day window returns one "pass" per such satellite spanning
            // the entire window, and the timeline rendered them as a wall of identical cards —
            // "METEOSAT-10, 14:54, 2880m" — which is not wrong so much as meaningless: there is
            // nothing to plan around for something that is always up. `Pass.isAlwaysVisible`
            // exists for exactly this and was written with this screen in mind; not using it put
            // 450 passes in a 24-hour view, almost all of them noise.
            .filter { !it.isAlwaysVisible }
            .collapseRepeatsOfTheSamePass()
            .map { it.toRow(sources, context, now) }
            .filter { it.matches(controls.filters) }

        PassesUiState(
            window = context.window,
            days = rows.groupByDay(),
            filters = controls.filters,
            filterSheetOpen = controls.filterSheetOpen,
            loading = false,
            computing = controls.computing,
            observerUnknown = observer.geoPos == null,
            // Said separately from "nothing is coming": a filtered-out sky and a quiet one look
            // identical and mean different things, and only one of them has a fix the user can act on.
            // Compared against the *passable* set, not the raw one: a sky containing nothing but
            // geostationary birds is a quiet sky, and blaming the user's filters for it would send
            // them to a filter sheet that cannot help.
            filteredToNothing = rows.isEmpty() && sources.passes.any { !it.isAlwaysVisible },
            alertsSupported = controls.alertCapability.supported,
        )
    }
        // The whole derivation — collapsing repeats, mapping every pass to a row, regrading and
        // regrouping by day — reruns once a second off [ticker], and `stateIn(viewModelScope, …)`
        // would run all of it on `Dispatchers.Main.immediate`. Off the main thread; only the
        // finished state crosses back.
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), PassesUiState())

    init {
        // The cache only reaches two days at startup; anything wider has to be computed. Watched
        // rather than done once, so switching to the week or subscribing mid-session both work.
        viewModelScope.launch {
            window.collect { active ->
                if (active == PassWindow.DAY) return@collect
                computing.value = true
                runCatchingCancellable { passRepository.refreshUpcoming(active.duration) }
                    .onFailure {
                        effectChannel.trySend(PassesEffect.ShowMessage("Could not work out the week's passes."))
                    }
                computing.value = false
            }
        }
    }

    fun onEvent(event: PassesEvent) {
        when (event) {
            is PassesEvent.WindowSelected -> onWindowSelected(event.window)

            is PassesEvent.FiltersChanged -> filters.value = event.filters

            PassesEvent.FiltersCleared -> filters.value = PassFilters.NONE

            PassesEvent.FilterSheetOpened -> filterSheetOpen.value = true

            PassesEvent.FilterSheetDismissed -> filterSheetOpen.value = false

            is PassesEvent.PassClicked -> {
                val row = findRow(event.id) ?: return
                effectChannel.trySend(PassesEffect.OpenPass(row.noradId, row.aos.toEpochMilliseconds()))
            }

            is PassesEvent.RemindToggled -> onRemindToggled(event.id)
        }
    }

    private fun onWindowSelected(selected: PassWindow) {
        requestedWindow.value = selected
    }

    /**
     * Arms or cancels a reminder.
     *
     * Nothing gates this beyond the platform's own notification permission, which is asked for at
     * the moment the user arms their first reminder — the only moment the request explains itself.
     */
    private fun onRemindToggled(id: String) {
        val row = findRow(id) ?: return

        if (row.hasAlert) {
            viewModelScope.launch {
                alertScheduler.cancel(id)
                effectChannel.trySend(PassesEffect.ShowMessage("Reminder for ${row.name} cancelled."))
            }
            return
        }

        val capability = alertScheduler.capability.also { alertCapability.value = it }
        if (!capability.supported) {
            effectChannel.trySend(PassesEffect.ShowMessage("This device cannot show pass reminders."))
            return
        }
        if (capability.needsPermission) {
            // Asked at the moment the user wants the thing the permission is for, which is the
            // only moment the request explains itself.
            effectChannel.trySend(PassesEffect.RequestNotificationPermission)
            return
        }

        viewModelScope.launch {
            val alert = PassAlert(
                id = id,
                noradId = row.noradId,
                satelliteName = row.name,
                aos = row.aos,
                maxElevationDeg = row.maxElevationDeg,
                aosAzimuthDeg = row.aosAzimuthDeg,
                createdAt = clock.now(),
            )
            alertScheduler.schedule(alert)
                .onSuccess {
                    val lead = alert.leadTime.inWholeMinutes
                    val exactness = if (capability.exact) "" else " (give or take a few minutes)"
                    effectChannel.trySend(
                        PassesEffect.ShowMessage("You will be told $lead minutes before$exactness."),
                    )
                }
                .onFailure {
                    effectChannel.trySend(
                        PassesEffect.ShowMessage(it.message ?: "Could not set that reminder."),
                    )
                }
        }
    }

    /**
     * Called by the screen once the platform has answered a permission request.
     *
     * The view model cannot ask for a permission itself — that needs an activity — so the screen
     * asks and reports back, and the retry happens here rather than being lost.
     */
    fun onNotificationPermissionResult(granted: Boolean, pendingId: String?) {
        // The permission the platform just settled is half of what capability reports, so this is
        // one of the only two moments the cached value can be wrong.
        alertCapability.value = alertScheduler.capability
        if (!granted) {
            effectChannel.trySend(
                PassesEffect.ShowMessage("Reminders need notification permission, which you can grant in Settings."),
            )
            return
        }
        if (pendingId != null) onRemindToggled(pendingId)
    }

    private fun findRow(id: String): PassRowUi? = state.value.days.firstNotNullOfOrNull { day ->
        day.passes.firstOrNull { it.id == id }
    }

    private fun Pass.toRow(sources: Sources, context: Context, now: Instant): PassRowUi {
        val satellite = sources.satellites[noradId]
        val hasDownlink = noradId in sources.activeDownlinks
        val id = passId(noradId, aos)
        // The countdown label is minute-granular, so `startsIn` is floored to whole minutes for
        // passes still in the future — which keeps every upcoming card `==`-stable across the 1 Hz
        // tick instead of recomposing once a second for nothing. A pass in progress (AOS reached)
        // keeps full resolution, because `livePoint()` interpolates the polar-plot dot from
        // sub-second precision; at most one such card is visible at a time, so letting it alone
        // re-render is the same budget as before, confined to a single card.
        val rawStartsIn = aos - now
        val startsIn =
            if (rawStartsIn <= Duration.ZERO) rawStartsIn else rawStartsIn.inWholeMinutes.minutes
        return PassRowUi(
            id = id,
            noradId = noradId,
            name = satellite?.name ?: "Object $noradId",
            classType = satellite?.classType ?: SatClass.OTHER,
            aos = aos,
            los = los,
            maxElevationDeg = maxElevationDeg,
            aosAzimuthDeg = aosAzimuthDeg,
            losAzimuthDeg = losAzimuthDeg,
            duration = duration,
            grade = PassGrade.of(maxElevationDeg, hasDownlink),
            hasDownlink = hasDownlink,
            arc = sources.arcs[id] ?: emptyList(),
            startsIn = startsIn,
            hasAlert = id in context.alertedIds,
            isFollowed = noradId in context.followedIds,
        )
    }

    /**
     * Collapses cache rows that describe the same physical pass.
     *
     * A pass search clips anything already in progress to the moment the search *started*, and the
     * search runs more than once — at startup, and again whenever the window widens. Each run
     * writes a row with a later AOS, and since the cache is keyed by AOS they all survive. The
     * timeline then listed ARKTIKA-M 1 twice, fourteen minutes apart, with near-identical
     * durations: not a wrong answer so much as the same answer twice.
     *
     * Overlap is the test, because that is what "the same pass" means here — one satellite cannot
     * have two transits at once. The earliest row wins: it was computed first and so has the least
     * clipped, truest AOS.
     */
    private fun List<Pass>.collapseRepeatsOfTheSamePass(): List<Pass> {
        if (size < 2) return this
        val kept = mutableListOf<Pass>()
        val lastLosPerSatellite = mutableMapOf<Int, Instant>()
        sortedBy { it.aos }.forEach { pass ->
            val previousLos = lastLosPerSatellite[pass.noradId]
            if (previousLos != null && pass.aos < previousLos) return@forEach
            kept += pass
            lastLosPerSatellite[pass.noradId] = pass.los
        }
        return kept
    }

    private fun PassRowUi.matches(filters: PassFilters): Boolean {
        if (filters.followedOnly && !isFollowed) return false
        if (filters.radioCapableOnly && !hasDownlink) return false
        if (maxElevationDeg < filters.minMaxElevationDeg) return false
        return true
    }

    /**
     * Groups into local days, preserving the chronological order the repository returned.
     *
     * The day is the user's, not UTC's. A pass at 00:20 local is tonight to the person waiting for
     * it and tomorrow to the clock, and a timeline that files it under tomorrow is telling them the
     * wrong thing about their own evening.
     */
    private fun List<PassRowUi>.groupByDay(): List<PassDayUi> {
        if (isEmpty()) return emptyList()
        val today = clock.now().toLocalDateTime(timeZone).date
        return sortedBy { it.aos }
            .groupBy { it.aos.toLocalDateTime(timeZone).date }
            .map { (date, passes) -> PassDayUi(date = date, label = date.asDayHeader(today), passes = passes) }
    }

    private data class Sources(
        val passes: List<Pass>,
        /** Thumbnail arcs keyed by [passId], downsampled once when the pass set changes. */
        val arcs: Map<String, List<SkyPoint>>,
        val satellites: Map<Int, Satellite>,
        val activeDownlinks: Set<Int>,
    )

    private data class Controls(
        val filters: PassFilters,
        val filterSheetOpen: Boolean,
        val computing: Boolean,
        val alertCapability: AlertCapability,
    )

    private data class Context(val window: PassWindow, val alertedIds: Set<String>, val followedIds: Set<Int>)

    private companion object {
        val TICK = 1.seconds
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}

/**
 * Downsamples a stored arc for a 56 dp plot.
 *
 * A cached pass carries about forty samples. At thumbnail size the whole arc is under fifty pixels
 * long, so most of those samples land on the pixel their neighbour did — and the timeline draws one
 * of these per row, once a second. Keeping the ends is what matters: AOS and LOS are the markers,
 * and dropping either would shorten the arc visibly.
 */
internal fun List<com.developerstring.satride.core.model.TimedTopo>.toThumbnailArc(
    maxPoints: Int = 14,
): List<SkyPoint> {
    if (isEmpty()) return emptyList()
    if (size <= maxPoints) {
        return map { SkyPoint(it.topo.azimuthDeg.toFloat(), it.topo.elevationDeg.toFloat()) }
    }
    val step = (size - 1).toDouble() / (maxPoints - 1)
    return (0 until maxPoints).map { index ->
        val sample = this[(index * step).toInt().coerceAtMost(size - 1)]
        SkyPoint(sample.topo.azimuthDeg.toFloat(), sample.topo.elevationDeg.toFloat())
    }
}
