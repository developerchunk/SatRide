package com.developerstring.satride.feature.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.developerstring.satride.core.common.AppStartMark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The splash, and the eight-second promise.
 *
 * Whatever has or has not finished, the user is on the home screen by [MAX_SPLASH_DURATION]. A
 * splash that waits on the network is a splash that never ends on a train, and "it hung on the
 * loading screen" is the single most common one-star review an app like this collects.
 *
 * The cap is a race, not a timeout that cancels: whichever of "everything finished" and "eight
 * seconds elapsed" wins, the handoff is the same, and **the work keeps running either way**. A
 * timeout that cancelled the catalog refresh would turn a slow start into no data at all, which is
 * a far worse outcome than a slightly late one.
 */
class StartupViewModel(
    private val coordinator: StartupCoordinator,
    private val appStart: AppStartMark,
    private val cap: Duration = MAX_SPLASH_DURATION,
) : ViewModel() {

    val progress: StateFlow<StartupProgress> = coordinator.progress

    private val mutableReady = MutableStateFlow(false)

    /** True once the home screen should be shown. Never returns to false. */
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    init {
        // Launched as its own coroutine, deliberately. The waiter below uses `withTimeoutOrNull`,
        // which cancels whatever is *inside* it — and what is inside it is only the act of watching
        // progress, never the work. A cap that cancelled the catalog refresh would turn a slow
        // start into no data at all, which is far worse than a late one.
        viewModelScope.launch { coordinator.run() }

        viewModelScope.launch {
            // Measured from *launch*, not from here. Process start, Koin wiring and the first
            // frame are 2.3 s of cold start on a Pixel 6a, and the user's eight seconds began when
            // they tapped the icon — charging the cap only from this constructor turned the promise
            // into a ten-second wait on a real device.
            val budget = appStart.remainingOf(cap)
            val finishedInTime = withTimeoutOrNull(budget) {
                coordinator.progress.first { it.finished }
            }
            if (finishedInTime == null) coordinator.markTimedOut()
            mutableReady.value = true
            // Logged because the guarantee this class exists to make is about wall-clock time, and
            // the only place that can be checked is a real device on a real network.
            Logger.withTag("Startup").i {
                val how = if (finishedInTime == null) "cap" else "work finished"
                "handing off to home after ${appStart.elapsed} ($how)"
            }
        }
    }

    companion object {
        val MAX_SPLASH_DURATION: Duration = 8.seconds
    }
}
