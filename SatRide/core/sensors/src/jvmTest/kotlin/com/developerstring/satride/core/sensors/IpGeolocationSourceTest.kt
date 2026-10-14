package com.developerstring.satride.core.sensors

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class IpGeolocationSourceTest {

    @Test
    fun parsesCoordinatesFromTheDocument() {
        val fix = parseIpApiFix("""{"ip":"203.0.113.9","city":"Pune","latitude":18.5204,"longitude":73.8567}""")
        assertNotNull(fix)
        assertEquals(18.5204, fix.position.latDeg)
        assertEquals(73.8567, fix.position.lonDeg)
        assertEquals(IP_FIX_ACCURACY_M, fix.accuracyM)
    }

    @Test
    fun rejectsErrorsAndGarbage() {
        assertNull(parseIpApiFix("""{"error":true,"reason":"RateLimited"}"""))
        assertNull(parseIpApiFix("""{"latitude":"n/a","longitude":73.8}"""))
        assertNull(parseIpApiFix("""{"latitude":91.0,"longitude":0.0}"""))
        assertNull(parseIpApiFix("<html>nope</html>"))
        assertNull(parseIpApiFix(""))
    }

    @Test
    fun looksUpOnceAndCachesTheAnswer() = runTest {
        var calls = 0
        val source = IpGeolocationSource(lookup = {
            calls++
            sampleFix
        })
        assertEquals(sampleFix, source.lastKnown())
        assertEquals(sampleFix, source.lastKnown())
        assertEquals(listOf(sampleFix), source.fixes().toList())
        assertEquals(1, calls)
    }

    @Test
    fun aFailureIsNotRetriedUntilTheCoolDownHasPassed() = runTest {
        var calls = 0
        val clock = SteppingClock(Instant.fromEpochMilliseconds(0))
        val source = IpGeolocationSource(lookup = {
            calls++
            error("offline")
        }, clock = clock)

        assertNull(source.lastKnown())
        assertNull(source.lastKnown())
        assertEquals(1, calls)

        clock.advance(6.minutes)
        assertNull(source.lastKnown())
        assertEquals(2, calls)
    }

    private val sampleFix = GeoFix(com.developerstring.satride.core.model.GeoPos(51.5, -0.1), IP_FIX_ACCURACY_M)

    private class SteppingClock(private var now: Instant) : Clock {
        override fun now(): Instant = now
        fun advance(by: kotlin.time.Duration) {
            now += by
        }
    }
}
