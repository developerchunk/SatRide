package com.developerstring.satride

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import co.touchlab.kermit.Logger
import com.developerstring.satride.core.analytics.Analytics
import com.developerstring.satride.core.analytics.CrashReporter
import com.developerstring.satride.core.analytics.SkyEvent
import com.developerstring.satride.core.auth.AuthRepository
import com.developerstring.satride.core.data.CatalogSync
import com.developerstring.satride.core.data.PassPrecomputeCoordinator
import com.developerstring.satride.core.designsystem.haptics.LocalSatRideHaptics
import com.developerstring.satride.core.designsystem.haptics.SatRideHaptics
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.sensors.Haptics
import com.developerstring.satride.navigation.SatRideNavHost
import org.koin.compose.koinInject

/**
 * The whole SatRide UI. Every platform entry point hosts exactly this.
 *
 * Night mode becomes a persisted setting in P11; until then the standard palette is used.
 */
@Composable
fun App(
    nightMode: Boolean = false,
    /**
     * Injectable so a platform shell can drive navigation itself — desktop binds Escape to back,
     * which needs the same controller the nav host is using.
     */
    navController: NavHostController = rememberNavController(),
) {
    CatalogSyncEffect()
    ReportingEffect()

    // Adapts the hardware peripheral (core:sensors) into the design system's semantic haptic
    // events, so buttons and tabs can click without the design system knowing about hardware.
    val hardware: Haptics = koinInject()
    val uiHaptics = remember(hardware) {
        object : SatRideHaptics {
            override fun tap() = hardware.tick()
            override fun press() = hardware.tick()
            override fun success() = hardware.lock()
        }
    }

    SatRideTheme(nightMode = nightMode) {
        CompositionLocalProvider(LocalSatRideHaptics provides uiHaptics) {
            SatRideNavHost(navController = navController)
        }
    }
}

/**
 * Seeds the bundled catalog and refreshes it, once per launch.
 *
 * Runs here rather than from a background scheduler: a satellite tracker is opened deliberately,
 * so "refresh when the user arrives" is both sufficient and immune to the background-execution
 * rules that keep changing underneath every platform.
 *
 * Nothing is awaited before the UI draws. The catalog is already in the database — seeded on the
 * very first launch — so every screen has data to show while this runs.
 */
@Composable
private fun CatalogSyncEffect(
    sync: CatalogSync = koinInject(),
    passPrecompute: PassPrecomputeCoordinator = koinInject(),
) {
    LaunchedEffect(Unit) {
        val result = sync.sync()
        Logger.i(tag = "SatRide") {
            "catalog sync: seeded=${result.seeded?.satellites ?: 0} " +
                "refreshed=${result.refresh?.tlesUpdated ?: 0} " +
                "transmitters=${result.refresh?.transmittersUpdated ?: 0} " +
                "passes=${result.passesComputed}"
        }
    }

    // Separate effect, never-ending: recomputes the pass timeline whenever the observer's
    // position first becomes known (a fresh install grants location *after* the startup sync
    // above has already run and computed nothing) or moves to a new cache cell.
    LaunchedEffect(Unit) {
        passPrecompute.watch()
    }
}

/**
 * The session-level reporting: one open event, and the account a crash belongs to.
 *
 * The user id is the Firebase uid and nothing else — enough to tell two reports from the same
 * person apart, useless for anything more. It is cleared on sign-out rather than left behind,
 * because a crash after signing out does not belong to that account.
 */
@Composable
private fun ReportingEffect(
    analytics: Analytics = koinInject(),
    crashReporter: CrashReporter = koinInject(),
    auth: AuthRepository = koinInject(),
) {
    LaunchedEffect(Unit) {
        analytics.log(SkyEvent.AppOpened)
    }

    LaunchedEffect(Unit) {
        auth.state.collect { state ->
            val uid = state.userOrNull?.uid
            crashReporter.setUserId(uid)
            analytics.property("signed_in", if (uid == null) "false" else "true")
        }
    }
}
