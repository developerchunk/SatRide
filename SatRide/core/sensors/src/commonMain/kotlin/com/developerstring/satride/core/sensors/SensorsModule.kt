package com.developerstring.satride.core.sensors

import com.developerstring.satride.core.common.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** The platform sensor sources — the only part of this module that differs per target. */
public expect fun platformSensorsModule(): Module

/** Qualifier for the long-lived scope the sensor providers publish their state flows in. */
public val SensorScopeQualifier: org.koin.core.qualifier.Qualifier = named("sensorScope")

public val sensorsModule: Module =
    module {
        includes(platformSensorsModule())

        // Application-lifetime, and a SupervisorJob so a failure in one sensor stream cannot take
        // the other down with it — a compass that stops must not also stop location.
        single(SensorScopeQualifier) {
            CoroutineScope(SupervisorJob() + get<AppDispatchers>().default)
        }

        single<LocationProvider> {
            DefaultLocationProvider(source = get(), scope = get(SensorScopeQualifier))
        }

        single<AttitudeProvider> {
            DefaultAttitudeProvider(
                source = get(),
                locationProvider = get(),
                clock = get(),
                scope = get(SensorScopeQualifier),
            )
        }
    }
