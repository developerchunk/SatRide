package com.developerstring.satride.core.notifications

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DefaultAlertSchedulerTest {

    private fun scheduler(
        now: kotlin.time.Instant = T0,
        store: InMemoryAlertStore = InMemoryAlertStore(),
        platform: RecordingPlatformScheduler = RecordingPlatformScheduler(),
    ) = Triple(DefaultAlertScheduler(store, platform, FakeClock(now)), store, platform)

    @Test
    fun schedulingStoresAndArms() = runTest {
        val (alerts, store, platform) = scheduler()
        val requested = alert(aos = T0 + 2.hours)

        val result = alerts.schedule(requested)

        assertTrue(result.isSuccess)
        assertEquals(listOf(requested), store.all())
        assertEquals(listOf(requested), platform.armed)
    }

    @Test
    fun theAlarmIsSetForTheLeadTimeBeforeAos() = runTest {
        val (alerts, _, platform) = scheduler()
        val aos = T0 + 3.hours

        alerts.schedule(alert(aos = aos, leadTime = 10.minutes))

        assertEquals(aos - 10.minutes, platform.armed.single().fireAt)
    }

    @Test
    fun aPassInsideTheLeadTimeIsRemindedAboutImmediatelyRatherThanRefused() = runTest {
        val (alerts, _, platform) = scheduler()
        // Eight minutes out, with a ten-minute lead: the reminder is already "late".
        val aos = T0 + 8.minutes

        val result = alerts.schedule(alert(aos = aos, leadTime = 10.minutes))

        assertTrue(result.isSuccess)
        val armed = platform.armed.single()
        // Fires within a few seconds of now, and never at AOS itself.
        assertTrue(armed.fireAt > T0, "should not fire in the past")
        assertTrue(armed.fireAt < aos, "should not fire at the moment the pass starts")
    }

    @Test
    fun aPassThatHasAlreadyStartedIsRefused() = runTest {
        val (alerts, store, platform) = scheduler()

        val result = alerts.schedule(alert(aos = T0 - 1.minutes))

        assertTrue(result.isFailure)
        assertTrue(store.all().isEmpty())
        assertTrue(platform.armed.isEmpty())
    }

    @Test
    fun anUnsupportedPlatformRefusesRatherThanPretending() = runTest {
        val (alerts, store, _) = scheduler(
            platform = RecordingPlatformScheduler(capability = AlertCapability(supported = false)),
        )

        val result = alerts.schedule(alert(aos = T0 + 2.hours))

        assertTrue(result.isFailure)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun aFailedArmKeepsTheStoredAlertSoTheNextReconcileCanRetry() = runTest {
        val platform = RecordingPlatformScheduler()
        platform.failNext(IllegalStateException("no exact alarms"))
        val (alerts, store, _) = scheduler(platform = platform)
        val requested = alert(aos = T0 + 2.hours)

        val result = alerts.schedule(requested)

        assertTrue(result.isFailure)
        // Stored anyway: losing the record is the unrecoverable half of the failure.
        assertEquals(listOf(requested), store.all())
    }

    @Test
    fun schedulingTheSamePassTwiceReplacesRatherThanDuplicates() = runTest {
        val (alerts, store, _) = scheduler()
        val aos = T0 + 4.hours

        alerts.schedule(alert(aos = aos, leadTime = 10.minutes))
        alerts.schedule(alert(aos = aos, leadTime = 20.minutes))

        assertEquals(1, store.all().size)
        assertEquals(20.minutes, store.all().single().leadTime)
    }

    @Test
    fun cancelDisarmsAndForgets() = runTest {
        val (alerts, store, platform) = scheduler()
        val requested = alert(aos = T0 + 2.hours)
        alerts.schedule(requested)

        alerts.cancel(requested.id)

        assertTrue(store.all().isEmpty())
        assertEquals(listOf(requested.id), platform.disarmed)
    }

    @Test
    fun scheduledIsObservable() = runTest {
        val (alerts, _, _) = scheduler()
        assertTrue(alerts.scheduled().first().isEmpty())

        alerts.schedule(alert(aos = T0 + 2.hours))

        assertEquals(1, alerts.scheduled().first().size)
    }

    @Test
    fun rearmingAfterBootArmsTheFutureAndDropsThePast() = runTest {
        val future = alert(noradId = 25544, aos = T0 + 90.minutes)
        val past = alert(noradId = 33591, aos = T0 - 30.minutes)
        val store = InMemoryAlertStore(listOf(future, past))
        val platform = RecordingPlatformScheduler()
        val alerts = DefaultAlertScheduler(store, platform, FakeClock(T0))

        val count = alerts.rearmAll()

        assertEquals(1, count)
        assertEquals(listOf(future), platform.armed)
        assertEquals(listOf(future), store.all())
    }

    @Test
    fun capabilityIsReportedFromThePlatformRatherThanAssumed() {
        val platform = RecordingPlatformScheduler(
            capability = AlertCapability(supported = true, exact = false, needsPermission = true),
        )
        val alerts = DefaultAlertScheduler(InMemoryAlertStore(), platform, FakeClock(T0))

        assertTrue(alerts.capability.supported)
        assertFalse(alerts.capability.exact)
        assertTrue(alerts.capability.needsPermission)
    }

    @Test
    fun theNotificationBodySaysHowLongHowHighAndWhichWay() {
        val body = alert(aos = T0 + 1.hours, maxElevationDeg = 78.4, aosAzimuthDeg = 271.0).body
        assertEquals("Pass in 10 min — max 78°, rises W", body)
    }

    @Test
    fun aShortLeadTimeReadsNaturally() {
        val body = alert(aos = T0 + 1.hours, leadTime = 30.seconds).body
        assertTrue(body.startsWith("Pass in under a minute"), body)
    }

    @Test
    fun anAlertsIdentityIsTheSatelliteAndItsOriginalAos() {
        val first = alert(aos = T0 + 1.hours)
        val second = alert(aos = T0 + 1.hours)
        assertEquals(first.id, second.id)
        assertTrue(first.id.startsWith("$ISS@"))
    }
}
