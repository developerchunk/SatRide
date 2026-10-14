package com.developerstring.satride.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CountriesTest {

    @Test
    fun theListIsCompleteEnoughAndHasNoDuplicateCodes() {
        assertTrue(Countries.all.size > 240, "expected the full ISO list, got ${Countries.all.size}")
        assertEquals(Countries.all.size, Countries.all.map { it.code }.distinct().size)
    }

    @Test
    fun everyCodeIsTwoUppercaseLetters() {
        val bad = Countries.all.filterNot { it.code.matches(Regex("^[A-Z]{2}$")) }
        assertTrue(bad.isEmpty(), "malformed codes: $bad")
    }

    @Test
    fun prefixMatchesComeBeforeContainsMatches() {
        val results = Countries.search("in")
        val india = results.indexOfFirst { it.code == "IN" }
        val argentina = results.indexOfFirst { it.code == "AR" }

        // Someone typing "in" wants India, not Argentina — even though both contain the letters.
        assertTrue(india in 0..3, "India should be near the top, was at $india")
        assertTrue(india < argentina, "prefix matches must outrank contains matches")
    }

    @Test
    fun anExactCodeMatchOutranksACountryWhoseNameStartsTheSameWay() {
        // "de" is both Germany's code and the first two letters of Denmark. A two-letter query
        // that is a valid code is almost always someone typing the code.
        assertEquals("Germany", Countries.search("de").first().name)
        assertEquals("India", Countries.search("IN").first().name)
        // Denmark is still offered, just below.
        assertTrue(Countries.search("de").any { it.code == "DK" })
    }

    @Test
    fun searchIsCaseAndWhitespaceInsensitive() {
        assertEquals(Countries.search("india").first(), Countries.search("  INDIA  ").first())
    }

    @Test
    fun anEmptyQueryReturnsEverything() {
        assertEquals(Countries.all.size, Countries.search("").size)
        assertEquals(Countries.all.size, Countries.search("   ").size)
    }

    @Test
    fun nothingMatchesNonsense() {
        assertTrue(Countries.search("zzzzqq").isEmpty())
    }

    @Test
    fun flagsAreDerivedFromTheCode() {
        // Two regional indicator symbols, each a surrogate pair — so four UTF-16 units.
        val india = Countries.byCode("IN")!!
        assertEquals(4, india.flag.length)
        assertEquals("🇮🇳", india.flag)
    }

    @Test
    fun lookupByCodeIsCaseInsensitiveAndSafe() {
        assertEquals("Japan", Countries.byCode("jp")?.name)
        assertEquals("Japan", Countries.byCode("JP")?.name)
        assertNull(Countries.byCode(null))
        assertNull(Countries.byCode("XX"))
    }
}

class UserProfileTest {

    @Test
    fun trailingWhitespaceFromAKeyboardIsRemoved() {
        // A soft keyboard's autocomplete adds a trailing space, and a stored name with one renders
        // with a gap the user can neither see nor delete.
        val profile = UserProfile.of("  Aditya ", null, null)
        assertEquals("Aditya", profile.displayName)
    }

    @Test
    fun blankOptionalFieldsBecomeNullSoThatUnansweredHasOneRepresentation() {
        val profile = UserProfile.of("Aditya", "   ", "  ")
        assertNull(profile.countryCode)
        assertNull(profile.referredBy)
    }

    @Test
    fun countryCodeIsNormalisedToUppercase() {
        assertEquals("IN", UserProfile.of("A", "in", null).countryCode)
    }

    @Test
    fun aMalformedCountryCodeIsDroppedRatherThanStored() {
        // The Firestore rules require exactly two uppercase letters, so anything else would be
        // rejected at write time — better to drop it here than to fail the save.
        assertNull(UserProfile.of("A", "India", null).countryCode)
        assertNull(UserProfile.of("A", "I", null).countryCode)
    }

    @Test
    fun overlongInputIsTruncatedToWhatTheRulesAccept() {
        val long = "x".repeat(200)
        val profile = UserProfile.of(long, null, long)
        assertEquals(UserProfile.MAX_NAME_LENGTH, profile.displayName.length)
        assertEquals(UserProfile.MAX_REFERRAL_LENGTH, profile.referredBy?.length)
    }

    @Test
    fun aNameIsRequiredAndWhitespaceIsNotAName() {
        assertTrue(UserProfile.of("Aditya", null, null).isValid)
        assertTrue(!UserProfile.of("   ", null, null).isValid)
    }
}
