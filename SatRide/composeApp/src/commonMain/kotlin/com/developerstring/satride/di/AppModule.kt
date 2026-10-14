package com.developerstring.satride.di

import com.developerstring.satride.core.analytics.analyticsModule
import com.developerstring.satride.core.auth.AuthRepository
import com.developerstring.satride.core.auth.authModule
import com.developerstring.satride.core.data.CatalogSync
import com.developerstring.satride.core.data.dataModule
import com.developerstring.satride.data.catalogSnapshotModule
import com.developerstring.satride.data.playAssetsModule
import com.developerstring.satride.feature.arsky.arskyModule
import com.developerstring.satride.feature.auth.authFeatureModule
import com.developerstring.satride.feature.decode.decodeModule
import com.developerstring.satride.feature.onboarding.onboardingModule
import com.developerstring.satride.feature.passes.passesModule
import com.developerstring.satride.feature.play.EARTH_VECTOR_FILES
import com.developerstring.satride.feature.play.EarthTextureSource
import com.developerstring.satride.feature.play.multiplay.multiPlayModule
import com.developerstring.satride.feature.play.playModule
import com.developerstring.satride.feature.profile.profileModule
import com.developerstring.satride.feature.radio.radioModule
import com.developerstring.satride.feature.satellites.satellitesModule
import com.developerstring.satride.feature.settings.settingsModule
import com.developerstring.satride.feature.startup.StartupCoordinator
import com.developerstring.satride.feature.startup.StartupStep
import com.developerstring.satride.feature.startup.startupModule
import com.developerstring.satride.ui.debug.TrackingDebugViewModel
import kotlinx.coroutines.flow.first
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Marks the app shell itself as wired. Screens resolve this to prove DI reached them, which
 * keeps the graph honest before real dependencies exist.
 */
data class AppInfo(val name: String, val version: String)

/**
 * The app's identity, in one place.
 *
 * [APP_VERSION] has to be kept level with `versionName` in `androidApp/build.gradle.kts` and the
 * iOS target's marketing version. It is duplicated rather than read from the platform because
 * common code has no portable way to ask, and the About section showing a different number from
 * the store listing is the kind of thing that makes a bug report impossible to place.
 */
const val APP_NAME: String = "SatRide"
const val APP_VERSION: String = "1.0.1"

/**
 * The release date shown on the About screen, bumped with [APP_VERSION].
 *
 * A constant rather than a build timestamp on purpose. A timestamp baked in at compile time
 * makes every build a different artifact, which defeats build caching and means two builds of
 * identical source are not identical. What a user wants from "last updated" is the release
 * date anyway, not the minute the binary happened to be compiled.
 */
const val APP_BUILD_DATE: String = "21 August 2026"

val appModule: Module =
    module {
        single { AppInfo(name = APP_NAME, version = APP_VERSION) }
        viewModelOf(::TrackingDebugViewModel)

        /*
         * The startup step map.
         *
         * Assembled here because only the app shell can see every feature: warming the globe reads
         * `feature:play`'s assets, and a feature module may not depend on another feature.
         *
         * Each step is independently failable — see StartupCoordinator — so nothing in here needs
         * to defend itself. A failed element-set refresh is not a failed startup; the bundled
         * catalog is three years of satellites and works with no network at all.
         */
        single {
            val catalogSync = get<CatalogSync>()
            val textures = get<EarthTextureSource>()
            val auth = get<AuthRepository>()

            StartupCoordinator(
                mapOf(
                    // Seeds from the bundle if empty. Local and fast; the sync also refreshes
                    // element sets, which is the slow part and the reason for the cap.
                    StartupStep.CATALOG to {
                        catalogSync.sync()
                        Unit
                    },

                    // Reading the vector Earth off disk now means the Play tab does not stall the
                    // first time it is opened. Coarsest LOD only: the finer tiers are megabytes and
                    // are not needed until the camera actually flies in.
                    StartupStep.GLOBE to {
                        textures.read(EARTH_VECTOR_FILES.first())
                        Unit
                    },

                    // Restoring a Firebase session is asynchronous, and the profile tab shows a
                    // shimmer until it resolves. Waiting for it here means the tab is right the
                    // first time it is opened rather than correcting itself a frame later.
                    StartupStep.SESSION to {
                        auth.state.first { it.isResolved }
                        Unit
                    },
                ),
            )
        }
    }

/**
 * Every module the app needs. Each core and feature module owns its own Koin module and is
 * listed here exactly once — this is the only place that knows the full graph.
 */
fun satRideModules(): List<Module> = listOf(
    appModule,
    // The data layer pulls in networking, persistence and preferences itself.
    catalogSnapshotModule(),
    playAssetsModule(),
    dataModule(),
    analyticsModule(),
    // Features
    onboardingModule,
    authModule,
    authFeatureModule,
    profileModule,
    startupModule,
    arskyModule,
    satellitesModule,
    passesModule,
    playModule,
    multiPlayModule,
    radioModule,
    decodeModule,
    settingsModule(appName = APP_NAME, appVersion = APP_VERSION),
)

/**
 * Shared Koin startup.
 *
 * [platformConfig] lets Android contribute `androidContext(...)` without the common code
 * knowing about Android; iOS and desktop pass nothing.
 */
fun initKoin(platformConfig: KoinApplication.() -> Unit = {}) {
    startKoin {
        platformConfig()
        modules(satRideModules())
    }
}
