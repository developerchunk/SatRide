package com.developerstring.satride.core.data

import com.developerstring.satride.core.common.MutableClock
import com.developerstring.satride.core.datastore.TrackingBudget
import com.developerstring.satride.core.datastore.TrackingMode
import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.ObserverPosition
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Satellite
import com.developerstring.satride.core.model.SatelliteSnapshot
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.model.Topocentric
import com.developerstring.satride.core.network.GpGroup
import com.developerstring.satride.core.orbit.Orbit
import com.developerstring.satride.core.orbit.sgp4.TleFormatter
import com.developerstring.satride.core.orbit.sgp4.TleParser
import com.developerstring.satride.core.sensors.LocationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * The live tracking loop.
 *
 * Driven by `runTest`'s virtual clock, so a test covering several sweep intervals finishes in
 * milliseconds — and so "does it stop when nobody is watching" is answerable at all.
 */
class TrackingEngineTest {

    private val london = GeoPos(51.5074, -0.1278, 35.0)
    private val clock = MutableClock(Instant.parse("2026-08-02T12:00:00Z"))

    private val issTle: Tle by lazy {
        val lines = TestFixtures.read("ariss_iss.txt").lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        TleFormatter.format(TleParser.parse(lines[0], lines[1], lines[2]))
    }

    private class FakeLocationProvider(initial: ObserverPosition) : LocationProvider {
        private val state = MutableStateFlow(initial)
        override val position: StateFlow<ObserverPosition> = state
        override suspend fun requestFix() = Unit
        override fun setManual(position: GeoPos?) {
            state.value = position?.let { ObserverPosition.Manual(it) } ?: ObserverPosition.Unknown
        }
    }

    private class FakeTleRepository(private val tles: Map<Int, Tle>) : TleRepository {
        var lookups = 0
            private set

        override suspend fun tleFor(noradId: Int): Tle? {
            lookups++
            return tles[noradId]
        }

        override suspend fun tlesFor(noradIds: List<Int>): Map<Int, Tle> {
            lookups++
            return tles.filterKeys { it in noradIds }
        }

        override fun freshness() = flowOf(TleFreshness.Empty)
        override suspend fun elementAgeOf(noradId: Int): kotlin.time.Duration? = null
        override suspend fun refresh(groups: Set<GpGroup>, onProgress: (RefreshProgress) -> Unit) =
            RefreshResult.empty()
        override suspend fun refreshIfStale(groups: Set<GpGroup>) = null
    }

    /** The selection, as a flow the test can change. Everything else on the port is unused here. */
    private class FakeSelection(ids: Collection<Int>) : TrackingSelectionRepository {
        val selected = MutableStateFlow(ids.sorted())
        val budget = MutableStateFlow(TrackingBudget.default)

        override fun selection(): Flow<TrackingSelection> = selected.map {
            TrackingSelection(TrackingMode.SELECTED, budget.value, it.toIntArray())
        }

        override fun observeSelectedIds(): Flow<List<Int>> = selected
        override fun observeSelectedCount(): Flow<Int> = selected.map { it.size }
        override fun observeCatalogCount(): Flow<Int> = selected.map { it.size }
        override fun observeClassCounts(): Flow<Map<SatClass, Int>> = flowOf(emptyMap())
        override fun browse(query: CatalogQuery, limit: Int, offset: Int): Flow<List<Satellite>> = flowOf(emptyList())

        override fun countMatching(query: CatalogQuery): Flow<Int> = flowOf(0)
        override suspend fun setMode(mode: TrackingMode) = Unit
        override suspend fun setBudget(budget: TrackingBudget) {
            this.budget.value = budget
        }

        override suspend fun setTracked(noradIds: Collection<Int>, tracked: Boolean) {
            selected.value =
                if (tracked) {
                    (selected.value + noradIds).distinct().sorted()
                } else {
                    (selected.value - noradIds.toSet()).sorted()
                }
        }

        override suspend fun setTrackedForQuery(query: CatalogQuery, tracked: Boolean): Int = 0
        override suspend fun selectAll(): Int = selected.value.size
        override suspend fun clearSelection() {
            selected.value = emptyList()
        }

        override suspend fun selectCurated(): Int = selected.value.size
        override suspend fun selectWithDownlink(): Int = selected.value.size
    }

    private fun TestScope.engine(
        tles: Map<Int, Tle>,
        position: ObserverPosition = ObserverPosition.Manual(london),
        config: TrackingConfig = TrackingConfig(),
    ): Triple<TrackingEngine, FakeLocationProvider, FakeSelection> {
        val location = FakeLocationProvider(position)
        val selection = FakeSelection(tles.keys)
        val engine = TrackingEngine(
            tleRepository = FakeTleRepository(tles),
            selectionRepository = selection,
            locationProvider = location,
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            clock = clock,
            scope = backgroundScope,
            config = config,
        )
        return Triple(engine, location, selection)
    }

