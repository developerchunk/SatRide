package com.developerstring.satride.core.sensors

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.ObserverPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocationProviderTest {

    private val london = GeoPos(51.5074, -0.1278)
    private val darkSkySite = GeoPos(51.9, -1.5)

    private class FakeSource(isAvailable: Boolean = true, private val cached: GeoFix? = null) :
        PlatformLocationSource {
        val emitted = MutableSharedFlow<GeoFix>(replay = 1)
        var lastKnownCalls = 0
            private set

        /** Mutable, so a test can model permission being granted after the provider was built. */
        override var isAvailable: Boolean = isAvailable

        override fun fixes(): Flow<GeoFix> = emitted
        override suspend fun lastKnown(): GeoFix? {
            lastKnownCalls++
            return cached
        }
    }

    /**
     * `backgroundScope`, not a scope of our own.
     *
     * The provider now watches for location permission indefinitely — it has to, because permission
     * is granted after launch and can be revoked and re-granted from Settings at any time. A plain
     * `TestScope` would make `runTest` wait for that watcher to finish, which it never does, and
     * every test in this file would hang. `backgroundScope` is cancelled when the test ends and is
     * not waited on, which is exactly the lifetime the app scope has in production.
     */
    private fun TestScope.provider(source: PlatformLocationSource) =
        // `backgroundScope` for the *lifetime*, the unconfined dispatcher for the *eagerness*:
        // combining a scope with a context keeps the scope's Job, so the watcher is still cancelled
        // when the test ends, while the init block still runs without an explicit advance.
        DefaultLocationProvider(
            source,
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )

    @Test
    fun permissionGrantedAfterStartupIsPickedUpWithoutARestart() = runTest {
        // The bug this guards, reported from a fresh install: the sky view asks for location
        // permission when it opens, which is *after* the provider was constructed. Checking
        // `isAvailable` once meant the provider never subscribed, and the screen sat on "waiting
        // for a location fix" until the app was killed and reopened.
        val source = FakeSource(isAvailable = false, cached = null)
        val provider = provider(source)

        assertIs<ObserverPosition.Unknown>(provider.position.value)

        source.isAvailable = true
        // The watcher's first retry.
        testScheduler.advanceTimeBy(1_500)
        testScheduler.runCurrent()
        source.emitted.emit(GeoFix(GeoPos(51.5074, -0.1278)))
        testScheduler.runCurrent()

        assertIs<ObserverPosition.Automatic>(provider.position.value)
        assertEquals(51.5074, provider.position.value.geoPos?.latDeg)
    }

    @Test
    fun theWatcherBacksOffRatherThanPollingForeverWhenPermissionIsRefused() = runTest {
        // Someone who declined permanently is the common case. Waking once a second for the life of
        // the process, to ask a question that cannot change without a trip to Settings, is a
        // battery cost for nothing — so the interval grows.
        val source = FakeSource(isAvailable = false, cached = null)
        provider(source)

        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()

        // Backing off 1, 2, 4, 8, 16, 30, 30... reaches a minute in far fewer rounds than the
        // sixty a fixed one-second poll would take.
        assertTrue(source.lastKnownCalls < 10, "polled ${source.lastKnownCalls} times in a minute")
    }

    @Test
    fun startsUnknownWithNothingToGoOn() = runTest {
        val provider = provider(FakeSource(isAvailable = true, cached = null))

        assertIs<ObserverPosition.Unknown>(provider.position.value)
    }

    @Test
    fun usesTheCachedFixImmediatelyRatherThanWaitingForANewOne() = runTest {
        val source = FakeSource(cached = GeoFix(london, accuracyM = 800.0))

        val provider = provider(source)
        testScheduler.advanceUntilIdle()

        val position = assertIs<ObserverPosition.Automatic>(provider.position.value)
        assertEquals(london, position.position)
        assertEquals(800.0, position.accuracyM)
    }

    @Test
    fun followsDeviceFixesAsTheyArrive() = runTest {
        val source = FakeSource()
        val provider = provider(source)
        testScheduler.advanceUntilIdle()

        source.emitted.emit(GeoFix(london))
        testScheduler.advanceUntilIdle()

        assertEquals(london, provider.position.value.geoPos)
    }

    /**
     * The precedence rule. Someone who typed a location meant it — they may be planning tonight's
     * session for a site they will drive to — and silently replacing it with the device's own fix
     * would make the setting look broken.
     */
    @Test
    fun aManualPositionOverridesTheDeviceFix() = runTest {
        val source = FakeSource(cached = GeoFix(london))
        val provider = provider(source)
        testScheduler.advanceUntilIdle()

        provider.setManual(darkSkySite)

        val position = assertIs<ObserverPosition.Manual>(provider.position.value)
        assertEquals(darkSkySite, position.position)
    }

    @Test
    fun aDeviceFixArrivingLaterDoesNotDisplaceTheManualPosition() = runTest {
        val source = FakeSource()
        val provider = provider(source)
        provider.setManual(darkSkySite)

        source.emitted.emit(GeoFix(london))
        testScheduler.advanceUntilIdle()

        assertEquals(darkSkySite, provider.position.value.geoPos)
    }

    /**
     * The device fix is remembered while overridden, so switching back does not leave the user
     * staring at an empty sky waiting for the GPS to reacquire.
     */
    @Test
    fun clearingTheManualPositionFallsBackToTheRememberedDeviceFix() = runTest {
        val source = FakeSource()
        val provider = provider(source)
        source.emitted.emit(GeoFix(london))
        testScheduler.advanceUntilIdle()
        provider.setManual(darkSkySite)

        provider.setManual(null)

        val position = assertIs<ObserverPosition.Automatic>(provider.position.value)
        assertEquals(london, position.position)
    }

    @Test
    fun clearingTheManualPositionWithNoDeviceFixReturnsToUnknown() = runTest {
        val provider = provider(FakeSource(cached = null))
        provider.setManual(darkSkySite)

        provider.setManual(null)

        assertIs<ObserverPosition.Unknown>(provider.position.value)
    }

    /**
     * Declined permission, or a platform with no location hardware at all. Manual entry has to
     * keep working — it is the whole fallback path, and on desktop it is the only path.
     */
    @Test
    fun withoutAnAvailableSourceOnlyManualEntryWorks() = runTest {
        val source = FakeSource(isAvailable = false, cached = GeoFix(london))
        val provider = provider(source)
        testScheduler.advanceUntilIdle()

        assertIs<ObserverPosition.Unknown>(provider.position.value)
        assertEquals(0, source.lastKnownCalls, "an unavailable source must not be queried")

        provider.setManual(darkSkySite)
        assertEquals(darkSkySite, provider.position.value.geoPos)
    }

    @Test
    fun requestingAFixIsSafeWhenTheSourceIsUnavailable() = runTest {
        val source = FakeSource(isAvailable = false)
        val provider = provider(source)

        provider.requestFix()

        assertTrue(source.lastKnownCalls == 0)
        assertIs<ObserverPosition.Unknown>(provider.position.value)
    }
}
