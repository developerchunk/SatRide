package com.developerstring.satride.ui.walkthrough

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.navigation.TopLevelTab
import kotlinx.coroutines.launch

/**
 * One beat of the tour: something on screen, and what to say about it.
 *
 * [tab] is the tab this step is *about*. The tour switches to it as the step opens, so the user is
 * looking at the screen being described while the spotlight sits on the button that got them
 * there — the two halves of "this is what this button is for".
 */
@Stable
class WalkthroughStep(val targetKey: String, val title: String, val body: String, val tab: TopLevelTab?)

/**
 * The tour, in the order it makes sense to meet the app.
 *
 * Satellites first because it is where the app lands and where the data lives; Sky second because
 * it is the reason to have installed it; Play, Passes and Profile after, each in one sentence.
 * Five stops is the whole budget — a tour long enough to feel like a manual gets skipped, and a
 * skipped tour teaches nothing.
 */
internal val WalkthroughSteps: List<WalkthroughStep> = listOf(
    WalkthroughStep(
        targetKey = TopLevelTab.Satellites.name,
        title = "Every satellite, listed",
        body = "Browse what is up now, the popular ones, weather birds and more. Tap any for its orbit and next pass.",
        tab = TopLevelTab.Satellites,
    ),
    WalkthroughStep(
        targetKey = TopLevelTab.Sky.name,
        title = "Point at the sky",
        body = "Hold your phone up and satellites appear over the real sky, exactly where they are.",
        tab = TopLevelTab.Sky,
    ),
    WalkthroughStep(
        targetKey = TopLevelTab.Play.name,
        title = "The whole constellation",
        body = "A live 3D Earth you can spin. Pick a satellite and ride along with it.",
        tab = TopLevelTab.Play,
    ),
    WalkthroughStep(
        targetKey = TopLevelTab.Passes.name,
        title = "Never miss one",
        body = "When each satellite rises over you, and a reminder a few minutes before it does.",
        tab = TopLevelTab.Passes,
    ),
    WalkthroughStep(
        targetKey = TopLevelTab.Profile.name,
        title = "You are set",
        body = "Your account and permissions live here. Change what SatRide can access any time.",
        tab = TopLevelTab.Profile,
    ),
)

/**
 * The tour's state: which step is showing, and whether it is showing at all.
 *
 * Deliberately not a view model. It has no work to do beyond one boolean read at startup and one
 * write at the end, and keeping it as plain composition state means the tour lives and dies with
 * the shell it is drawn over rather than outliving it in a retained scope.
 */
@Stable
class WalkthroughState internal constructor(
    private val settings: SettingsRepository,
    private val onFinished: () -> Unit,
) {
    var index by mutableIntStateOf(0)
        private set

    var running by mutableStateOf(false)
        private set

    val step: WalkthroughStep? get() = if (running) WalkthroughSteps.getOrNull(index) else null

    val total: Int get() = WalkthroughSteps.size

    val isLast: Boolean get() = index == WalkthroughSteps.lastIndex

    internal fun start() {
        index = 0
        running = true
    }

    fun next() {
        if (isLast) finish() else index++
    }

    fun restart() {
        index = 0
    }

    /**
     * Ends the tour, whether by finishing it or skipping it.
     *
     * Both write the same flag on purpose: somebody who skipped has told you they do not want it,
     * and showing it to them again on the next launch is a worse offence than never showing it.
     */
    fun finish() {
        running = false
        onFinished()
    }
}

/**
 * Creates the tour and starts it once, on the first launch that reaches the app proper.
 *
 * [canStart] is what keeps it from opening over the splash, the onboarding pager or the sign-in
 * screen: those are full-screen surfaces with no tab bar, so there would be nothing to point at.
 */
@Composable
fun rememberWalkthroughState(settings: SettingsRepository, canStart: Boolean): WalkthroughState {
    val scope = rememberCoroutineScope()
    val state = remember(settings) {
        WalkthroughState(settings) {
            scope.launch { settings.setWalkthroughComplete(true) }
        }
    }

    var considered by remember { mutableStateOf(false) }
    LaunchedEffect(canStart) {
        if (!canStart || considered) return@LaunchedEffect
        considered = true
        if (!settings.current().walkthroughComplete) state.start()
    }

    return state
}
