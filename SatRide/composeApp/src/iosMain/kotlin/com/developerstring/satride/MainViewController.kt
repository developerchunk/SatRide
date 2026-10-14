package com.developerstring.satride

import androidx.compose.ui.window.ComposeUIViewController
import com.developerstring.satride.core.common.SkyDeepLink
import com.developerstring.satride.core.common.SkyGuideRequests
import com.developerstring.satride.di.initKoin
import com.developerstring.satride.feature.auth.IosGoogleSignIn
import com.developerstring.satride.navigation.TopLevelTab
import com.developerstring.satride.ui.NativeTabBridge
import org.koin.mp.KoinPlatformTools

/** Entry point called from Swift (`ContentView.swift`). */
fun MainViewController() = ComposeUIViewController { App() }

/**
 * Starts DI from the iOS app before the first composition.
 *
 * Exposed as a plain no-argument function because that maps cleanly to Swift as
 * `MainViewControllerKt.doInitKoin()`; `initKoin`'s default-argument form does not.
 */
fun doInitKoin() {
    initKoin()
}

/**
 * Handles a `satride://` URL, from Swift's `onOpenURL`.
 *
 * iOS has no equivalent of an intent filter that Compose can read, so the URL comes in through the
 * SwiftUI scene and is handed to the same application-scoped bus the Android activity uses. The
 * parsing itself is common code, which is the point: a URL scheme implemented twice is a URL
 * scheme that diverges, and the failure is silent — the notification simply opens the app on the
 * wrong screen, on one platform.
 *
 * Returns true when the link was one of ours, so the Swift side can tell handled from ignored.
 */
fun handleDeepLink(url: String): Boolean {
    val target = SkyDeepLink.parse(url) ?: return false
    val requests = KoinPlatformTools.defaultContext().get().get<SkyGuideRequests>()
    requests.request(noradId = target.noradId, guide = target.guide)
    return true
}

// The native (SwiftUI Liquid Glass) tab bar's bridge to Compose navigation.
//
// Three plain functions rather than a richer object because each crosses the Objective-C bridge to
// Swift as `MainViewControllerKt.foo()` -- the same shape as `setGoogleSignInHandler`. The state
// itself lives in `NativeTabBridge` (Kotlin-internal), so Swift only ever calls in through these.

/**
 * Registers the native bar's observer and immediately replays the current tab, so the bar does not
 * flash unselected on the first frame. Compose invokes `handler` with the active tab index on every
 * navigation change, or `-1` when the destination is not a tab -- the bar's cue to hide itself.
 */
fun setNativeTabObserver(handler: (Int) -> Unit) {
    NativeTabBridge.registerObserver(handler)
}

/** Swift → Compose: a tab was tapped. Routes to the same `navigateToTab` the Compose bar uses. */
fun selectNativeTab(index: Int) {
    NativeTabBridge.select(index)
}

/** The tab labels in order, for the native bar to render, sourced from `TopLevelTab`. */
fun nativeTabLabels(): List<String> = TopLevelTab.visible.map { it.label }

/**
 * Lets Swift supply the Google sign-in flow.
 *
 * `IosGoogleSignIn` lives in `feature:auth`, which is an `implementation` dependency of this
 * module and therefore *not* in the framework's Objective-C header — Swift cannot see it. Widening
 * the export surface to fix that would publish an entire feature module's API to Swift for the sake
 * of one property. This forwards instead, which keeps the framework's surface to the three
 * functions the iOS app actually calls.
 *
 * [handler] is invoked with a completion taking `(idToken, accessToken, errorDescription)`: either
 * the first two are set or the last one is. Pass [googleSignInCancelledSentinel] as the error when the user dismisses the chooser,
 * so the app can stay silent rather than reporting a fault the user caused on purpose.
 */
fun setGoogleSignInHandler(handler: (completion: (String?, String?, String?) -> Unit) -> Unit) {
    IosGoogleSignIn.handler = handler
}

/** The string Swift must report for a user-cancelled sign-in. */
fun googleSignInCancelledSentinel(): String = IosGoogleSignIn.CANCELLED
