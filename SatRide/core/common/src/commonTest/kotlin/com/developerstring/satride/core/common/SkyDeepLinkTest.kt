package com.developerstring.satride.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The URL scheme, pinned.
 *
 * Deep links are the one part of the app that is exercised only from outside it — a notification
 * tap on Android, `onOpenURL` on iOS — so nothing in the ordinary run of the app would ever notice
 * this breaking. These tests are the notice.
 */
class SkyDeepLinkTest {

    @Test
    fun theCanonicalLinkRoundTrips() {
        val built = SkyDeepLink.guide(25544)
        assertEquals("satride://sky/detail/25544?guide=true", built)

        val parsed = SkyDeepLink.parse(built)
        assertEquals(SkyDeepLinkTarget(noradId = 25544, guide = true), parsed)
    }

    @Test
    fun guidingIsTheDefaultWhenTheFlagIsAbsent() {
        // Everything that produces one of these means "show me this one". A bare link landing on a
        // sky full of dots with nothing highlighted is a worse answer than the link asked for.
        assertEquals(SkyDeepLinkTarget(25544, guide = true), SkyDeepLink.parse("satride://sky/detail/25544"))
    }

    @Test
    fun guidingCanBeTurnedOff() {
        assertEquals(
            SkyDeepLinkTarget(25544, guide = false),
            SkyDeepLink.parse("satride://sky/detail/25544?guide=false"),
        )
    }

    @Test
    fun oneIsAcceptedForTrue() {
        assertTrue(SkyDeepLink.parse("satride://sky/detail/25544?guide=1")!!.guide)
    }

    @Test
    fun otherQueryParametersAreIgnored() {
        val parsed = SkyDeepLink.parse("satride://sky/detail/33591?from=notification&guide=true")
        assertEquals(SkyDeepLinkTarget(33591, guide = true), parsed)
    }

    @Test
    fun anythingThatIsNotOursIsRefused() {
        assertNull(SkyDeepLink.parse(null))
        assertNull(SkyDeepLink.parse(""))
        assertNull(SkyDeepLink.parse("https://example.com/sky/detail/25544"))
        assertNull(SkyDeepLink.parse("satride://radio/tune/25544"))
        assertNull(SkyDeepLink.parse("satride://sky/detail"))
        assertNull(SkyDeepLink.parse("satride://sky/25544"))
    }

    @Test
    fun aCatalogNumberThatIsNotANumberIsRefusedRatherThanGuessedAt() {
        assertNull(SkyDeepLink.parse("satride://sky/detail/ISS"))
        assertNull(SkyDeepLink.parse("satride://sky/detail/25544x"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        // A link pasted from a message often arrives with a newline attached.
        assertEquals(SkyDeepLinkTarget(25544, true), SkyDeepLink.parse("  satride://sky/detail/25544?guide=true\n"))
    }
}

/**
 * The guide bus.
 *
 * Small, and worth testing carefully: these behaviours are what make a notification tap work, and
 * every one of them was got wrong first time in a way no unit test then existing could see.
 */
class SkyGuideRequestsTest {

    @Test
    fun aRequestStaysPendingSoEveryReaderSeesIt() {
        val requests = DefaultSkyGuideRequests()
        assertNull(requests.pending.value)

        requests.request(25544)

        // Deliberately not cleared by reading. A request has two readers — the sky view, which
        // guides, and the app shell, which switches to the sky tab — and whichever one cleared it
        // first left the other with nothing. On a device that meant tapping a pass notification
        // from another tab did nothing at all.
        assertEquals(25544, requests.pending.value?.noradId)
        assertEquals(25544, requests.pending.value?.noradId)
    }

    @Test
    fun askingTwiceForTheSameSatelliteIsTwoRequests() {
        val requests = DefaultSkyGuideRequests()

        requests.request(25544)
        val first = requests.pending.value!!
        requests.request(25544)
        val second = requests.pending.value!!

        // Without the sequence number these two values are identical, StateFlow conflates the
        // second away, and tapping a second ISS notification does nothing.
        assertEquals(first.noradId, second.noradId)
        assertTrue(second.sequence > first.sequence)
    }

    @Test
    fun theSequenceOnlyEverGrows() {
        val requests = DefaultSkyGuideRequests()
        val sequences = (1..5).map {
            requests.request(25544 + it)
            requests.pending.value!!.sequence
        }
        assertEquals(sequences.sorted(), sequences)
        assertEquals(sequences.distinct(), sequences)
    }

    @Test
    fun theLatestRequestWins() {
        val requests = DefaultSkyGuideRequests()
        requests.request(25544)
        requests.request(33591)

        // A user who taps two notifications in a second means the second one.
        assertEquals(33591, requests.pending.value?.noradId)
    }

    @Test
    fun guidingCanBeTurnedOffPerRequest() {
        val requests = DefaultSkyGuideRequests()
        requests.request(25544, guide = false)
        assertEquals(false, requests.pending.value?.guide)
    }
}
