package com.developerstring.satride.feature.passes

import com.developerstring.satride.feature.passes.detail.PassDetailViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Instant

/** Koin definitions owned by this feature. Aggregated by composeApp's app module. */
val passesModule: Module = module {
    // Written out rather than `viewModelOf(::PassesViewModel)`. The constructor DSL resolves
    // *every* parameter from the graph, including ones that have default values — so it demanded
    // a `TimeZone` binding that nothing provides and the Passes tab died on open with
    // `NoDefinitionFoundException`. Naming the arguments lets `timeZone` keep its default, which
    // is the system zone and is exactly what the screen wants.
    viewModel {
        PassesViewModel(
            passRepository = get(),
            satelliteRepository = get(),
            transmitterRepository = get(),
            tracker = get(),
            alertScheduler = get(),
            passCoverage = get<com.developerstring.satride.core.data.PassPrecomputeCoordinator>().coverage,
            clock = get(),
            dispatchers = get(),
        )
    }

    // The satellite and its AOS come from the route, so they are runtime parameters rather than
    // dependencies — the same arrangement the satellite detail screen uses.
    viewModel { parameters ->
        PassDetailViewModel(
            noradId = parameters.get(),
            aos = Instant.fromEpochMilliseconds(parameters.get()),
            passRepository = get(),
            satelliteRepository = get(),
            transmitterRepository = get(),
            tracker = get(),
            alertScheduler = get(),
            guideRequests = get(),
            clock = get(),
            dispatchers = get(),
        )
    }
}
