package com.developerstring.satride

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.rememberNavController
import co.touchlab.kermit.Logger
import com.developerstring.satride.core.auth.DesktopFirebase
import com.developerstring.satride.core.auth.DesktopFirebaseConfig
import com.developerstring.satride.core.data.CatalogSync
import com.developerstring.satride.core.data.TrackingEngine
import com.developerstring.satride.core.data.TrackingSelectionRepository
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.core.datastore.TrackingMode
import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.notifications.DefaultAlertScheduler
import com.developerstring.satride.core.orbit.doppler.Doppler
import com.developerstring.satride.di.initKoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import java.awt.Dimension
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Desktop entry point.
 *
 * The same shared UI as the phones, in a window. What differs is only what a desktop physically
 * lacks: there is no compass, so the sky view is a planetarium you drag and zoom rather than a
 * camera overlay; location comes from the network connection instead of a GPS; and pass alerts
 * are delivered through the system's notification centre while the app is open. Everything else
 * — the catalog, tracking, passes, the globe, sign-in and the profile — is the common code.
 *
 * ## Configuration
 *
 * - `google-services.json` (bundled by the build when present, or dropped into `~/.satride/`) starts
 *   Firebase, which is what makes sign-in and the profile work. Without it the app runs signed out.
 * - A Google OAuth *Desktop app* client id enables the sign-in button itself; see
 *   `DesktopOAuthClient` in `feature:auth` for the three places it is read from.
 *
 * ## Developer flags
 *
 * - `--observer=lat,lon` stores a manual observer position and switches to manual mode.
 * - `--force-sync` runs a full catalog refresh against the live endpoints before the window opens,
 *   printing what each source returned. The normal path skips a refresh while the data is inside
 *   its interval, so this is the check to run after touching anything in `core:network`.
 * - `--track` prints a few seconds of the tracking engine's output and exits the check.
 * - `--track-mode=curated|selected|all` sets what the engine tracks, so the scaling behaviour can
 *   be exercised against the real catalog rather than only in tests. `all` also opts into the full
 *   catalog download, exactly as the tracking screen's "show all possible satellites" does.
 */
fun main(args: Array<String>) {
    initializeFirebase()
    initKoin()

    args.firstOrNull { it.startsWith("--observer=") }
        ?.removePrefix("--observer=")
        ?.let(::setManualObserver)

    args.firstOrNull { it.startsWith("--track-mode=") }
        ?.removePrefix("--track-mode=")
        ?.let(::setTrackingMode)

    if ("--force-sync" in args) {
        forceSync()
    }

    if ("--track" in args) {
        printTracking()
    }

    rearmPassAlerts()

    application {
        val windowState = rememberWindowState(
            width = 420.dp,
            height = 900.dp,
            // Centred rather than wherever the window manager felt like: a phone-shaped window
            // dropped in a corner of a large monitor reads as a mistake.
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "SatRide",
            icon = windowIcon(),
            state = windowState,
        ) {
            // Below this the bottom bar's five tabs start colliding with each other.
            window.minimumSize = Dimension(360, 600)

            val navController = rememberNavController()
            Box(
                Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                    // Escape is desktop's back gesture. `onPreviewKeyEvent` so it works wherever
                    // focus happens to be — a globe or sky surface that has taken focus for its own
                    // key handling must not swallow it.
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        navController.popBackStack()
                    } else {
                        false
                    }
                },
            ) {
                App(navController = navController)
            }
        }
    }
}

/** The app's data directory, shared with the database and preferences. */
private val dataDir: File = File(System.getProperty("user.home"), ".satride")

/**
 * Starts Firebase from `google-services.json`, if one can be found.
 *
 * Before Koin, because `authModule` picks the real or the unavailable repository at construction.
 * Not finding a file is an expected outcome and logged as one line, not a stack trace.
 */
private fun initializeFirebase() {
    val logger = Logger.withTag("Desktop")
    val json = readGoogleServicesJson()
    if (json == null) {
        logger.i { "no google-services.json bundled or in $dataDir; running signed out" }
        return
    }
    val config = DesktopFirebaseConfig.fromGoogleServicesJson(json)
    if (config == null) {
        logger.w { "google-services.json could not be parsed; running signed out" }
        return
    }
    DesktopFirebase.initialize(config, dataDir)
}

private fun readGoogleServicesJson(): String? =
    object {}.javaClass.getResourceAsStream("/google-services.json")?.use { it.readBytes().decodeToString() }
        ?: File(dataDir, "google-services.json").takeIf { it.isFile }?.readText()

