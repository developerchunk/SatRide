package com.developerstring.satride.ui

/**
 * The seam between Compose's navigation and the native iOS tab bar.
 *
 * Compose owns the NavHost and the source of truth for the active tab; the SwiftUI Liquid Glass bar
 * in `ContentView.swift` only reflects and drives it. This object is the whole bridge — no state
 * beyond two lambdas, so there is nothing that can get out of sync:
 *
 * - [publishActive] is called by `SatRideNavHost` whenever the active tab changes, and fans out to
 *   [onActiveTabChange], which Swift registers through `MainViewControllerKt.setNativeTabObserver`.
 * - [select] is called by Swift on a tab tap, and fans out to the handler `SatRideNavHost` bound
 *   through [bindSelect], which runs the existing `navController.navigateToTab`.
 *
 * Kept `internal` so it never reaches the Objective-C surface itself; only the three plain
 * `public` functions in `MainViewController.kt` (`setNativeTabObserver`, `selectNativeTab`,
 * `nativeTabLabels`) cross to Swift. That mirrors the `setGoogleSignInHandler` pattern.
 */
internal object NativeTabBridge {
    /**
     * Set from Swift; invoked from Compose with the active tab index, or `-1` when the current
     * destination is not a top-level tab — which is the native bar's signal to hide itself.
     */
    var onActiveTabChange: ((Int) -> Unit)? = null

    /**
     * The last index [publishActive] reported, so an observer registering late (Swift's `onAppear`
     * can fire after Compose's first publish) is told the current tab instead of waiting for the
     * next navigation.
     */
    private var lastPublishedIndex: Int = -1

    private var selectHandler: ((Int) -> Unit)? = null

    /** Swift → Compose: a tab was tapped. No-op until [bindSelect] has wired a handler. */
    fun select(index: Int) {
        selectHandler?.invoke(index)
    }

    /** Compose → registers the navigator's tab-switch entry point. */
    internal fun bindSelect(handler: (Int) -> Unit) {
        selectHandler = handler
    }

    /** Compose → Swift: the active tab changed (or `-1` for a non-tab destination). */
    internal fun publishActive(index: Int) {
        lastPublishedIndex = index
        onActiveTabChange?.invoke(index)
    }

    /**
     * Swift registers its observer here; it is immediately replayed the current tab so the native
     * bar does not flash unselected on the first frame.
     */
    internal fun registerObserver(handler: (Int) -> Unit) {
        onActiveTabChange = handler
        handler(lastPublishedIndex)
    }
}
