package com.developerstring.satride.core.data

import com.developerstring.satride.core.common.AppError

/**
 * What a refresh actually achieved, per source.
 *
 * A refresh across seven endpoints is rarely all-or-nothing — one community server being down
 * while the rest succeed is the normal case, not an error. Reporting it as a single boolean would
 * either hide a real outage or turn a routine partial success into a failure message.
 */
data class RefreshResult(
    val sources: List<SourceOutcome>,
    /** Element sets written, counting only those that were newer than what was stored. */
    val tlesUpdated: Int,
    val satellitesSeen: Int,
    val transmittersUpdated: Int,
    val transmitterError: AppError? = null,
) {
    val succeededSources: List<SourceOutcome> get() = sources.filter { it.error == null }
    val failedSources: List<SourceOutcome> get() = sources.filter { it.error != null }

    /** Sources that answered "you already have this". Not failures — see [SourceOutcome.upToDate]. */
    val upToDateSources: List<SourceOutcome> get() = sources.filter { it.upToDate }

    /**
     * True when nothing at all was retrieved *and* nothing was already current.
     *
     * The second half matters: a catalog that answers "you already have this" for every group has
     * fetched nothing and is nonetheless completely healthy.
     */
    val isCompleteFailure: Boolean
        get() = succeededSources.isEmpty() && upToDateSources.isEmpty() && transmittersUpdated == 0

    /** True when some sources worked and others did not. Worth a quiet note, not an alarm. */
    val isPartial: Boolean get() = !isCompleteFailure && (failedSources.isNotEmpty() || transmitterError != null)

    /** The first error worth showing, preferring the one that cost the most data. */
    val representativeError: AppError?
        get() = failedSources.firstOrNull()?.error ?: transmitterError

    /**
     * True when a catalog asked us to slow down.
     *
     * Distinct from an unreachable server, because the user's response differs: an outage is worth
     * retrying and a **429** is the catalog asking to be left alone for a while. Note that a plain
     * **403** from CelesTrak usually means "you already have this data" — that is handled as
     * [SourceOutcome.upToDate] long before it reaches here, and must never be reported as either.
     */
    val isRateLimited: Boolean
        get() = failedSources.any { (it.error as? AppError.Server)?.statusCode in RATE_LIMIT_CODES }

    /** True when every source either delivered or was already current. */
    val isFullyCurrent: Boolean get() = failedSources.isEmpty() && transmitterError == null

    data class SourceOutcome(
        val sourceId: String,
        val label: String,
        val fetched: Int,
        val error: AppError? = null,
        /**
         * The source had nothing new for us.
         *
         * Counts as a success with zero rows. CelesTrak answers a repeat request for an unchanged
         * group this way, every two hours, for as long as the data is unchanged — so treating it
         * as a failure meant a perfectly healthy app reported a broken catalog most of the time.
         */
        val upToDate: Boolean = false,
    )

    companion object {
        fun empty(): RefreshResult = RefreshResult(emptyList(), 0, 0, 0)

        /** Statuses that mean "not now" rather than "not working". */
        private val RATE_LIMIT_CODES = setOf(429)
    }
}
