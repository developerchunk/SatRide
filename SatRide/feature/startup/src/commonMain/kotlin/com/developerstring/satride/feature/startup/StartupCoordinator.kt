package com.developerstring.satride.feature.startup

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The work the splash screen is waiting on.
 *
 * Named individually rather than hidden behind one boolean, because the splash names the step it is
 * on and a bare progress bar that invents its own motion is worse than none — it claims the app is
 * working when it may be stuck.
 */
enum class StartupStep(val label: String) {
    /** Copy the bundled catalog into the database if it is empty. Fast, local, never fails twice. */
    CATALOG("Loading the catalog"),

    /** Fetch fresh element sets. The only step that needs a network, and the one allowed to fail. */
    ELEMENTS("Updating orbital data"),

    /** Parse the vector Earth the Play globe draws. Local, CPU-bound, a few hundred milliseconds. */
    GLOBE("Building the globe"),

    /** Restore the Firebase session, if there is one. */
    SESSION("Signing you in"),
}

enum class StepState { PENDING, RUNNING, DONE, FAILED }

data class StartupProgress(
    val steps: Map<StartupStep, StepState> = StartupStep.entries.associateWith { StepState.PENDING },
    val finished: Boolean = false,
    /** True when the eight-second cap ended it rather than the work completing. */
    val timedOut: Boolean = false,
) {
    val currentLabel: String
        get() = steps.entries.firstOrNull { it.value == StepState.RUNNING }?.key?.label
            ?: StartupStep.entries.lastOrNull { steps[it] == StepState.DONE }?.label
            ?: StartupStep.CATALOG.label

    /** Measured, not animated: the fraction of steps that have actually settled. */
    val fraction: Float
        get() = steps.values.count { it == StepState.DONE || it == StepState.FAILED }.toFloat() /
            steps.size.toFloat()

    /**
     * Steps that never finished before handoff.
     *
     * The screens that depend on these are the ones that show a shimmer — everything else has its
     * data and should render normally.
     */
    val stillRunning: Set<StartupStep>
        get() = steps.filterValues { it == StepState.PENDING || it == StepState.RUNNING }.keys
}

/**
 * Runs the startup work and reports it honestly.
 *
 * Every step is independently failable and independently reported. A failed element-set refresh is
 * *not* a failed startup: the bundled catalog is three years of satellites and works with no
 * network at all, which is the whole reason it is bundled.
 *
 * Nothing here enforces the eight-second cap — that belongs to the view model, so that this class
 * stays a plain description of the work and can be tested without a clock.
 */
class StartupCoordinator(private val steps: Map<StartupStep, suspend () -> Unit>) {

    private val logger = Logger.withTag("Startup")

    private val mutableProgress = MutableStateFlow(StartupProgress())
    val progress: StateFlow<StartupProgress> = mutableProgress.asStateFlow()

    /**
     * Runs every step concurrently.
     *
     * Concurrently because they are genuinely independent — the globe's geometry has nothing to do
     * with the network, and the session has nothing to do with either. Running them in sequence
     * would make the slowest one everybody's problem, and the slowest one is the network.
     */
    suspend fun run() {
        coroutineScope {
            steps.map { (step, work) ->
                async {
                    update(step, StepState.RUNNING)
                    val outcome = runCatchingCancellable { work() }
                    outcome
                        .onFailure { logger.w(it) { "startup step $step failed" } }
                    update(step, if (outcome.isSuccess) StepState.DONE else StepState.FAILED)
                }
            }.awaitAll()
        }
        mutableProgress.value = mutableProgress.value.copy(finished = true)
    }

    /** Marks the run as ended by the cap. Steps keep running; this only records why we moved on. */
    fun markTimedOut() {
        if (mutableProgress.value.finished) return
        mutableProgress.value = mutableProgress.value.copy(finished = true, timedOut = true)
    }

    private fun update(step: StartupStep, state: StepState) {
        mutableProgress.value = mutableProgress.value.copy(
            steps = mutableProgress.value.steps + (step to state),
        )
    }
}
