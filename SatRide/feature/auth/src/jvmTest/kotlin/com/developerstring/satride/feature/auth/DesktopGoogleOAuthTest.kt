package com.developerstring.satride.feature.auth

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopGoogleOAuthTest {

    @Test
    fun pkceChallengeMatchesTheRfcTestVector() {
        // RFC 7636, appendix B.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OAuthPkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifierIsWithinTheRfcLength() {
        val verifier = OAuthPkce.verifier()
        assertTrue(verifier.length in 43..128, "length ${verifier.length}")
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun authorizationUrlCarriesEveryRequiredParameter() {
        val url = authorizationUrl(
            clientId = "abc.apps.googleusercontent.com",
            redirectUri = "http://127.0.0.1:4242/oauth2/callback",
            state = "st ate",
            codeChallenge = "chal",
        )
        assertTrue(url.startsWith("$GOOGLE_AUTHORIZATION_ENDPOINT?"))
        val params = parseQuery(url.substringAfter('?'))
        assertEquals("abc.apps.googleusercontent.com", params["client_id"])
        assertEquals("http://127.0.0.1:4242/oauth2/callback", params["redirect_uri"])
        assertEquals("code", params["response_type"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals("chal", params["code_challenge"])
        assertEquals("st ate", params["state"])
        assertEquals("select_account", params["prompt"])
        assertTrue(params.getValue("scope").split(' ').containsAll(listOf("openid", "email", "profile")))
    }

    @Test
    fun callbackWithTheRightStateYieldsTheCode() {
        val outcome = interpretCallback(mapOf("state" to "s1", "code" to "4/abc"), expectedState = "s1")
        assertEquals(CallbackOutcome.Code("4/abc"), outcome)
    }

    @Test
    fun callbackWithTheWrongStateIsRejected() {
        assertIs<CallbackOutcome.Invalid>(interpretCallback(mapOf("state" to "other", "code" to "4/abc"), "s1"))
    }

    @Test
    fun userDenialIsACancelNotAnError() {
        assertEquals(
            CallbackOutcome.Denied,
            interpretCallback(mapOf("state" to "s1", "error" to "access_denied"), "s1"),
        )
    }

    @Test
    fun tokenResponseIsParsed() {
        val tokens = parseTokenResponse(200, """{"access_token":"at","id_token":"idt","expires_in":3599}""")
        assertEquals("idt", tokens.idToken)
        assertEquals("at", tokens.accessToken)
    }

    @Test
    fun tokenErrorSurfacesGooglesDescription() {
        val failure = assertFailsWith<IllegalStateException> {
            parseTokenResponse(400, """{"error":"invalid_grant","error_description":"Bad Request"}""")
        }
        assertTrue("Bad Request" in failure.message.orEmpty())
    }

    @Test
    fun clientNeedsAtLeastAnId() {
        assertNull(DesktopOAuthClient.fromProperties(Properties()))
        val client = DesktopOAuthClient.fromProperties(Properties().apply { setProperty("client_id", " id ") })
        assertEquals(DesktopOAuthClient("id", null), client)
        assertEquals(
            DesktopOAuthClient("id", "sec"),
            DesktopOAuthClient.fromEnvironment(
                mapOf(DesktopOAuthClient.ENV_CLIENT_ID to "id", DesktopOAuthClient.ENV_CLIENT_SECRET to "sec"),
            ),
        )
    }
}