    @Test
    fun tracksASelectedSatelliteAndProducesLivePositions() = runTest {
        val (engine, _, _) = engine(mapOf(25544 to issTle))

        val sky = engine.sky.first { it.has(25544) }

        val iss = assertNotNull(sky.snapshotOf(25544))
        assertEquals(25544, iss.noradId)
        assertTrue(iss.topo.rangeKm in 300.0..12_000.0, "range was ${iss.topo.rangeKm} km")
        assertTrue(iss.subPoint.altM > 300_000.0, "sub-point altitude was ${iss.subPoint.altM} m")
        assertTrue(iss.speedKmS in 6.0..9.0, "speed was ${iss.speedKmS} km/s")
    }

    /**
     * Without an observer there is no answer, and inventing one from a previous location would be
     * worse than admitting it — the sky would be drawn confidently in the wrong place.
     */
    @Test
    fun reportsNothingWhileTheObserverPositionIsUnknown() = runTest {
        val (engine, location, _) = engine(mapOf(25544 to issTle), position = ObserverPosition.Unknown)

        assertEquals(0, engine.sky.first().size)

        location.setManual(london)
        assertTrue(engine.sky.first { it.has(25544) }.has(25544))
    }

    @Test
    fun followAndUnfollowChangeTheFollowedSet() = runTest {
        val (engine, _, _) = engine(mapOf(25544 to issTle))

        engine.follow(25544)
        assertTrue(engine.isFollowed(25544))
        assertEquals(setOf(25544), engine.followedIds.value)

        engine.unfollow(25544)
        assertTrue(!engine.isFollowed(25544))
    }

    /**
     * Changing the selection has to change what is tracked, and this is the path that does it —
     * the engine subscribes to the selection rather than being told about it.
     */
    @Test
    fun addingToTheSelectionArmsTheNewSatellite() = runTest {
        val second = issTle.copy(noradId = 99999)
        val (engine, _, selection) = engine(mapOf(25544 to issTle, 99999 to second))
        selection.selected.value = listOf(25544)

        engine.sky.first { it.has(25544) }

        selection.setTracked(listOf(99999), tracked = true)

        assertTrue(engine.sky.first { it.has(99999) }.has(99999))
    }

    /**
     * Every satellite is interpolatable within the warm-up, not a rotation later.
     *
     * A satellite needs two samples before it can be interpolated — one gives a position but no
     * rate, so it holds still. With a large selection the rotation takes tens of seconds to reach
     * the far end, so without the warm-up sweeps most of the sky would be frozen on arrival and
     * then twitch into motion as its turn came round. The budget here is deliberately far smaller
     * than the selection, so anything that relied on the rotation would fail.
     */
    @Test
    fun everySelectedSatelliteIsMovingWithinTheWarmUp() = runTest {
        val base = TleParser.parse(issTle)
        val tles = (0 until 200).associate { i ->
            val noradId = 90_000 + i
            noradId to TleFormatter.format(
                base.copy(
                    noradId = noradId,
                    raanRad = (base.raanRad + i * 0.31) % (2 * kotlin.math.PI),
                    meanAnomalyRad = (base.meanAnomalyRad + i * 0.17) % (2 * kotlin.math.PI),
                ),
            )
        }
        val (engine, _, selection) = engine(tles)
        // Ten a tick: the rotation alone would need twenty ticks to reach the far end.
        selection.budget.value = TrackingBudget.BATTERY

        val collector = backgroundScope.launch { engine.sky.collect { } }
        // Three ticks: the fill, then the two warm-up sweeps.
        advanceTimeBy(3.seconds)
        val sky = engine.sky.value
        collector.cancel()

        val out = DoubleArray(2)
        val at = clock.now().toEpochMicroseconds()
        for (index in 0 until sky.size) {
            assertTrue(sky.samples[index] >= 2, "slot $index has no rate; it will hold still")
            assertTrue(sky.interpolateIndexInto(index, at, out), "slot $index has no position")
        }
    }

