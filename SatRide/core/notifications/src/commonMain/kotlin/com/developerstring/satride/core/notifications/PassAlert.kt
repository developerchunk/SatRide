package com.developerstring.satride.core.notifications

import com.developerstring.satride.core.common.asCompassPoint
import com.developerstring.satride.core.common.format
import com.developerstring.satride.core.model.Pass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A reminder the user asked for, about one pass of one satellite.
 *
 * Holds a copy of the pass's numbers rather than a reference to it. A pass is a *computed* thing —
 * it is recomputed from new elements every day and its cache row is pruned the moment its element
 * set is superseded — so an alert that pointed at one would go dangling within a day of being
 * created. Copying the numbers also means the notification can be written by a boot receiver that
 * has no orbit model warmed up and no observer position yet.
 *
 * [id] is stable across reschedules. When a TLE refresh moves the pass by a few seconds the alert
 * keeps its identity and only its [aos] changes, so the platform alarm is replaced rather than
 * duplicated — the alternative silently accumulates one dead alarm per refresh.
 */
data class PassAlert(
    val id: String,
    val noradId: Int,
    val satelliteName: String,
    val aos: Instant,
    val maxElevationDeg: Double,
    val aosAzimuthDeg: Double,
    /** How far ahead of AOS the reminder fires. Ten minutes is enough to get outside and look up. */
    val leadTime: Duration = DEFAULT_LEAD_TIME,
    /** When the user asked for it, kept for diagnostics and for ordering the alerts list. */
    val createdAt: Instant,
) {
    /** The moment the notification should appear. */
    val fireAt: Instant get() = aos - leadTime

    val title: String get() = "$satelliteName pass"

    /**
     * The body a user reads on a lock screen, in the order they need it: how long they have, how
     * good it will be, and which way to face.
     */
    val body: String
        get() = buildString {
            append("Pass in ")
            append(leadTime.describe())
            append(" — max ")
            append(maxElevationDeg.format(0))
            append("°, rises ")
            append(aosAzimuthDeg.asCompassPoint())
        }

    companion object {
        val DEFAULT_LEAD_TIME: Duration = 10.minutes

        /**
         * The identity of an alert: the satellite and the pass it was first created for.
         *
         * Deterministic rather than random so asking twice for the same pass replaces the alert
         * instead of arming a second alarm for the same moment.
         */
        fun idFor(noradId: Int, originalAos: Instant): String = "$noradId@${originalAos.toEpochMilliseconds()}"

        fun from(pass: Pass, satelliteName: String, now: Instant, leadTime: Duration = DEFAULT_LEAD_TIME): PassAlert =
            PassAlert(
                id = idFor(pass.noradId, pass.aos),
                noradId = pass.noradId,
                satelliteName = satelliteName,
                aos = pass.aos,
                maxElevationDeg = pass.maxElevationDeg,
                aosAzimuthDeg = pass.aosAzimuthDeg,
                leadTime = leadTime,
                createdAt = now,
            )
    }
}

/**
 * A lead time in the words a notification uses.
 *
 * "10 min", not "PT10M" and not "600 seconds". Only ever called with round values the user chose
 * from a short list, so it does not try to be a general duration formatter.
 */
internal fun Duration.describe(): String = when {
    this < 1.minutes -> "under a minute"
    inWholeMinutes < 60 -> "$inWholeMinutes min"
    inWholeMinutes % 60 == 0L -> "${inWholeHours}h"
    else -> "${inWholeHours}h ${inWholeMinutes % 60}m"
}
