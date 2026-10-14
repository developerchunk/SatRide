package com.developerstring.satride.feature.satellites

/**
 * The top-level cuts through the catalog.
 *
 * Not filters — a starting point. Someone opening this screen almost always wants one of a small
 * number of things ("what can I see right now", "the weather birds", "what can I actually hear"),
 * and a filter sheet makes them assemble that themselves. The segments answer it in one tap; the
 * filter sheet is for narrowing afterwards.
 */
enum class CatalogSegment(val label: String) {
    /** Above the horizon this second. Empty most of the time, and that is useful information. */
    UP_NOW("Up now"),

    /**
     * What the user has chosen to track.
     *
     * The set the tracking engine actually propagates, the sky draws and the timeline predicts —
     * so it is the one segment whose contents the user owns outright. Managed from the tracking
     * screen; see `TrackingSelection`.
     */
    TRACKED("Tracked"),

    /** The bundled starter set: available offline, and chosen to be worth looking at. */
    POPULAR("Popular"),

    WEATHER("Weather"),

    /** Amateur-band satellites with a working downlink — the ones the radio half can use. */
    HAM("Ham/FM"),

    CREWED("Crewed"),

    /** Everything in the database — after a full sync, every catalogued object in orbit. */
    ALL("All"),
    ;

    companion object {
        /**
         * Popular, not [UP_NOW].
         *
         * "Up now" is the more interesting cut and the worse landing: it is genuinely empty a good
         * part of the time, it needs a location fix before it can say anything at all, and on a
         * first launch it needs the catalog to have synced too. So the tab people arrive on was
         * routinely a blank screen with an explanation on it. [POPULAR] is the bundled starter
         * set — offline, immediate, and chosen to be worth looking at — so the catalog opens with
         * satellites in it every time, and "up now" is one tap away for anyone who wants it.
         */
        val default: CatalogSegment = POPULAR
    }
}
