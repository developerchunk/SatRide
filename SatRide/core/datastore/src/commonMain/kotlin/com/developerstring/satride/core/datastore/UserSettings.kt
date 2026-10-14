package com.developerstring.satride.core.datastore

import com.developerstring.satride.core.model.GeoPos
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** How SatRide decides where the user is. */
enum class ObserverMode {
    /** Follow the device's location provider. */
    AUTOMATIC,

    /**
     * Use a position the user typed in.
     *
     * Not a fallback but a real choice: plenty of people plan a session from home for a site they
     * will drive to, and location permission is something a user is entitled to decline outright.
     */
    MANUAL,
}

/**
 * Which satellites the app propagates, draws and predicts passes for.
 *
 * The single most expensive setting in SatRide: it decides how many SGP4 evaluations happen per
 * second, how many rows the catalog sorts, and how many objects the sky renderer projects. So it
 * is a user-owned choice with an honest cost attached rather than something the app decides.
 */
enum class TrackingMode {
    /** The bundled starter set — a few dozen objects. Free, instant, and enough for most people. */
    CURATED,

    /**
     * Exactly what the user ticked on the tracking screen.
     *
     * The interesting mode: a satellite enthusiast's own list, which might be six objects or six
     * thousand, and which the app must handle either way without asking.
     */
    SELECTED,

    /**
     * Every object in the catalog.
     *
     * Tens of thousands after a full sync. Genuinely supported — see [TrackingBudget], which is
     * what stops it from being a promise the device cannot keep — and one tap away, because
     * "show me everything" is a reasonable thing to want and refusing it is not an answer.
     */
    ALL,
}

/**
 * How much work per second the user is willing to spend on tracking.
 *
 * Selecting twenty thousand objects does not mean propagating twenty thousand orbits every
 * second — it means the *selection* is twenty thousand. The budget decides how many of them are
 * live at full rate; the rest are refreshed on a slower rotation, which is invisible because a
 * satellite below the horizon or off-screen cannot be seen to lag.
 *
 * Named for what the user gets, not for a number of objects, because the right number differs by
 * an order of magnitude between a five-year-old phone and a current one.
 */
enum class TrackingBudget(
    /**
     * Objects propagated at the full tick rate.
     *
     * Sized against what a full catalog actually puts above the horizon, which measurement puts at
     * a little over four hundred from a mid-latitude observer with sixteen thousand objects
     * tracked. Below that number the budget would start demoting satellites the user can see into
     * the slow rotation, which is the one thing it must not do — so [BALANCED] clears it with
     * margin, and even [BATTERY] holds the two hundred highest.
     */
    val liveObjects: Int,
    /** Objects refreshed per slow-rotation tick, on top of the live set. */
    val rotationObjects: Int,
    val label: String,
) {
    BATTERY(liveObjects = 200, rotationObjects = 250, label = "Battery saver"),
    BALANCED(liveObjects = 700, rotationObjects = 900, label = "Balanced"),
    PERFORMANCE(liveObjects = 2_000, rotationObjects = 3_000, label = "Maximum"),
    ;

    companion object {
        val default: TrackingBudget = BALANCED
    }
}

enum class UnitSystem { METRIC, IMPERIAL }

/** Automatic gain, or a fixed value the user picked. */
enum class GainMode { AUTOMATIC, MANUAL }

/**
 * Everything the user has chosen, in one value.
 *
 * A single object rather than a flow per key: screens almost always need several settings at
 * once, and combining half a dozen flows per screen produces a burst of recompositions on every
 * change instead of one.
 */
