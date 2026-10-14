package com.developerstring.satride.navigation

import kotlinx.serialization.Serializable

/**
 * Every destination in SatRide.
 *
 * Type-safe routes: arguments are constructor parameters, so a navigation call that forgets an
 * argument fails to compile rather than at runtime. Deep links (P6) map onto these same types.
 */
sealed interface SatRideRoute {
    /** Top-level tabs. */
    sealed interface Tab : SatRideRoute

    @Serializable
    data object Sky : Tab

    @Serializable
    data object Satellites : Tab

    @Serializable
    data object Passes : Tab

    @Serializable
    data object Play : Tab

    @Serializable
    data object Profile : Tab

    @Serializable
    data object Radio : Tab

    @Serializable
    data class SatelliteDetail(val noradId: Int) : SatRideRoute

    /**
     * Multi-Play: the landscape, full-screen arena reached from the Play tab.
     *
     * Not a tab. It locks the orientation and hides every bit of chrome while it is up, which is
     * exactly what a tab must never do; it is pushed over Play and popped back to it.
     */
    @Serializable
    data class MultiPlay(
        /** A game mode id to open on, e.g. "solar"; null lands on the Multi-Play home as usual. */
        val mode: String? = null,
    ) : SatRideRoute

    /**
     * One pass, identified the way the cache identifies it.
     *
     * Two numbers rather than an opaque id, because a pass is not a stored object with a lifetime —
     * it is recomputed daily and its cache row is pruned when its element set is superseded. A
     * route holding a row id would go dangling within a day; one holding the satellite and the time
     * can look the pass up again, and say so honestly when it has genuinely stopped existing.
     */
    @Serializable
    data class PassDetail(val noradId: Int, val aosEpochMillis: Long) : SatRideRoute

    /** [mode] matches `DecodeMode` once decoders land in P10. */
    @Serializable
    data class Decode(val mode: String) : SatRideRoute

    @Serializable
    data object Gallery : SatRideRoute

    @Serializable
    data object Settings : SatRideRoute

    /**
     * Choosing which satellites the app tracks.
     *
     * Pushed rather than a tab: it is a settings-shaped screen reached from the catalog, and it is
     * where the single most consequential choice in the app is made — the size of the tracked set
     * decides what every other screen costs to draw.
     */
    @Serializable
    data object Tracking : SatRideRoute

    /** Pasting in element sets the catalogs do not carry yet. */
    @Serializable
    data object TleImport : SatRideRoute

    @Serializable
    data object Onboarding : SatRideRoute

    /**
     * The one screen that asks for location, camera and notifications, between sign-in and the
     * app proper. Shown once ever — see `UserSettings.permissionsPrimed`.
     */
    @Serializable
    data object Permissions : SatRideRoute

    /**
     * The first destination, and the only one that is not reachable by navigating *to* it.
     *
     * It pops itself off the back stack on handoff, so the system back button from the home screen
     * exits the app rather than returning to a splash that has nothing left to do.
     */
    @Serializable
    data object Splash : SatRideRoute

    /** Optional. Nothing behind it is gated — see `docs/dev/phase-15-identity-profile.md`. */
    @Serializable
    data object SignIn : SatRideRoute

    @Serializable
    data object CreateProfile : SatRideRoute

    /**
     * Email-and-password sign-in for reviewers and internal testers.
     *
     * Declared to Google in the App access form rather than hidden, so it is an ordinary route
     * reachable from Settings. See `TesterSignInScreen`.
     */
    @Serializable
    data object TesterSignIn : SatRideRoute

    @Serializable
    data object About : SatRideRoute

    /**
     * A page from the SatRide site, rendered inside the app.
     *
     * [title] rides along so the top bar can be labelled before the page has loaded — a bar that
     * fills in its own title two seconds late reads as a glitch.
     */
    @Serializable
    data class WebPage(val url: String, val title: String) : SatRideRoute

    /** Internal: the design-system gallery. Not reachable from a shipping build (P13). */
    @Serializable
    data object DesignPreview : SatRideRoute

    /** Internal: live sensors and tracking engine, for checking against a reference tracker. */
    @Serializable
    data object TrackingDebug : SatRideRoute
}

/*
 * The deep-link pattern moved to `core:common/SkyDeepLink` in P6.
 *
 * It has to be readable from places that are not in the navigation graph at all — the Android
 * activity's intent, iOS's `onOpenURL`, and the notification builder inside `core:notifications` —
 * so a constant in the app shell was the wrong home for it. Handling goes through
 * `SkyGuideRequests` rather than through Navigation Compose's own deep-link support, because the
 * target is a *tab* that must not gain a second back-stack entry every time a notification is
 * tapped.
 */
