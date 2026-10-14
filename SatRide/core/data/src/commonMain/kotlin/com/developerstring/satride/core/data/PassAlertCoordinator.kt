package com.developerstring.satride.core.data

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.model.Pass
import com.developerstring.satride.core.notifications.AlertAction
import com.developerstring.satride.core.notifications.AlertReconciliation
import com.developerstring.satride.core.notifications.AlertStore
import com.developerstring.satride.core.notifications.DefaultAlertScheduler
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Keeps armed reminders in step with the orbits they were computed from.
 *
 * Run after every element-set refresh. Without it, an alert armed on Monday fires at Monday's
 * predicted time all week, drifting a little further each day — and the failure is invisible until
 * the user is standing outside at the wrong minute.
 *
 * The decision of what to do with each alert is [AlertReconciliation]'s, and is pure. This class
 * is only the plumbing: it gathers the inputs, and applies the answers.
 */
class PassAlertCoordinator(
    private val scheduler: DefaultAlertScheduler,
    private val store: AlertStore,
    private val passRepository: PassRepository,
    private val tleRepository: TleRepository,
    private val observerPositions: ObserverPositionProvider,
    private val clock: Clock,
) {

    private val logger = Logger.withTag("PassAlerts")

    data class Result(val kept: Int, val rescheduled: Int, val dropped: Int) {
        val changed: Int get() = rescheduled + dropped

        companion object {
            val NOTHING_TO_DO = Result(0, 0, 0)
        }
    }

    suspend fun reconcile(): Result {
        val stored = store.all()
        if (stored.isEmpty()) return Result.NOTHING_TO_DO

        val now = clock.now()
        val observer = observerPositions.position().first()
        if (observer == null) {
            // Every alert is kept: without a position nothing can be recomputed, and cancelling
            // reminders because the phone has not got a fix yet would be the worst possible
            // reading of "we do not know".
            logger.i { "no observer position; leaving ${stored.size} alerts alone" }
            return Result(kept = stored.size, rescheduled = 0, dropped = 0)
        }

        val window = stored.maxOf { it.aos - now }.plus(1.days).coerceAtMost(MAX_WINDOW)
        val recomputed = mutableMapOf<Int, List<Pass>>()
        stored.map { it.noradId }.distinct().forEach { noradId ->
            // A satellite with no element set is left out of the map entirely rather than mapped
            // to an empty list. The two look the same to a naive reader and mean opposite things:
            // "could not check" must not cancel a reminder, "checked, and it is not happening"
            // must.
            if (tleRepository.tleFor(noradId) == null) {
                logger.w { "no elements for $noradId; its alerts are left as they are" }
                return@forEach
            }
            recomputed[noradId] = runCatching { passRepository.passesFor(noradId, observer, window) }
                .getOrElse {
                    logger.w(it) { "recomputing passes for $noradId failed" }
                    return@forEach
                }
        }

        var kept = 0
        var rescheduled = 0
        var dropped = 0

        AlertReconciliation.reconcile(stored, recomputed, now).forEach { action ->
            when (action) {
                is AlertAction.Keep -> kept++

                is AlertAction.Reschedule -> {
                    logger.i { "${action.alert.id} moved by ${action.drift}; re-arming" }
                    scheduler.schedule(action.alert)
                        .onSuccess { rescheduled++ }
                        .onFailure { logger.w(it) { "re-arming ${action.alert.id} failed" } }
                }

                is AlertAction.Drop -> {
                    logger.i { "dropping ${action.alert.id}: ${action.reason}" }
                    scheduler.cancel(action.alert.id)
                    dropped++
                }
            }
        }

        return Result(kept = kept, rescheduled = rescheduled, dropped = dropped)
    }

    private companion object {
        /** No alert can be further out than the longest pass window, plus a day of slack. */
        val MAX_WINDOW: Duration = 8.days
    }
}