data class UserSettings(
    val onboardingComplete: Boolean = false,

    /**
     * Whether the permission screen has been shown and dismissed.
     *
     * Separate from [onboardingComplete] because it is a separate promise. Onboarding says what
     * the app is; the permission screen asks for three things and must never appear twice —
     * including for the user who declined, since re-asking somebody who said "not now" on every
     * launch is exactly the behaviour that earns a permanent refusal. Whether the permissions were
     * actually *granted* is not recorded here: that is the platform's answer to keep, and caching
     * it would go stale the moment the user changed it in system settings.
     */
    val permissionsPrimed: Boolean = false,

    /** Whether the in-app walkthrough has been finished or skipped. */
    val walkthroughComplete: Boolean = false,
    val observerMode: ObserverMode = ObserverMode.AUTOMATIC,
    /** Only meaningful in [ObserverMode.MANUAL]. */
    val manualPosition: GeoPos? = null,
    val units: UnitSystem = UnitSystem.METRIC,
    val nightMode: Boolean = false,

    /**
     * Refresh interval for element sets.
     *
     * A day is the honest default: SGP4 error grows about 1–3 km per day in low orbit, so daily
     * keeps the AR view well inside its own pointing accuracy, and refreshing more often would
     * put load on volunteer-run servers for accuracy nobody could see.
     */
    val tleRefreshInterval: Duration = 24.hours,
    val autoRefreshTles: Boolean = true,

    /** Passes below this are hidden — a two-degree pass is technically a pass and practically not. */
    val minimumPassElevationDeg: Double = 10.0,

    // --- Tracking selection ---

    /** Which satellites are live. See [TrackingMode]. */
    val trackingMode: TrackingMode = TrackingMode.CURATED,

    /** How much per-second work the tracking engine may spend. See [TrackingBudget]. */
    val trackingBudget: TrackingBudget = TrackingBudget.default,

    /**
     * Whether a full sync pulls CelesTrak's whole active catalog rather than the six curated groups.
     *
     * Off by default because it is several megabytes and most people never need it; turned on
     * automatically the first time the user asks to track everything, since the alternative is a
     * "track all satellites" button that tracks the eight hundred we happen to have downloaded.
     */
    val downloadFullCatalog: Boolean = false,

    /**
     * How many element sets the user has pasted in by hand.
     *
     * Unlimited, and counted only so the import screen can say how many have been added — which is
     * also where a hand-imported satellite that a public catalog has since started carrying would
     * be noticed.
     */
    val customTleImports: Int = 0,

    // --- Radio hardware defaults (P7 onwards) ---
    val gainMode: GainMode = GainMode.AUTOMATIC,
    val manualGainTenthsDb: Int = 300,
    /** Crystal error in parts per million. Cheap dongles are routinely tens of ppm out. */
    val frequencyCorrectionPpm: Int = 0,
    val biasTeeEnabled: Boolean = false,
    /**
     * Previously used `rtl_tcp` endpoints, most recent first.
     *
     * The only path to a dongle on iOS, so remembering it is not a convenience — retyping a
     * host and port before every session is the difference between using the feature and not.
     */
    val rtlTcpHistory: List<String> = emptyList(),

    // --- Multi-Play ---
    /** The name a guest races under; generated once and kept. */
    val multiplayGuestName: String? = null,
    /** A `SatClass` name: the craft the player flies. */
    val multiplaySkin: String? = null,
    /** ARGB of the player's craft. 0 means "not chosen yet". */
    val multiplayColor: Long = 0L,
    /** Whether tilting the phone steers the camera. */
    val multiplayMotionCamera: Boolean = true,
    /** Whether tilting the phone also steers the craft (the stick still works). */
    val multiplayTiltSteer: Boolean = false,
    /** Best local scores, `mode=score;mode=score`. */
    val multiplayBestScores: Map<String, Int> = emptyMap(),
    /** Achievement ids unlocked so far. */
    val multiplayAchievements: Set<String> = emptySet(),
    /**
     * Game modes whose how-to-play card has been shown.
     *
     * Per mode rather than one flag: the four games share a stick and three buttons but not a
     * goal, and somebody who has raced still has no idea what "it" means in Tag. Seeing the card
     * once per mode is the difference between a game that explains itself and one that expects you
     * to already know.
     */
    val multiplayHowToPlaySeen: Set<String> = emptySet(),
    /** Games played, for the profile card. */
    val multiplayGamesPlayed: Int = 0,
) {
    companion object {
        /** How many endpoints are remembered before the oldest is dropped. */
        const val MAX_RTL_TCP_HISTORY: Int = 5
    }
}