    /**
     * When the live budget binds, it drops what is below the horizon before what is above it.
     *
     * With a full catalog four hundred-odd objects are up at once, which is the same order as the
     * budget itself — so the tier has to be filled by elevation band rather than by catalog order,
     * or satellites genuinely overhead get demoted to the rotation while ones on the far side of
     * the Earth are refreshed every second.
     */
    @Test
    fun theLiveTierPrefersWhatIsAboveTheHorizon() = runTest {
        val base = TleParser.parse(issTle)
        val tles = (0 until 400).associate { i ->
            val noradId = 90_000 + i
            noradId to TleFormatter.format(
                base.copy(
                    noradId = noradId,
                    // The two angles advance at unrelated rates on purpose. Stepped together
                    // they stay correlated and the family traces one curve across the sky rather
                    // than covering it — a fixture that put nothing above the horizon, which is a
                    // test that silently proves nothing. The golden angle decorrelates them.
                    raanRad = (base.raanRad + i * (2 * kotlin.math.PI / 400)) % (2 * kotlin.math.PI),
                    meanAnomalyRad = (base.meanAnomalyRad + i * 2.399963) % (2 * kotlin.math.PI),
                ),
            )
        }
        val (engine, _, selection) = engine(tles)
        selection.budget.value = TrackingBudget.BATTERY

        val collector = backgroundScope.launch { engine.sky.collect { } }
        // Past the warm-up, into steady state, where the budget actually binds.
        advanceTimeBy(6.seconds)
        val sky = engine.sky.value
        val settled = clock.now()
        advanceTimeBy(2.seconds)
        collector.cancel()

        // Every satellite above the horizon was refreshed on one of the last ticks, which is only
        // true if the up band is filled before the rest.
        val cutoff = (settled - 2.seconds).toEpochMicroseconds()
        var up = 0
        for (index in 0 until sky.size) {
            if (sky.samples[index].toInt() == 0 || sky.elevationDegAt(index) <= 0.0) continue
            up++
            assertTrue(
                sky.sampledAtMicros[index] >= cutoff,
                "slot ${sky.idAt(index)} is up but was left to the slow rotation",
            )
        }
        assertTrue(up > 0, "the fixture put nothing above the horizon; the test proves nothing")
    }

    /**
     * The battery requirement. A tracker left open in the background must not keep running SGP4
     * — with no collectors nothing should be propagated at all.
     */
    @Test
    fun doesNoWorkWhileNobodyIsCollecting() = runTest {
        val tleRepository = FakeTleRepository(mapOf(25544 to issTle))
        val engine = TrackingEngine(
            tleRepository = tleRepository,
            selectionRepository = FakeSelection(listOf(25544)),
            locationProvider = FakeLocationProvider(ObserverPosition.Manual(london)),
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            clock = clock,
            scope = backgroundScope,
        )
        engine.follow(25544)

        advanceTimeBy(30.seconds)

        assertEquals(0, tleRepository.lookups, "the engine propagated with no subscribers")
    }

    // --- Interpolation -------------------------------------------------------------------------
    //
    // The AR read path, called per satellite per frame. It lives in `LiveSky` now — column-wise,
    // so a full catalog costs arrays rather than a pair of objects per satellite — but the rules
    // it has to obey are unchanged, and so are these tests.

    private fun twoFixes(
        first: Topocentric,
        second: Topocentric,
        gap: kotlin.time.Duration = 1.seconds,
    ): Pair<LiveSky, Instant> {
        val start = Instant.parse("2026-08-02T12:00:00Z")
        val sky = LiveSky(intArrayOf(1))
        sky.write(0, snapshot(first, start), start.toEpochMicroseconds())
        sky.write(0, snapshot(second, start + gap), (start + gap).toEpochMicroseconds())
        return sky to start
    }

    private fun snapshot(topo: Topocentric, at: Instant) = SatelliteSnapshot(
        noradId = 1,
        time = at,
        topo = topo,
        subPoint = GeoPos(0.0, 0.0, 420_000.0),
        footprintRadiusKm = 2300.0,
        speedKmS = 7.66,
        sunlit = true,
    )

    private fun LiveSky.anglesAt(time: Instant): DoubleArray {
        val out = DoubleArray(2)
        assertTrue(interpolateInto(1, time.toEpochMicroseconds(), out))
        return out
    }

    /**
     * Interpolation must produce a value between the fixes rather than snapping to one of them.
     */
    @Test
    fun interpolatesBetweenOneSecondFixes() {
        val (sky, start) = twoFixes(
            Topocentric(10.0, 20.0, 1000.0, -5.0),
            Topocentric(20.0, 30.0, 900.0, -4.0),
        )

        val midpoint = sky.anglesAt(start + 500.milliseconds)

        assertEquals(15.0, midpoint[0], 1e-9)
        assertEquals(25.0, midpoint[1], 1e-9)
    }

