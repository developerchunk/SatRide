package com.developerstring.satride.feature.satellites

import app.cash.turbine.test
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.MutableClock
import com.developerstring.satride.core.common.RadioLauncher
import com.developerstring.satride.core.common.UnavailableRadioLauncher
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.TxMode
import com.developerstring.satride.core.network.IssEventsApi
import com.developerstring.satride.core.network.SatRideHttpClient
import com.developerstring.satride.feature.satellites.detail.SatelliteDetailEffect
import com.developerstring.satride.feature.satellites.detail.SatelliteDetailEvent
import com.developerstring.satride.feature.satellites.detail.SatelliteDetailUiState
import com.developerstring.satride.feature.satellites.detail.SatelliteDetailViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SatelliteDetailViewModelTest {

    /**
     * `viewModelScope` runs on `Dispatchers.Main`, which on Kotlin/Native is the platform run loop
     * and is not driven by a test's virtual clock — so without this every view model's internal
     * collection silently never runs, and the whole class passes on JVM and hangs on iOS.
     */
    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setMainDispatcher() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    private val now = Instant.parse("2026-08-02T12:00:00Z")
    private val clock = MutableClock(now)

    private val satellites = FakeSatelliteRepository(
        listOf(
            satellite(25544, "ISS (ZARYA)", SatClass.CREWED, curated = true),
            satellite(33591, "NOAA 19", SatClass.WEATHER, curated = true),
        ),
    )
    private val transmitters = FakeTransmitterRepository(
        listOf(
            transmitter(25544, id = "iss-voice", downlinkHz = 145_800_000L, mode = TxMode.FM),
            transmitter(25544, id = "iss-sstv", downlinkHz = 145_800_000L, mode = TxMode.SSTV),
            transmitter(25544, id = "iss-lora", downlinkHz = 437_000_000L, mode = TxMode.LORA),
        ),
    )
    private val passes = FakePassRepository(listOf(pass(25544, aos = now + 12.minutes, maxElevationDeg = 54.0)))
    private val tles = FakeTleRepository().apply { elementAge = 2.days }
    private val tracker = FakeSatelliteTracker()

    /** The endpoint is unpublished, so a 404 is the path the app actually takes today. */
    private fun issApi() = IssEventsApi(
        client = SatRideHttpClient.create(MockEngine { respondError(HttpStatusCode.NotFound) }),
    )

    private fun TestScope.viewModel(
        noradId: Int = 25544,
        radioLauncher: RadioLauncher = UnavailableRadioLauncher(),
    ): SatelliteDetailViewModel {
        val viewModel = SatelliteDetailViewModel(
            noradId = noradId,
            satelliteRepository = satellites,
            transmitterRepository = transmitters,
            passRepository = passes,
            tleRepository = tles,
            trackingEngine = tracker,
            radioLauncher = radioLauncher,
            issEventsApi = issApi(),
            clock = clock,
            // `flowOn`-ed state chain: point it at the test dispatcher so it stays under the
            // virtual clock rather than a real thread pool.
            dispatchers = TestDispatchers(mainDispatcher),
        )
        backgroundScope.launch { viewModel.state.collect { } }
        testScheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun showsTheSatelliteAndItsDownlinks() = runTest(mainDispatcher) {
        val state = viewModel().state.first { !it.loading }

        assertEquals("ISS (ZARYA)", state.satellite?.name)
        assertEquals(3, state.transmitters.size)
        assertEquals(setOf("iss-voice", "iss-sstv", "iss-lora"), state.transmitters.map { it.id }.toSet())
    }

    /**
     * Which downlinks SatRide can turn into something, as opposed to only tune. Claiming more would
     * mean a user sitting through a pass for nothing.
     */
    @Test
    fun marksOnlyTheDownlinksWithADecoder() = runTest(mainDispatcher) {
        val state = viewModel().state.first { it.transmitters.isNotEmpty() }

        assertTrue(state.transmitters.first { it.id == "iss-sstv" }.isDecodable)
        assertTrue(state.transmitters.first { it.id == "iss-voice" }.isDecodable)
        assertTrue(!state.transmitters.first { it.id == "iss-lora" }.isDecodable, "LoRa has no decoder")
    }

    /** Opening the screen tracks the satellite, so its numbers tick while it is on screen. */
    @Test
    fun tracksTheSatelliteWhileOpen() = runTest(mainDispatcher) {
        viewModel()

        assertTrue(tracker.isFollowed(25544))
    }

    /**
     * Following is a deliberate act and outlives the screen; merely looking at a satellite does
     * not. Conflating them either leaks tracked satellites or silently un-follows a pinned one.
     */
    @Test
    fun releasesTheSatelliteOnCloseUnlessTheUserPinnedIt() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.releaseTracking()

        assertTrue(!tracker.isFollowed(25544))
    }

    @Test
    fun aPinnedSatelliteStaysTrackedAfterClose() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(SatelliteDetailEvent.FollowToggled)
        assertTrue(viewModel.state.first { it.isFollowed }.isFollowed)

        viewModel.releaseTracking()

        assertTrue(tracker.isFollowed(25544))
    }

    @Test
    fun theFollowToggleTurnsBackOff() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(SatelliteDetailEvent.FollowToggled)
        viewModel.state.first { it.isFollowed }

        viewModel.onEvent(SatelliteDetailEvent.FollowToggled)

        assertTrue(!viewModel.state.first { !it.isFollowed }.isFollowed)
        assertTrue(!tracker.isFollowed(25544))
    }

    @Test
    fun surfacesLivePositionAsItTicks() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        tracker.setUp(25544, elevationDeg = 42.0, azimuthDeg = 190.0, sunlit = true)

        val state = viewModel.state.first { it.snapshot != null }

        assertEquals(42.0, state.snapshot?.topo?.elevationDeg)
        assertTrue(state.isUp)
        assertTrue(state.snapshot?.sunlit == true)
    }

    @Test
    fun showsTheNextPassAndHowLongUntilIt() = runTest(mainDispatcher) {
        val state = viewModel().state.first { it.nextPass != null }

        assertEquals(12.minutes, state.nextPassIn)
        assertEquals(54.0, state.nextPass?.maxElevationDeg)
    }

    @Test
    fun reportsTheElementSetsOwnAge() = runTest(mainDispatcher) {
        val state = viewModel().state.first { it.elementAge != null }

        assertEquals(2.days, state.elementAge)
    }

    /** Tapping Tune before the radio exists explains itself rather than doing nothing. */
    @Test
    fun tuningExplainsItselfWhileTheRadioIsUnavailable() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.effects.test {
            viewModel.onEvent(SatelliteDetailEvent.TuneClicked("iss-voice"))
            assertEquals(SatelliteDetailEffect.RadioUnavailable, awaitItem())
        }
    }

    @Test
    fun tuningStartsASessionOnceTheRadioExists() = runTest(mainDispatcher) {
        var tuned: Pair<Int, String>? = null
        val launcher = object : RadioLauncher {
            override val isAvailable = true
            override fun tune(noradId: Int, transmitterId: String) {
                tuned = noradId to transmitterId
            }
        }

        viewModel(radioLauncher = launcher).onEvent(SatelliteDetailEvent.TuneClicked("iss-sstv"))

        assertEquals(25544 to "iss-sstv", tuned)
    }

    @Test
    fun theNextPassCardOpensThePassScreen() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.effects.test {
            viewModel.onEvent(SatelliteDetailEvent.NextPassClicked)
            assertIs<SatelliteDetailEffect.OpenPass>(awaitItem())
        }
    }

    @Test
    fun theIssBlockAppearsOnlyForTheIss() = runTest(mainDispatcher) {
        assertTrue(viewModel(noradId = 25544).state.first { !it.loading }.isIss)
        assertTrue(!viewModel(noradId = 33591).state.first { !it.loading }.isIss)
    }

    /**
     * The events endpoint is not published yet, so every launch takes the failure path. It has to
     * be silent — the banner simply does not appear.
     */
    @Test
    fun anUnavailableEventFeedIsNotAnError() = runTest(mainDispatcher) {
        val state = viewModel(noradId = 25544).state.first { !it.loading }

        assertEquals(null, state.issEvent)
    }

    @Test
    fun aSatelliteWithNoDownlinksIsNotAnError() = runTest(mainDispatcher) {
        val state = viewModel(noradId = 33591).state.first { !it.loading }

        assertTrue(state.transmitters.isEmpty())
        assertEquals("NOAA 19", state.satellite?.name)
    }

    @Test
    fun theIssCatalogNumberIsTheOneEverybodyKnows() {
        assertEquals(25544, SatelliteDetailUiState.ISS_NORAD_ID)
    }
}
