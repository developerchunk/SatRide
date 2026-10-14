package com.developerstring.satride.core.data

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.AppDispatchers
import com.developerstring.satride.core.common.runCatchingCancellable
import com.developerstring.satride.core.network.GpGroup
import kotlinx.coroutines.withContext

/**
 * The one thing the app calls on startup.
 *
 * Seed from the bundle if the catalog is empty, then refresh if it has been long enough. There is
 * no scheduler and no background worker: a satellite tracker is opened deliberately, so refreshing
 * when the user actually arrives is both sufficient and impossible to get wrong on a platform
 * whose background-execution rules keep changing.
 */
class CatalogSync(
    private val seeder: CatalogSeeder,
    private val tleRepository: TleRepository,
    private val transmitterRepository: TransmitterRepository,
    private val passRepository: DefaultPassRepository,
    private val alertCoordinator: PassAlertCoordinator,
    private val settings: com.developerstring.satride.core.datastore.SettingsRepository,
    private val dispatchers: AppDispatchers,
) {

    private val logger = Logger.withTag("CatalogSync")

    data class SyncResult(
        val seeded: CatalogSeeder.SeedResult?,
        val refresh: RefreshResult?,
        val passesComputed: Int,
        val alerts: PassAlertCoordinator.Result = PassAlertCoordinator.Result.NOTHING_TO_DO,
    )

    /**
     * Brings the catalog up to date.
     *
     * Nothing here throws: every step is independently recoverable and the app is fully usable
     * with whatever is already stored, so a failed refresh is reported rather than raised.
     */
    suspend fun sync(groups: Set<GpGroup>? = null, force: Boolean = false): SyncResult = withContext(dispatchers.io) {
        // Null means "whatever the user's tracking settings imply". Startup must not fetch the
        // six starter groups for somebody who has asked to track the whole sky, or their
        // selection would be permanently short of element sets with nothing to say why.
        val scope = groups ?: CatalogScope.of(settings.current())
        val seeded = seeder.seedIfEmpty()
            .onFailure { logger.e(it) { "seeding the bundled catalog failed" } }
            .getOrNull()

        val refresh = runCatchingCancellable {
            if (force) tleRepository.refresh(scope) else tleRepository.refreshIfStale(scope)
        }.onFailure { logger.e(it) { "element-set refresh failed" } }.getOrNull()

        // Transmitters only when element sets actually moved. The transmitter catalog is 2 MB
        // and changes far more slowly than orbits do, so refetching it on every launch would be
        // most of the app's data use for almost none of its value.
        val transmitterCount = if (refresh != null && !refresh.isCompleteFailure) {
            transmitterRepository.refresh()
                .onFailure { logger.w(it) { "transmitter refresh failed; keeping the stored catalog" } }
                .getOrNull() ?: 0
        } else {
            0
        }

        val passes = runCatchingCancellable {
            passRepository.refreshUpcoming(DefaultPassRepository.PRECOMPUTE_WINDOW)
        }
            .onFailure { logger.w(it) { "pass precomputation failed" } }
            .getOrDefault(0)

        // Last, and after the passes have been recomputed: a reminder is only as good as the
        // prediction behind it, and new elements move pass times by seconds a day — enough to
        // send someone outside at the wrong minute within a week.
        val alerts = runCatchingCancellable { alertCoordinator.reconcile() }
            .onFailure { logger.w(it) { "reconciling pass alerts failed" } }
            .getOrDefault(PassAlertCoordinator.Result.NOTHING_TO_DO)

        SyncResult(
            seeded = seeded,
            refresh = refresh?.copy(transmittersUpdated = transmitterCount),
            passesComputed = passes,
            alerts = alerts,
        )
    }
}