    /**
     * The bug this exists to prevent: a satellite passing due north goes 359° → 1°, and a naive
     * average places it at 180° — pointing due south, for exactly one frame. Long enough to look
     * like a glitch and short enough never to be reproducible.
     */
    @Test
    fun interpolationCrossesNorthTheShortWay() {
        val (sky, start) = twoFixes(
            Topocentric(359.0, 45.0, 800.0, 0.0),
            Topocentric(1.0, 45.0, 800.0, 0.0),
        )

        assertEquals(
            0.0,
            sky.anglesAt(start + 500.milliseconds)[0],
            1e-9,
            "expected to pass through north, not through south",
        )
    }

    @Test
    fun interpolationCrossesNorthTheShortWayInTheOtherDirection() {
        val (sky, start) = twoFixes(
            Topocentric(1.0, 45.0, 800.0, 0.0),
            Topocentric(359.0, 45.0, 800.0, 0.0),
        )

        assertEquals(0.0, sky.anglesAt(start + 500.milliseconds)[0], 1e-9)
    }

    /**
     * The render loop always asks for *now*, which is always past the newest fix — so the line has
     * to be followed forwards. Returning the newest fix instead would hold each glyph still for a
     * second and then jump it, which is the exact stutter the two-rate design exists to avoid.
     */
    @Test
    fun predictsForwardPastTheNewestFixRatherThanFreezing() {
        val (sky, start) = twoFixes(
            Topocentric(10.0, 20.0, 1000.0, -5.0),
            Topocentric(20.0, 30.0, 900.0, -4.0),
        )

        // Half a tick past the newest fix: half a step further along the same line.
        val predicted = sky.anglesAt(start + 1500.milliseconds)

        assertEquals(25.0, predicted[0], 1e-9)
        assertEquals(35.0, predicted[1], 1e-9)
    }

    @Test
    fun predictionIsBoundedSoAStalledEngineDoesNotFlingGlyphsAcrossTheSky() {
        val (sky, start) = twoFixes(
            Topocentric(10.0, 20.0, 1000.0, -5.0),
            Topocentric(20.0, 30.0, 900.0, -4.0),
        )

        // One span past the newest fix is the limit; a minute later gives the same answer.
        val atLimit = sky.anglesAt(start + 2.seconds)
        val wayPast = sky.anglesAt(start + 60.seconds)

        assertEquals(30.0, atLimit[0], 1e-9)
        assertEquals(atLimit[0], wayPast[0], 1e-9)
        assertEquals(atLimit[1], wayPast[1], 1e-9)
    }

    @Test
    fun doesNotPredictBackwardsBeforeTheOlderFix() {
        val (sky, start) = twoFixes(
            Topocentric(10.0, 20.0, 1000.0, -5.0),
            Topocentric(20.0, 30.0, 900.0, -4.0),
        )

        assertEquals(10.0, sky.anglesAt(start - 5.seconds)[0], 1e-9)
    }

    @Test
    fun predictedElevationStaysWithinTheZenith() {
        // A satellite climbing hard towards the zenith; naive prediction would push it past 90.
        val (sky, start) = twoFixes(
            Topocentric(10.0, 70.0, 500.0, -6.0),
            Topocentric(10.0, 88.0, 480.0, -1.0),
        )

        assertEquals(90.0, sky.anglesAt(start + 2.seconds)[1], 1e-9)
    }

    /**
     * A satellite with no position must be distinguishable from one at zero elevation.
     *
     * NaN rather than 0.0 for exactly this reason: the catalog row says "not tracked" where a real
     * zero would say "on the horizon", and those mean different things.
     */
    @Test
    fun readsAreEmptyBeforeThereIsAnythingToInterpolate() = runTest {
        val (engine, _, _) = engine(mapOf(25544 to issTle))

        assertNull(engine.interpolated(25544, clock.now()))
        assertTrue(LiveSky.EMPTY.elevationDegOf(25544).isNaN())
    }

    @Test
    fun interpolatedTracksTheLivePositions() = runTest {
        val (engine, _, _) = engine(mapOf(25544 to issTle))
        val snapshot = assertNotNull(engine.sky.first { it.has(25544) }.snapshotOf(25544))

        val interpolated = assertNotNull(engine.interpolated(25544, snapshot.time))

        assertEquals(snapshot.topo.azimuthDeg, interpolated.azimuthDeg, 1e-6)
        assertEquals(snapshot.topo.elevationDeg, interpolated.elevationDeg, 1e-6)
    }

