package com.developerstring.satride.core.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAlertSchedulerTest {

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun alert(id: String = "25544@1", minutesAhead: Int = 30) = PassAlert(
        id = id,
        noradId = 25544,
        satelliteName = "ISS",
        aos = now + minutesAhead.minutes,
        maxElevationDeg = 60.0,
        aosAzimuthDeg = 270.0,
        createdAt = now,
    )

    @Test
    fun firesAtTheLeadTimeNotAtAos() = runTest {
        val shown = RecordingNotifier()
        val scheduler = DesktopAlertScheduler(backgroundScope, FakeClock(now), shown)

        assertTrue(scheduler.arm(alert()).isSuccess)
        assertEquals(1, scheduler.armedCount)

        // fireAt = aos - 10 min = 20 min from now.
        advanceTimeBy(19.minutes)
        runCurrent()
        assertTrue(shown.calls.isEmpty())

        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(listOf("ISS pass"), shown.calls.map { it.first })
        assertEquals(0, scheduler.armedCount)
    }

    @Test
    fun disarmCancelsTheTimer() = runTest {
        val shown = RecordingNotifier()
        val scheduler = DesktopAlertScheduler(backgroundScope, FakeClock(now), shown)

        scheduler.arm(alert())
        scheduler.disarm("25544@1")

        advanceTimeBy(60.minutes)
        runCurrent()
        assertTrue(shown.calls.isEmpty())
        assertEquals(0, scheduler.armedCount)
    }

    @Test
    fun rearmingTheSameIdReplacesRatherThanDuplicates() = runTest {
        val shown = RecordingNotifier()
        val scheduler = DesktopAlertScheduler(backgroundScope, FakeClock(now), shown)

        scheduler.arm(alert(minutesAhead = 30))
        scheduler.arm(alert(minutesAhead = 40))
        assertEquals(1, scheduler.armedCount)

        advanceTimeBy(60.minutes)
        runCurrent()
        assertEquals(1, shown.calls.size)
    }

    @Test
    fun aPassAlreadyInsideTheLeadTimeFiresImmediately() = runTest {
        val shown = RecordingNotifier()
        val scheduler = DesktopAlertScheduler(backgroundScope, FakeClock(now), shown)

        scheduler.arm(alert(minutesAhead = 5))
        runCurrent()
        assertEquals(1, shown.calls.size)
    }

    @Test
    fun reportsUnsupportedWhenTheDesktopCannotNotify() = runTest {
        val scheduler = DesktopAlertScheduler(backgroundScope, FakeClock(now), RecordingNotifier(isAvailable = false))
        assertFalse(scheduler.capability.supported)
        assertTrue(scheduler.arm(alert()).isFailure)
    }

    @Test
    fun appleScriptEscapingIsAppliedToTheCommand() {
        val commands = mutableListOf<List<String>>()
        val notifier = SystemNotifier(os = "Mac OS X", path = "", run = { commands += it })
        notifier.notify("ISS \"pass\"", "back\\slash")
        val script = commands.single().last()
        assertTrue("\\\"pass\\\"" in script, script)
        assertTrue("back\\\\slash" in script, script)
    }

    private class RecordingNotifier(override val isAvailable: Boolean = true) : DesktopNotifier {
        val calls = mutableListOf<Pair<String, String>>()
        override fun notify(title: String, body: String) {
            calls += title to body
        }
    }
}
