package com.developerstring.satride.core.data

import com.developerstring.satride.core.data.TleFreshness
import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.network.GpGroup
import com.developerstring.satride.core.notifications.AlertCapability
import com.developerstring.satride.core.notifications.AlertStore
import com.developerstring.satride.core.notifications.DefaultAlertScheduler
import com.developerstring.satride.core.notifications.PassAlert
import com.developerstring.satride.core.notifications.PlatformAlertScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The plumbing between recomputed passes and armed reminders.
 *
 * [com.developerstring.satride.core.notifications.AlertReconciliation] already decides *what* should
 * happen; these check that the coordinator gathers the right inputs and actually carries the
 * answers out — in particular that it never treats "we could not recompute" as "it is not
 * happening", which is the difference between a working reminder and a cancelled one.
 */
class PassAlertCoordinatorTest {

    private val now = Instant.parse("2026-08-04T20:00:00Z")
    private val here = GeoPos(52.2, 0.13)

    @Test
    fun aPassThatMovedIsReArmedAtItsNewTime() = runTest {
        val stored = alert(aos = now + 3.hours)
        val world = world(alerts = listOf(stored), passes = mapOf(ISS to listOf(pass(aos = now + 3.hours + 5.minutes))))

        val result = world.coordinator.reconcile()

        assertEquals(1, result.rescheduled)
        assertEquals(0, result.dropped)
        assertEquals(now + 3.hours + 5.minutes, world.platform.armed.last().aos)
        // Same identity, so the platform replaces the alarm instead of gaining a second one.
        assertEquals(stored.id, world.platform.armed.last().id)
        assertEquals(1, world.store.all().size)
    }

    @Test
    fun aPassThatBarelyMovedIsLeftArmed() = runTest {
        val world = world(
            alerts = listOf(alert(aos = now + 3.hours)),
            passes = mapOf(ISS to listOf(pass(aos = now + 3.hours + 20.seconds))),
        )

        val result = world.coordinator.reconcile()

        assertEquals(1, result.kept)
        assertTrue(world.platform.armed.isEmpty(), "an unmoved pass should not be re-armed")
    }

    @Test
    fun aPassThatNoLongerExistsIsCancelled() = runTest {
        val stored = alert(aos = now + 3.hours)
        val world = world(alerts = listOf(stored), passes = mapOf(ISS to emptyList()))

        val result = world.coordinator.reconcile()

        assertEquals(1, result.dropped)
        assertEquals(listOf(stored.id), world.platform.disarmed)
        assertTrue(world.store.all().isEmpty())
    }

    @Test
    fun anAlertForASatelliteWithNoElementsIsLeftAlone() = runTest {
        val stored = alert(aos = now + 3.hours)
        // No TLE, so nothing can be recomputed. Cancelling here would silently delete a reminder
        // because a download failed.
        val world = world(alerts = listOf(stored), passes = emptyMap(), tles = emptyMap())

        val result = world.coordinator.reconcile()

        assertEquals(1, result.kept)
        assertEquals(0, result.dropped)
        assertEquals(listOf(stored), world.store.all())
    }

    @Test
    fun withNoObserverPositionEverythingIsLeftAlone() = runTest {
        val stored = alert(aos = now + 3.hours)
        val world = world(alerts = listOf(stored), passes = emptyMap(), observer = null)

        val result = world.coordinator.reconcile()

        assertEquals(1, result.kept)
        assertEquals(listOf(stored), world.store.all())
    }

    @Test
    fun withNoAlertsNothingIsRecomputed() = runTest {
        val world = world(alerts = emptyList(), passes = emptyMap())

        val result = world.coordinator.reconcile()

        assertEquals(PassAlertCoordinator.Result.NOTHING_TO_DO, result)
        assertTrue(world.passRepository.requestedWindows.isEmpty(), "no alerts means no propagation")
    }

    @Test
    fun theRecomputeWindowReachesPastTheFurthestAlert() = runTest {
        val world = world(
            alerts = listOf(alert(noradId = ISS, aos = now + 5.hours), alert(noradId = NOAA, aos = now + 30.hours)),
            passes = mapOf(ISS to listOf(pass(aos = now + 5.hours)), NOAA to listOf(pass(NOAA, now + 30.hours))),
            tles = mapOf(ISS to tle(ISS), NOAA to tle(NOAA)),
        )

        world.coordinator.reconcile()

        assertTrue(
            world.passRepository.requestedWindows.all { it > 30.hours },
            "a window shorter than the furthest alert would report it missing: ${world.passRepository.requestedWindows}",
        )
    }

    // --- fakes -------------------------------------------------------------------------------