/**
 * Rebuilds the pass-alert timers from the store.
 *
 * The desktop scheduler keeps its timers in memory, so every launch starts with none; the store
 * is what remembers what the user asked for. Same job as Android's boot receiver, same call.
 */
private fun rearmPassAlerts() {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        runCatching { GlobalContext.get().get<DefaultAlertScheduler>().rearmAll() }
            .onFailure { Logger.withTag("Desktop").w(it) { "could not re-arm pass alerts" } }
    }
}

/** The generated icon, from the classpath; null (the platform default) if the build has none. */
private fun windowIcon(): Painter? =
    runCatching { BitmapPainter(useResource("icons/satride.png", ::loadImageBitmap)) }.getOrNull()

private fun setManualObserver(raw: String) = runBlocking {
    val parts = raw.split(',').mapNotNull { it.trim().toDoubleOrNull() }
    if (parts.size < 2) {
        println("--observer expects lat,lon — got '$raw'")
        return@runBlocking
    }
    GlobalContext.get().get<SettingsRepository>().setManualPosition(GeoPos(parts[0], parts[1]))
    println("observer set to ${parts[0]}, ${parts[1]}")
}

/**
 * Prints a few seconds of the tracking engine's output.
 *
 * The headless twin of the tracking-debug screen. Desktop has no compass, so this covers the
 * observer/propagation/interpolation half of P3 — the attitude half can only honestly be checked
 * on a device with a magnetometer.
 */
private fun setTrackingMode(raw: String) = runBlocking {
    val mode = TrackingMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    if (mode == null) {
        println("--track-mode expects curated, selected or all — got '$raw'")
        return@runBlocking
    }
    GlobalContext.get().get<TrackingSelectionRepository>().setMode(mode)
    println("tracking mode set to ${mode.name.lowercase()}")
}

private fun printTracking() = runBlocking {
    val engine = GlobalContext.get().get<TrackingEngine>()

    println("--- tracking engine ---")
    val job = launch { engine.sky.collect { } }

    // Two sweeps' worth: enough for the engine to find what is up and to build the second fix
    // that interpolation needs.
    delay(4_000)

    val sky = engine.sky.value
    println("observer: ${engine.observer.value}")
    println("tracked: ${sky.size}  status: ${engine.status.value}")
    sky.upNow(limit = 8).mapNotNull { sky.snapshotOf(it) }.forEach {
        val doppler = Doppler.shiftHz(145_800_000L, it.topo.rangeRateKmS)
        println(
            "  ${it.noradId}  az ${it.topo.azimuthDeg.roundTo(1)}  el ${it.topo.elevationDeg.roundTo(1)}  " +
                "${it.topo.rangeKm.roundTo(0)} km  rdot ${it.topo.rangeRateKmS.roundTo(3)} km/s  " +
                "doppler ${doppler.toLong()} Hz  ${if (it.sunlit) "sunlit" else "eclipsed"}",
        )
    }

    // Interpolation has to move between fixes, not sit on one of them.
    (0 until sky.size).firstOrNull { sky.hasPositionAt(it) }?.let { index ->
        val noradId = sky.idAt(index)
        val now = Clock.System.now()
        val a = engine.interpolated(noradId, now)
        val b = engine.interpolated(noradId, now + 500.milliseconds)
        println("interpolation $noradId: el ${a?.elevationDeg?.roundTo(4)} -> ${b?.elevationDeg?.roundTo(4)}")
    }

    job.cancel()
}

private fun Double.roundTo(decimals: Int): Double {
    var scale = 1.0
    repeat(decimals) { scale *= 10.0 }
    return kotlin.math.round(this * scale) / scale
}

private fun forceSync() = runBlocking {
    val result = GlobalContext.get().get<CatalogSync>().sync(force = true)

    println("--- forced catalog sync ---")
    println("seeded: ${result.seeded?.satellites ?: 0} satellites")
    result.refresh?.sources?.forEach { source ->
        val outcome = source.error?.let { "FAILED: ${it.message}" } ?: "${source.fetched} element sets"
        println("  ${source.label.padEnd(28)} $outcome")
    }
    println("stored: ${result.refresh?.tlesUpdated ?: 0} element sets")
    println("transmitters: ${result.refresh?.transmittersUpdated ?: 0}")
    println("passes computed: ${result.passesComputed}")
}
