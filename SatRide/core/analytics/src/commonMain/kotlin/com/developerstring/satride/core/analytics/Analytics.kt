package com.developerstring.satride.core.analytics

/**
 * Product analytics: what people actually do with the app.
 *
 * Deliberately a small, closed vocabulary ([SkyEvent]) rather than free-form strings. Event names
 * typed at each call site drift — `pass_reminder_set`, `passReminderSet`, `set_pass_reminder` — and
 * by the time anybody notices, the funnel has three names for one thing and no history for any of
 * them. Firebase also silently caps the number of distinct event names per project, so a typo is
 * not merely untidy, it consumes a slot permanently.
 *
 * **Nothing identifying goes through here.** No coordinates, no email, no satellite the user is
 * watching tied to who they are. Parameters are bounded, low-cardinality facts about *what*
 * happened, never *whose* it was — which is what keeps the data-safety declaration honest and
 * short.
 */
interface Analytics {

    fun log(event: SkyEvent, params: Map<String, String> = emptyMap())

    /** Records the screen the user is now looking at. */
    fun screen(name: String)

    /**
     * Sets a durable, non-identifying property used to segment sessions.
     *
     * Values are things like "granted"/"denied", never a user id.
     */
    fun property(name: String, value: String)
}

/** Every event the app reports. Adding one here is the only way to report a new one. */
enum class SkyEvent(val key: String) {
    AppOpened("app_opened"),
    OnboardingCompleted("onboarding_completed"),
    OnboardingSkipped("onboarding_skipped"),
    PermissionsPrimed("permissions_primed"),
    PermissionAnswered("permission_answered"),
    WalkthroughCompleted("walkthrough_completed"),
    WalkthroughSkipped("walkthrough_skipped"),
    SignedIn("signed_in"),
    SignedOut("signed_out"),
    CatalogSegmentSelected("catalog_segment_selected"),
    SatelliteOpened("satellite_opened"),
    SkyViewOpened("sky_view_opened"),
    CameraEnabled("camera_enabled"),
    GuideStarted("guide_started"),
    PassReminderSet("pass_reminder_set"),
    TleImported("tle_imported"),
    TleRefreshFailed("tle_refresh_failed"),
}

/**
 * Crash and non-fatal reporting.
 *
 * [log] and [setKey] are breadcrumbs: they do nothing on their own, and are attached to whatever
 * crash happens next. That is the whole value — a stack trace tells you where it broke, and the
 * breadcrumbs tell you what the user was doing when it did.
 */
interface CrashReporter {

    /** A breadcrumb attached to the next crash report. */
    fun log(message: String)

    /** A key/value attached to every subsequent report. Non-identifying, same rule as [Analytics]. */
    fun setKey(key: String, value: String)

    /**
     * Reports something that was caught and handled.
     *
     * Worth doing for the failures the app recovers from silently — a catalog refresh that gave up,
     * a decode that produced nothing — because those never crash and so never otherwise appear.
     */
    fun recordNonFatal(error: Throwable)

    /**
     * Associates reports with the signed-in account, or clears it on sign-out.
     *
     * The Firebase uid, not an email: enough to recognise the same person across two reports,
     * useless to anyone reading the console for anything else.
     */
    fun setUserId(id: String?)
}

/** Used on platforms with no backend wired, and in tests. */
object NoOpAnalytics : Analytics {
    override fun log(event: SkyEvent, params: Map<String, String>) = Unit
    override fun screen(name: String) = Unit
    override fun property(name: String, value: String) = Unit
}

object NoOpCrashReporter : CrashReporter {
    override fun log(message: String) = Unit
    override fun setKey(key: String, value: String) = Unit
    override fun recordNonFatal(error: Throwable) = Unit
    override fun setUserId(id: String?) = Unit
}
