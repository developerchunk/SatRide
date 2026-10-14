package com.developerstring.satride.di

import com.developerstring.satride.core.data.CatalogSnapshotSource
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import kotlinx.datetime.TimeZone
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Every definition in the graph can actually be built.
 *
 * `AppModuleTest` proves the graph *starts*; it resolves one binding and stops there, so a view
 * model whose constructor asks for a type nobody provides sails through it and dies the moment the
 * user opens that tab. That is not hypothetical — it is exactly how P6's Passes tab shipped into
 * an emulator: `viewModelOf(::PassesViewModel)` resolves *every* constructor parameter from the
 * graph including ones with default values, so it demanded a `kotlinx.datetime.TimeZone` binding
 * and crashed with `NoDefinitionFoundException` on first open.
 *
 * `verify()` reads constructor signatures by reflection and checks each parameter against the
 * graph, without instantiating anything — so this costs no database, no network client and no
 * DataStore file, and it fails at build time instead of on a device.
 *
 * JVM-only because it is reflective. The definitions it checks are common code, so checking them
 * once is enough.
 */
@OptIn(KoinExperimentalAPI::class)
class ModuleGraphVerificationTest {

    @Test
    fun everyDefinitionsDependenciesArePresentInTheGraph() {
        // Verified as one assembled graph rather than module by module. `verify()` checks a
        // module against its own definitions, and this project's whole arrangement is that a
        // feature's view models are satisfied by `core:data` — so per-module checking reports
        // every cross-module dependency as missing and says nothing about the real graph.
        val wholeGraph = module { satRideModules().forEach { includes(it) } }

        wholeGraph.verify(
            extraTypes = listOf(
                // Supplied per platform by the app entry point rather than by a common module,
                // so it is genuinely absent from the graph this test can see.
                CatalogSnapshotSource::class,
                // Parameters the routes pass at call time (`parametersOf(noradId, aos)`), which
                // by definition are not graph bindings.
                Int::class,
                Long::class,
                Instant::class,
                // Reachable only through a constructor default; listed so a *new* dependency on
                // it would still have to be a deliberate choice rather than a silent crash.
                // `verify()` reflects on a definition's *declared type* and cannot tell a
                // constructor-DSL definition from a lambda one. `HttpClient` is built inside a
                // lambda by `SatRideHttpClient.create`, so its own constructor parameters are not
                // graph bindings and never were.
                HttpClientEngine::class,
                HttpClientConfig::class,
                // The startup step map is assembled in a lambda by the app shell — see AppModule —
                // so `Map` is a constructor parameter that was never meant to be a graph binding.
                Map::class,
            ),
        )
    }
}
