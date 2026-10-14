package com.developerstring.satride.core.model

/**
 * Where SatRide believes the user is, and how it came to believe it.
 *
 * Modelled as three distinct states rather than a nullable [GeoPos] because the *provenance*
 * changes what the UI should say. "Waiting for a fix", "using your saved location" and "using
 * GPS" all render differently, and collapsing them to null-or-not loses the difference —
 * leaving a screen unable to distinguish a user who declined location permission from one whose
 * first fix has simply not arrived.
 */
sealed interface ObserverPosition {

    /** No position: permission declined, no fix yet, or a platform with no location hardware. */
    data object Unknown : ObserverPosition

    /**
     * A fix from the device.
     *
     * [accuracyM] is the platform's own horizontal-accuracy estimate, or null if it did not
     * offer one. SatRide asks for coarse accuracy on purpose — a kilometre of error moves a
     * satellite at 800 km range by well under a tenth of a degree, far below the compass error
     * the AR view already lives with, and coarse location is cheaper and less intrusive.
     */
    data class Automatic(val position: GeoPos, val accuracyM: Double? = null) : ObserverPosition

    /**
     * A position the user entered.
     *
     * Always wins over a device fix. Someone who typed in a location meant it — they may be
     * planning tonight's session for a dark-sky site an hour's drive away.
     */
    data class Manual(val position: GeoPos) : ObserverPosition

    /** The position, or null when [Unknown]. */
    val geoPos: GeoPos?
        get() = when (this) {
            is Automatic -> position
            is Manual -> position
            Unknown -> null
        }

    val isKnown: Boolean get() = this !is Unknown
}
