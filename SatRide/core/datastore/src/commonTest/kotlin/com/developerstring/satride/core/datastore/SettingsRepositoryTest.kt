package com.developerstring.satride.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.developerstring.satride.core.model.GeoPos
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class SettingsRepositoryTest {

    private val paths = mutableListOf<Path>()

    private fun newRepository(): SettingsRepository = SettingsRepository(newDataStore())

    /**
     * A fresh file for every store.
     *
     * DataStore refuses two live instances over one path, and the test runner builds a new class
     * instance per test — so the counter has to outlive the instance or every test reuses the
     * first name and they all fail on the second one.
     */
    private fun newDataStore(): DataStore<Preferences> {
        val path = "${FileSystem.SYSTEM_TEMPORARY_DIRECTORY}/satride-test-${nextFile++}.preferences_pb"
            .toPath()
        paths += path
        return PreferenceDataStoreFactory.createWithPath(produceFile = { path })
    }

    @AfterTest
    fun tearDown() {
        paths.forEach { runCatching { FileSystem.SYSTEM.delete(it) } }
    }

    @Test
    fun startsFromDefaultsWhenNothingHasBeenStored() = runTest {
        val settings = newRepository().settings.first()

        assertEquals(UserSettings(), settings)
        assertEquals(ObserverMode.AUTOMATIC, settings.observerMode)
        assertEquals(24.hours, settings.tleRefreshInterval)
        assertTrue(settings.autoRefreshTles)
    }

    @Test
    fun roundTripsEverySetting() = runTest {
        val repository = newRepository()

        repository.setOnboardingComplete(true)
        repository.setUnits(UnitSystem.IMPERIAL)
        repository.setNightMode(true)
        repository.setAutoRefreshTles(false)
        repository.setTleRefreshInterval(6.hours)
        repository.setMinimumPassElevation(25.0)
        repository.setGainMode(GainMode.MANUAL)
        repository.setManualGainTenthsDb(421)
        repository.setFrequencyCorrectionPpm(-12)
        repository.setBiasTeeEnabled(true)

        val settings = repository.settings.first()

        assertTrue(settings.onboardingComplete)
        assertEquals(UnitSystem.IMPERIAL, settings.units)
        assertTrue(settings.nightMode)
        assertTrue(!settings.autoRefreshTles)
        assertEquals(6.hours, settings.tleRefreshInterval)
        assertEquals(25.0, settings.minimumPassElevationDeg)
        assertEquals(GainMode.MANUAL, settings.gainMode)
        assertEquals(421, settings.manualGainTenthsDb)
        assertEquals(-12, settings.frequencyCorrectionPpm)
        assertTrue(settings.biasTeeEnabled)
    }

    /**
     * Storing a position without switching to manual mode leaves a value that is saved and then
     * ignored, which from the user's side is indistinguishable from the setting not working.
     */
    @Test
    fun settingAManualPositionAlsoSelectsManualMode() = runTest {
        val repository = newRepository()

        repository.setManualPosition(GeoPos(51.5074, -0.1278, 35.0))

        val settings = repository.settings.first()
        assertEquals(ObserverMode.MANUAL, settings.observerMode)
        assertEquals(51.5074, settings.manualPosition?.latDeg)
        assertEquals(-0.1278, settings.manualPosition?.lonDeg)
        assertEquals(35.0, settings.manualPosition?.altM)
    }

    @Test
    fun reportsNoManualPositionUntilOneIsSet() = runTest {
        assertNull(newRepository().settings.first().manualPosition)
    }

    @Test
    fun clampsAnOutOfRangeMinimumElevation() = runTest {
        val repository = newRepository()

        repository.setMinimumPassElevation(120.0)
        assertEquals(90.0, repository.settings.first().minimumPassElevationDeg)

        repository.setMinimumPassElevation(-5.0)
        assertEquals(0.0, repository.settings.first().minimumPassElevationDeg)
    }

    @Test
    fun remembersRtlTcpEndpointsMostRecentFirst() = runTest {
        val repository = newRepository()

        repository.rememberRtlTcpEndpoint("192.168.1.10:1234")
        repository.rememberRtlTcpEndpoint("raspberrypi.local:1234")

        assertEquals(
            listOf("raspberrypi.local:1234", "192.168.1.10:1234"),
            repository.settings.first().rtlTcpHistory,
        )
    }

    @Test
    fun reusingAnEndpointMovesItToTheFrontInsteadOfDuplicatingIt() = runTest {
        val repository = newRepository()

        repository.rememberRtlTcpEndpoint("a:1234")
        repository.rememberRtlTcpEndpoint("b:1234")
        repository.rememberRtlTcpEndpoint("a:1234")

        assertEquals(listOf("a:1234", "b:1234"), repository.settings.first().rtlTcpHistory)
    }

    @Test
    fun capsTheEndpointHistorySoItStaysAShortlist() = runTest {
        val repository = newRepository()

        repeat(UserSettings.MAX_RTL_TCP_HISTORY + 3) { repository.rememberRtlTcpEndpoint("host$it:1234") }

        val history = repository.settings.first().rtlTcpHistory
        assertEquals(UserSettings.MAX_RTL_TCP_HISTORY, history.size)
        assertEquals("host${UserSettings.MAX_RTL_TCP_HISTORY + 2}:1234", history.first())
    }

    @Test
    fun ignoresABlankEndpoint() = runTest {
        val repository = newRepository()

        repository.rememberRtlTcpEndpoint("   ")

        assertTrue(repository.settings.first().rtlTcpHistory.isEmpty())
    }

    @Test
    fun persistsAcrossInstancesOfTheRepository() = runTest {
        val store = newDataStore()

        SettingsRepository(store).setOnboardingComplete(true)

        assertTrue(SettingsRepository(store).settings.first().onboardingComplete)
    }

    private companion object {
        private var nextFile: Int = 0
    }
}
