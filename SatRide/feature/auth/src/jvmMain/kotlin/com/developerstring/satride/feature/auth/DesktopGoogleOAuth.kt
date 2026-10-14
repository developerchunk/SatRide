package com.developerstring.satride.feature.auth

import co.touchlab.kermit.Logger
import com.developerstring.satride.core.auth.AuthFailure
import com.developerstring.satride.core.auth.DesktopFirebase
import com.developerstring.satride.core.auth.GoogleTokens
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import kotlin.time.Duration.Companion.minutes
import java.time.Duration as JavaDuration

/**
 * The OAuth client the desktop build signs in with.
 *
 * Google issues these per platform, and the Android and web ids in `google-services.json` cannot be
 * used from a loopback redirect — only a client of type *Desktop app* accepts one. Creating that
 * client is a one-minute job in the Cloud console, and the result is read from the first of:
 *
 * 1. a `google-oauth-desktop.properties` resource on the classpath (the desktop app copies it from
 *    its `src/main/resources`; the file is git-ignored),
 * 2. `~/.satride/google-oauth-desktop.properties`,
 * 3. the `SATRIDE_GOOGLE_DESKTOP_CLIENT_ID` / `SATRIDE_GOOGLE_DESKTOP_CLIENT_SECRET` environment.
 *
 * The "secret" of a desktop client is not one — Google's own guidance is that it cannot be kept
 * confidential in an installed app — but the token endpoint still wants it sent, so it is carried.
 */
internal data class DesktopOAuthClient(val clientId: String, val clientSecret: String?) {

