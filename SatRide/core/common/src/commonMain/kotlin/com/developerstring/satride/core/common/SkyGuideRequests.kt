package com.developerstring.satride.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Take me to this satellite in the sky view."
 *
 * A pass notification, a deep link and the pass detail screen's [Open in Sky] all want the same
 * thing, and none of them can call the sky view directly: two of them arrive from outside the
 * Compose tree entirely, and the third lives in a feature module that may not depend on another
 * feature. So the request is put here, application-scoped, and the sky view picks it up.
 *
 * Modelled as latest-wins state rather than as an event stream, on purpose. A user who taps two
 * notifications in a second means the second one; a queue would walk them through the first and
 * then jump, which is the opposite of what the taps said.
 *
 * ## Why nothing consumes
 *
 * A request has *two* readers that must both act on it: the sky view, which guides, and the app
 * shell, which has to be showing the sky view for that to be visible. The first design had the sky
 * view consume the request — and on a device it lost every time. Tapping a pass notification while
 * the app sat on the Passes tab did nothing at all: the sky view's view model is still alive on the
 * back stack, so it consumed the request the instant it was posted, `StateFlow` coalesced the value
 * back to null before the shell's `LaunchedEffect` ever recomposed, and the shell concluded there
 * was nothing to navigate to.
 *
 * So the request is never cleared. Each reader remembers the highest sequence number it has already
 * acted on and ignores anything at or below it, which makes handling idempotent and completely
 * independent of who gets there first.
 */
interface SkyGuideRequests {
    val pending: StateFlow<SkyGuideRequest?>

    fun request(noradId: Int, guide: Boolean = true)
}

/**
 * One request, with a sequence number.
 *
 * The number is what makes "the same satellite, again" a distinct request. Without it, tapping two
 * notifications for the ISS ten minutes apart produces an identical value, `StateFlow` conflates
 * the second away, and the second tap does nothing at all.
 */
data class SkyGuideRequest(val noradId: Int, val guide: Boolean, val sequence: Long)

class DefaultSkyGuideRequests : SkyGuideRequests {
    private val state = MutableStateFlow<SkyGuideRequest?>(null)
    private var sequence = 0L

    override val pending: StateFlow<SkyGuideRequest?> = state.asStateFlow()

    override fun request(noradId: Int, guide: Boolean) {
        sequence += 1
        state.value = SkyGuideRequest(noradId = noradId, guide = guide, sequence = sequence)
    }
}
