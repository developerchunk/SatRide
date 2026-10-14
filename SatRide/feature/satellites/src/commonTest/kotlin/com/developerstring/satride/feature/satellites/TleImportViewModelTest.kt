package com.developerstring.satride.feature.satellites

import app.cash.turbine.test
import com.developerstring.satride.core.common.AppError
import com.developerstring.satride.core.orbit.sgp4.TleParseException
import com.developerstring.satride.feature.satellites.import.ImportAllowance
import com.developerstring.satride.feature.satellites.import.TleImportEffect
import com.developerstring.satride.feature.satellites.import.TleImportEvent
import com.developerstring.satride.feature.satellites.import.TleImportUiState
import com.developerstring.satride.feature.satellites.import.TleImportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

class TleImportViewModelTest {

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

    private val satellites = FakeSatelliteRepository()
    private val selection = FakeTrackingSelectionRepository(catalog = satellites.all)

    private class FakeImportAllowance : ImportAllowance {
        private val used = MutableStateFlow(0)
        override val importsUsed: Flow<Int> = used
        override suspend fun recordImport() {
            used.value += 1
        }
    }

    private val allowance = FakeImportAllowance()

    private val validText = """
        ISS (ZARYA)
        1 25544U 98067A   26213.79682580  .00007322  00000-0  13941-3 0  9994
        2 25544  51.6314  74.3802 0007140   2.2740 357.8280 15.49304641578823
    """.trimIndent()

    private fun TestScope.viewModel(): TleImportViewModel {
        val viewModel = TleImportViewModel(satellites, allowance, selection)
        backgroundScope.launch { viewModel.state.collect { } }
        testScheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun importsPastedElementSets() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(TleImportEvent.TextChanged(validText))

        viewModel.effects.test {
            viewModel.onEvent(TleImportEvent.Submitted)
            assertEquals(TleImportEffect.Imported(1, tracked = true), awaitItem())
        }

        assertEquals(validText, satellites.importedText)
    }

    @Test
    fun clearsTheFieldAfterASuccessfulImport() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(TleImportEvent.TextChanged(validText))
        assertEquals(validText, viewModel.state.first { it.text.isNotEmpty() }.text)

        viewModel.onEvent(TleImportEvent.Submitted)

        assertEquals("", viewModel.state.first { it.text.isEmpty() }.text)
    }

    /**
     * Typing and immediately tapping Import must work. Gating on the published state instead of
     * the field itself made the first tap do nothing at all.
     */
    @Test
    fun importingImmediatelyAfterTypingStillWorks() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.effects.test {
            viewModel.onEvent(TleImportEvent.TextChanged(validText))
            viewModel.onEvent(TleImportEvent.Submitted)

            assertEquals(TleImportEffect.Imported(1, tracked = true), awaitItem())
        }
    }

    @Test
    fun cannotSubmitAnEmptyField() = runTest(mainDispatcher) {
        assertTrue(!viewModel().state.first().canSubmit)
    }

    /**
     * Parser messages name a column or a checksum, which is right for a log and useless to
     * somebody who pasted from a web page. What comes back has to say what to do about it.
     */
    @Test
    fun aChecksumFailureExplainsWhatWentWrong() = runTest(mainDispatcher) {
        satellites.importResult = Result.failure(TleParseException("Checksum mismatch: expected 4, computed 7"))
        val viewModel = viewModel()
        viewModel.onEvent(TleImportEvent.TextChanged(validText))

        viewModel.onEvent(TleImportEvent.Submitted)

        val error = viewModel.state.first { it.error != null }.error!!
        assertTrue(error.contains("copying", ignoreCase = true), "unhelpful message: $error")
    }

    @Test
    fun textThatIsNotAnElementSetExplainsTheExpectedShape() = runTest(mainDispatcher) {
        satellites.importResult = Result.failure(AppError.InvalidInput("No element sets found. Expected two lines."))
        val viewModel = viewModel()
        viewModel.onEvent(TleImportEvent.TextChanged("here you go!"))

        viewModel.onEvent(TleImportEvent.Submitted)

        assertTrue(viewModel.state.first { it.error != null }.error!!.contains("element sets"))
    }

    @Test
    fun editingClearsAStaleError() = runTest(mainDispatcher) {
        satellites.importResult = Result.failure(AppError.InvalidInput("nope"))
        val viewModel = viewModel()
        viewModel.onEvent(TleImportEvent.TextChanged("bad"))
        viewModel.onEvent(TleImportEvent.Submitted)
        viewModel.state.first { it.error != null }

        viewModel.onEvent(TleImportEvent.TextChanged("better"))

        assertEquals(null, viewModel.state.first { it.text == "better" }.error)
    }

    /** Imports are unlimited, and every one of them lands. */
    @Test
    fun importsAreUnlimited() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        repeat(3) {
            viewModel.onEvent(TleImportEvent.TextChanged(validText))
            viewModel.onEvent(TleImportEvent.Submitted)
            testScheduler.advanceUntilIdle()
        }

        assertEquals(3, viewModel.state.first { it.importsMade == 3 }.importsMade)
    }

    /**
     * An import selects what it imported.
     *
     * Nobody pastes an element set in order to *not* track that satellite, and an import that
     * appeared to do nothing was the most confusing outcome this screen could produce.
     */
    @Test
    fun importingSelectsTheSatelliteForTracking() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(TleImportEvent.TextChanged(validText))
        viewModel.onEvent(TleImportEvent.Submitted)
        testScheduler.advanceUntilIdle()

        assertTrue(25544 in selection.selected.value)
    }

    /** And leaves it alone when the user has said not to. */
    @Test
    fun importingWithoutTrackingLeavesTheSelectionAlone() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(TleImportEvent.TrackAfterImportChanged(false))
        viewModel.onEvent(TleImportEvent.TextChanged(validText))
        viewModel.onEvent(TleImportEvent.Submitted)
        testScheduler.advanceUntilIdle()

        assertTrue(selection.selected.value.isEmpty())
    }
}
