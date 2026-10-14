package com.developerstring.satride.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * A scope that lives as long as the application.
 *
 * For work that has to outlive whatever screen started it — a catalog refresh the user began and
 * then navigated away from, a sensor stream shared between screens. A `viewModelScope` would
 * cancel these the moment the user moved, abandoning the work halfway and leaving them to come
 * back to a spinner that had achieved nothing.
 */
val AppScopeQualifier: Qualifier = named("appScope")

/** Primitives every other module injects: dispatchers, the clock, and the application scope. */
val commonModule: Module =
    module {
        single<AppDispatchers> { DefaultAppDispatchers() }

        // Eager, and deliberately the first thing created: it timestamps the launch, and a lazily
        // created one would timestamp whenever something first asked — which is exactly the bias
        // it exists to remove.
        single(createdAtStart = true) { AppStartMark() }
        single<Clock> { Clock.System }

        // Application-scoped by necessity: a deep link is delivered to the activity before any
        // screen exists to hear it, and the request has to survive until the sky view composes.
        single { DefaultSkyGuideRequests() }
        single<SkyGuideRequests> { get<DefaultSkyGuideRequests>() }

        // SupervisorJob so one failed background job cannot take the others down with it.
        single(AppScopeQualifier) {
            CoroutineScope(SupervisorJob() + get<AppDispatchers>().default)
        }
    }
