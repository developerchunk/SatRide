package com.developerstring.satride.di

import com.developerstring.satride.feature.passes.PassesViewModel
import com.developerstring.satride.feature.satellites.SatellitesViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Every tab's view model can actually be built from the real graph.
 *
 * This is the test P6 was missing, and its absence cost an emulator run to find:
 * `viewModelOf(::PassesViewModel)` resolves *every* constructor parameter from the graph including
 * ones that have default values, so it demanded a `kotlinx.datetime.TimeZone` binding that nothing
 * provides. The Passes tab died with `NoDefinitionFoundException` the first time it was opened.
 *
 * Neither of the two guards that existed would have caught it. `AppModuleTest` resolves one
 * binding and stops. `ModuleGraphVerificationTest` reflects constructor signatures — and was tried
 * against this exact bug, reintroduced deliberately, and stayed green. So the only thing that
 * proves a view model is constructible is constructing it.
 *
 * JVM only: it builds the real data layer, which is the point — a fake graph would prove nothing
 * about the graph the app ships with.
 */
class ViewModelResolutionTest {

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun everyTabsViewModelResolves() {
        val koin = startKoin { modules(satRideModules()) }.koin

        // The parameterless ones. The detail screens take route arguments and are covered by their
        // own feature tests, which construct them directly.
        assertNotNull(koin.get<SatellitesViewModel>(), "the catalog tab cannot be opened")
        assertNotNull(koin.get<PassesViewModel>(), "the passes tab cannot be opened")
    }
}