    companion object {
        const val RESOURCE: String = "/google-oauth-desktop.properties"
        const val HOME_FILE: String = ".satride/google-oauth-desktop.properties"
        const val ENV_CLIENT_ID: String = "SATRIDE_GOOGLE_DESKTOP_CLIENT_ID"
        const val ENV_CLIENT_SECRET: String = "SATRIDE_GOOGLE_DESKTOP_CLIENT_SECRET"

        fun load(
            home: File = File(System.getProperty("user.home")),
            environment: Map<String, String> = System.getenv(),
        ): DesktopOAuthClient? = fromResource() ?: fromFile(File(home, HOME_FILE)) ?: fromEnvironment(environment)

        internal fun fromProperties(properties: Properties): DesktopOAuthClient? {
            val id = properties.getProperty("client_id")?.trim().orEmpty()
            if (id.isEmpty()) return null
            return DesktopOAuthClient(
                clientId = id,
                clientSecret = properties.getProperty("client_secret")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        internal fun fromEnvironment(environment: Map<String, String>): DesktopOAuthClient? {
            val id = environment[ENV_CLIENT_ID]?.trim().orEmpty()
            if (id.isEmpty()) return null
            return DesktopOAuthClient(id, environment[ENV_CLIENT_SECRET]?.trim()?.takeIf { it.isNotEmpty() })
        }

        private fun fromResource(): DesktopOAuthClient? = DesktopOAuthClient::class.java.getResourceAsStream(RESOURCE)
            ?.use { stream -> fromProperties(Properties().apply { load(stream) }) }

        private fun fromFile(file: File): DesktopOAuthClient? = if (file.isFile) {
            runCatching { file.inputStream().use { fromProperties(Properties().apply { load(it) }) } }.getOrNull()
        } else {
            null
        }
    }
}

/** RFC 7636. Pure functions, so the desktop flow's one piece of cryptography is unit-testable. */
internal object OAuthPkce {
    private val random = SecureRandom()

    /** 64 random bytes, base64url without padding: 86 characters, inside the 43–128 the RFC allows. */
    fun verifier(): String = randomToken(64)

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun state(): String = randomToken(24)

    private fun randomToken(bytes: Int): String = base64Url(ByteArray(bytes).also(random::nextBytes))

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal const val GOOGLE_AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
internal const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
internal const val CALLBACK_PATH = "/oauth2/callback"

/** The account-chooser URL. `prompt=select_account` because the app promises to never auto-pick. */
internal fun authorizationUrl(clientId: String, redirectUri: String, state: String, codeChallenge: String): String {
    val params = linkedMapOf(
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "response_type" to "code",
        "scope" to "openid email profile",
        "state" to state,
        "code_challenge" to codeChallenge,
        "code_challenge_method" to "S256",
        "access_type" to "online",
        "prompt" to "select_account",
    )
    return GOOGLE_AUTHORIZATION_ENDPOINT + "?" + params.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
}

internal fun parseQuery(query: String?): Map<String, String> {
    if (query.isNullOrBlank()) return emptyMap()
    return query.split('&').mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        URLDecoder.decode(part.substring(0, eq), Charsets.UTF_8) to
            URLDecoder.decode(part.substring(eq + 1), Charsets.UTF_8)
    }.toMap()
}

/** What the browser brought back, sorted into the three things the caller can do about it. */
internal sealed interface CallbackOutcome {
    data class Code(val code: String) : CallbackOutcome

    /** The user pressed Cancel on Google's page. Not an error; say nothing. */
    data object Denied : CallbackOutcome

    data class Invalid(val reason: String) : CallbackOutcome
}

internal fun interpretCallback(params: Map<String, String>, expectedState: String): CallbackOutcome {
    if (params["state"] !=
        expectedState
    ) {
        return CallbackOutcome.Invalid("state mismatch — the response did not come from this sign-in")
    }
    params["error"]?.let { error ->
        return if (error ==
            "access_denied"
        ) {
            CallbackOutcome.Denied
        } else {
            CallbackOutcome.Invalid("Google reported: $error")
        }
    }
    val code =
        params["code"]?.takeIf { it.isNotBlank() }
            ?: return CallbackOutcome.Invalid("no authorisation code in the response")
    return CallbackOutcome.Code(code)
}

/** Exchanges the authorisation code. Only the two tokens the app needs are read out of the reply. */
internal class GoogleTokenEndpoint(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(JavaDuration.ofSeconds(15)).build(),
    private val endpoint: String = GOOGLE_TOKEN_ENDPOINT,
) {
    suspend fun exchange(
        client: DesktopOAuthClient,
        code: String,
        redirectUri: String,
        verifier: String,
    ): GoogleTokens {
        val form = buildList {
            add("code" to code)
            add("client_id" to client.clientId)
            client.clientSecret?.let { add("client_secret" to it) }
            add("redirect_uri" to redirectUri)
            add("grant_type" to "authorization_code")
            add("code_verifier" to verifier)
        }.joinToString("&") { (k, v) -> "$k=${encode(v)}" }

        val request = HttpRequest.newBuilder(URI(endpoint))
            .timeout(JavaDuration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        return parseTokenResponse(response.statusCode(), response.body())
    }
}

internal fun parseTokenResponse(status: Int, body: String): GoogleTokens {
    val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    if (status !in 200..299) {
        val detail = json?.get("error_description")?.jsonPrimitive?.content
            ?: json?.get("error")?.jsonPrimitive?.content
            ?: "HTTP $status"
        error("Google's token endpoint refused the code: $detail")
    }
    val idToken = json?.get("id_token")?.jsonPrimitive?.content
        ?: error("Google's token response carried no ID token")
    return GoogleTokens(idToken = idToken, accessToken = json["access_token"]?.jsonPrimitive?.content)
}

private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

/**
 * The launcher itself.
 *
 * One flow at a time: the listener is created when the user presses the button and torn down the
 * moment a response arrives, the wait times out, or the calling coroutine is cancelled. Nothing
 * listens on the machine while nobody is signing in.
 */
internal class DesktopGoogleSignIn(
    private val client: DesktopOAuthClient?,
    private val tokenEndpoint: GoogleTokenEndpoint = GoogleTokenEndpoint(),
    private val openInBrowser: (String) -> Unit = ::browse,
) : GoogleSignInLauncher {

    private val logger = Logger.withTag("GoogleSignIn")

    /** Both halves are needed: a client to ask Google, and a Firebase app to hand the answer to. */
    override val isAvailable: Boolean
        get() = client != null && DesktopFirebase.isInitialized

    override suspend fun requestTokens(): Result<GoogleTokens> {
        val client = client ?: return Result.failure(GoogleSignInException(AuthFailure.Unsupported))
        if (!DesktopFirebase.isInitialized) return Result.failure(GoogleSignInException(AuthFailure.Unsupported))

        return withContext(Dispatchers.IO) {
            runCatching { run(client) }
                .recoverCatching { error ->
                    if (error is GoogleSignInException) throw error
                    logger.w(error) { "desktop sign-in failed" }
                    throw GoogleSignInException(AuthFailure.Unexpected(error))
                }
        }
    }

    private suspend fun run(client: DesktopOAuthClient): GoogleTokens {
        val verifier = OAuthPkce.verifier()
        val state = OAuthPkce.state()
        val callback = CompletableDeferred<Map<String, String>>()

        // Port 0: the OS picks a free one, which is exactly what Google's loopback rule allows —
        // a Desktop client accepts any port on 127.0.0.1 without it being registered first.
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        try {
            server.createContext(CALLBACK_PATH) { exchange ->
                val params = parseQuery(exchange.requestURI.rawQuery)
                val page = if (params["error"] == null) SUCCESS_PAGE else CANCELLED_PAGE
                val bytes = page.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                callback.complete(params)
            }
            server.start()

            val redirectUri = "http://127.0.0.1:${server.address.port}$CALLBACK_PATH"
            openInBrowser(authorizationUrl(client.clientId, redirectUri, state, OAuthPkce.challenge(verifier)))

            // A browser tab closed without choosing sends nothing back, so a silent wait has to
            // end somewhere. Treated as a cancel, which is what it was.
            val params = withTimeoutOrNull(SIGN_IN_TIMEOUT) { callback.await() }
                ?: throw GoogleSignInException(AuthFailure.Cancelled)

            return when (val outcome = interpretCallback(params, state)) {
                is CallbackOutcome.Code -> tokenEndpoint.exchange(client, outcome.code, redirectUri, verifier)

                CallbackOutcome.Denied -> throw GoogleSignInException(AuthFailure.Cancelled)

                is CallbackOutcome.Invalid ->
                    throw GoogleSignInException(AuthFailure.Unexpected(IllegalStateException(outcome.reason)))
            }
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        val SIGN_IN_TIMEOUT = 5.minutes

        val SUCCESS_PAGE = page(
            "You're signed in to SatRide",
            "You can close this tab and go back to the app.",
        )
        val CANCELLED_PAGE = page(
            "Sign-in cancelled",
            "Nothing was changed. You can close this tab.",
        )

        fun page(title: String, body: String): String =
            """
            <!doctype html><html><head><meta charset="utf-8"><title>$title</title>
            <style>body{font-family:-apple-system,system-ui,sans-serif;background:#0b1020;color:#f4f6ff;
            display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
            main{text-align:center;padding:2rem}h1{font-weight:700;margin:0 0 .5rem}p{opacity:.8;margin:0}</style>
            </head><body><main><h1>$title</h1><p>$body</p></main></body></html>
            """.trimIndent()
    }
}

private fun browse(url: String) {
    check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        "No browser can be opened from this desktop session."
    }
    Desktop.getDesktop().browse(URI(url))
}
