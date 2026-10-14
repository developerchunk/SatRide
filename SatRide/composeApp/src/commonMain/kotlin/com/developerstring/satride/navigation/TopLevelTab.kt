package com.developerstring.satride.navigation

import com.developerstring.satride.core.designsystem.component.SatRideIcon

/**
 * The five top-level destinations.
 *
 * Satellites is first and is the landing tab (2026-08-06): the catalog is the calmest place to
 * arrive — it always has data, and on a first launch it carries the sync loading state — where
 * the Sky view opens straight into camera/sensor territory. Play sits in the middle — it is
 * the tab people show other people.
 */
enum class TopLevelTab(val route: SatRideRoute.Tab, val label: String, val icon: SatRideIcon) {
    Satellites(SatRideRoute.Satellites, "Satellites", SatRideIcon.Satellites),
    Sky(SatRideRoute.Sky, "Sky", SatRideIcon.Sky),
    Play(SatRideRoute.Play, "Play", SatRideIcon.Play),
    Passes(SatRideRoute.Passes, "Passes", SatRideIcon.Passes),
    Profile(SatRideRoute.Profile, "Profile", SatRideIcon.Profile),
    ;

    companion object {
        /**
         * The tabs this build shows.
         *
         * Radio is absent: the receiver is deferred to post-v1 (2026-08-04), and a tab whose only
         * content is "coming soon" spends a fifth of the bottom bar on nothing. `feature:radio` and
         * its route both still exist, so restoring it is adding one entry here — which is exactly
         * the property "deferred, not deleted" is supposed to have.
         */
        val visible: List<TopLevelTab> get() = entries
    }
}