    private class World(
        val coordinator: PassAlertCoordinator,
        val store: InMemoryStore,
        val platform: RecordingPlatform,
        val passRepository: FakePassRepository,
    )

    private fun world(
        alerts: List<PassAlert>,
        passes: Map<Int, List<Pass>>,
        tles: Map<Int, Tle> = mapOf(ISS to tle(ISS)),
        observer: GeoPos? = here,
    ): World {
        val store = InMemoryStore(alerts)
        val platform = RecordingPlatform()
        val clock = FixedClock(now)
        val scheduler = DefaultAlertScheduler(store, platform, clock)
        val passRepository = FakePassRepository(passes)
        return World(
            coordinator = PassAlertCoordinator(
                scheduler = scheduler,
                store = store,
                passRepository = passRepository,
                tleRepository = FakeTleRepository(tles),
                observerPositions = { flowOf(observer) },
                clock = clock,
            ),
            store = store,
            platform = platform,
            passRepository = passRepository,
        )
    }

    private class InMemoryStore(initial: List<PassAlert>) : AlertStore {
        private val alerts = MutableStateFlow(initial.associateBy { it.id })
        override fun observeAll() = alerts.map { it.values.toList() }
        override suspend fun all() = alerts.value.values.sortedBy { it.aos }
        override suspend fun put(alert: PassAlert) {
            alerts.value = alerts.value + (alert.id to alert)
        }

        override suspend fun remove(alertId: String) {
            alerts.value = alerts.value - alertId
        }

        override suspend fun removePassed(now: Instant) {
            alerts.value = alerts.value.filterValues { it.aos > now }
        }
    }

    private class RecordingPlatform : PlatformAlertScheduler {
        val armed = mutableListOf<PassAlert>()
        val disarmed = mutableListOf<String>()
        override val capability = AlertCapability(supported = true, exact = true)
        override suspend fun arm(alert: PassAlert): Result<Unit> {
            armed += alert
            return Result.success(Unit)
        }

        override suspend fun disarm(alertId: String) {
            disarmed += alertId
        }
    }

    private class FakePassRepository(private val passes: Map<Int, List<Pass>>) : PassRepository {
        val requestedWindows = mutableListOf<Duration>()
        override fun upcoming(window: Duration): Flow<List<Pass>> = flowOf(emptyList())
        override suspend fun passesFor(noradId: Int, window: Duration) = passesFor(noradId, here0, window)
        override suspend fun passesFor(noradId: Int, observer: GeoPos, window: Duration): List<Pass> {
            requestedWindows += window
            return passes[noradId].orEmpty()
        }

        override suspend fun nextPass(noradId: Int, observer: GeoPos): Pass? = passes[noradId]?.firstOrNull()
        override suspend fun prune() = Unit
        override suspend fun refreshUpcoming(window: Duration): Int = 0

        private companion object {
            val here0 = GeoPos(0.0, 0.0)
        }
    }

    private class FakeTleRepository(private val tles: Map<Int, Tle>) : TleRepository {
        override suspend fun tleFor(noradId: Int): Tle? = tles[noradId]
        override suspend fun tlesFor(noradIds: List<Int>): Map<Int, Tle> = tles.filterKeys { it in noradIds }
        override fun freshness(): Flow<TleFreshness> = flowOf(TleFreshness.Empty)
        override suspend fun elementAgeOf(noradId: Int): Duration? = null
        override suspend fun refresh(groups: Set<GpGroup>, onProgress: (RefreshProgress) -> Unit): RefreshResult =
            RefreshResult(sources = emptyList(), tlesUpdated = 0, satellitesSeen = 0, transmittersUpdated = 0)

        override suspend fun refreshIfStale(groups: Set<GpGroup>): RefreshResult? = null
    }

    private fun alert(noradId: Int = ISS, aos: Instant): PassAlert = PassAlert(
        id = PassAlert.idFor(noradId, aos),
        noradId = noradId,
        satelliteName = "SAT $noradId",
        aos = aos,
        maxElevationDeg = 55.0,
        aosAzimuthDeg = 270.0,
        createdAt = now,
    )

    private fun pass(noradId: Int = ISS, aos: Instant): Pass = Pass(
        noradId = noradId,
        aos = aos,
        tca = aos + 5.minutes,
        los = aos + 10.minutes,
        maxElevationDeg = 55.0,
        aosAzimuthDeg = 270.0,
        losAzimuthDeg = 50.0,
        arc = emptyList(),
    )

    private fun tle(noradId: Int) = Tle("SAT $noradId", noradId, "1", "2", now - 1.hours)

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private companion object {
        const val ISS = 25544
        const val NOAA = 33591
    }
}