    /** Time travel (P5.7) must not disturb the live interpolation the user returns to. */
    @Test
    fun propagatingAtAnArbitraryTimeLeavesTheLiveFixesAlone() = runTest {
        val (engine, _, _) = engine(mapOf(25544 to issTle))
        val liveBefore = engine.sky.first { it.has(25544) }.elevationDegOf(25544)

        engine.at(clock.now() + 30.seconds * 60)

        assertEquals(liveBefore, engine.sky.value.elevationDegOf(25544), 1e-12)
    }

    /**
     * The performance budget from P3.4: a full sweep of a realistic catalog has to fit inside a
     * fraction of the one-second tick, or the engine cannot keep up with itself.
     *
     * Bound generously — this runs on CI machines of unknown speed, and the point is to catch an
     * order-of-magnitude regression such as rebuilding propagators every tick, not to police
     * microseconds.
     */
    @Test
    fun propagatesThreeHundredSatellitesWellInsideOneTick() {
        val base = TleParser.parse(issTle)
        // Distinct orbits so no two satellites share a propagator's cached state.
        val satellites = (0 until 300).map { i ->
            Orbit.satellite(
                base.copy(
                    noradId = 90000 + i,
                    raanRad = (base.raanRad + i * 0.02) % (2 * kotlin.math.PI),
                    meanAnomalyRad = (base.meanAnomalyRad + i * 0.017) % (2 * kotlin.math.PI),
                ),
            )
        }
        val now = clock.now()

        // Warm up so the measurement is of steady-state work, not of JIT and class loading.
        repeat(3) { satellites.forEach { it.snapshot(now, london) } }

        val started = TimeSource.Monotonic.markNow()
        satellites.forEach { it.snapshot(now, london) }
        val elapsed = started.elapsedNow()

        println("300-satellite sweep: ${elapsed.inWholeMicroseconds / 1000.0} ms")
        assertTrue(elapsed < 50.milliseconds, "300-satellite sweep took $elapsed")
    }

    /**
     * A thousand satellites, fifty ticks, one table.
     *
     * The claim `LiveSky` is built on: a tick writes in place, so the previous sample is carried
     * forward rather than lost, and every entry stays interpolatable however many ticks have gone
     * by. An implementation that rebuilt its storage each tick — which is what a
     * `Map<Int, SatelliteSnapshot>` amounts to — would either lose the previous sample or allocate
     * a new pair of objects per satellite per tick.
     */
    @Test
    fun writingATickKeepsEveryEntryInterpolatable() {
        val start = Instant.parse("2026-08-02T12:00:00Z")
        val sky = LiveSky(IntArray(1_000) { it })
        assertNull(sky.snapshotOf(0), "a fresh table has no positions")

        // Elevation climbs a degree a tick, so the last two samples for any entry differ and the
        // interpolation being checked is a real one rather than two copies of the same number.
        repeat(50) { tick ->
            val at = start + tick.seconds
            for (index in 0 until sky.size) {
                val elevation = 10.0 + tick
                sky.write(
                    index,
                    snapshot(Topocentric(index % 360.0, elevation, 800.0, 0.0), at),
                    at.toEpochMicroseconds(),
                )
            }
        }

        val out = DoubleArray(2)
        assertTrue(sky.interpolateInto(999, (start + 49.seconds).toEpochMicroseconds(), out))
        assertEquals(999 % 360.0, out[0], 1e-9)
        assertEquals(59.0, out[1], 1e-9, "the newest sample")

        // Halfway between the last two ticks, which only exists if the previous one survived.
        assertTrue(sky.interpolateInto(999, (start + 48_500.milliseconds).toEpochMicroseconds(), out))
        assertEquals(58.5, out[1], 1e-9)
    }

    /**
     * Propagator construction runs SGP4's initialisation, which is most of the cost of a single
     * propagation. Rebuilding one per tick would multiply the engine's work by the size of the
     * tracked set, which is the difference between a warm phone and a hot one.
     */
    @Test
    fun reusesPropagatorsAcrossTicks() = runTest {
        val tleRepository = FakeTleRepository(mapOf(25544 to issTle))
        val engine = TrackingEngine(
            tleRepository = tleRepository,
            selectionRepository = FakeSelection(listOf(25544)),
            locationProvider = FakeLocationProvider(ObserverPosition.Manual(london)),
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            clock = clock,
            scope = backgroundScope,
        )

        val collector = backgroundScope.launch { engine.sky.collect { } }
        advanceTimeBy(5.seconds)
        collector.cancel()

        // Element sets are read once, when the selection is armed. The per-tick path must never
        // touch storage at all.
        assertTrue(
            tleRepository.lookups <= 2,
            "looked element sets up ${tleRepository.lookups} times across five ticks",
        )
    }
}
