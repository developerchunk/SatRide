package com.developerstring.satride.core.auth

/**
 * Who is signed in.
 *
 * [Unknown] is a distinct state and not a convenience. Restoring a Firebase session is asynchronous,
 * so for the first fraction of a second after launch the honest answer is "we do not know yet" —
 * and that is completely different from "nobody is signed in". Collapsing the two makes the profile
 * tab flash its signed-out prompt at a signed-in user on every cold start, which reads as the app
 * having lost their account.
 */
sealed interface AuthState {
    data object Unknown : AuthState

    data object SignedOut : AuthState

    data class SignedIn(val user: SatRideUser) : AuthState

    val userOrNull: SatRideUser? get() = (this as? SignedIn)?.user

    /** True only once the answer is actually known. */
    val isResolved: Boolean get() = this != Unknown
}

/**
 * The account, as SatRide needs it.
 *
 * A deliberately thin projection of the Firebase user: uid, and the few display fields the profile
 * screen shows before a profile document exists. Everything else the app knows about a person lives
 * in [UserProfile], which the user controls and can edit — as distinct from what Google told us,
 * which they cannot.
 */
data class SatRideUser(
    val uid: String,
    val email: String? = null,
    /** From the Google account. The profile's own name overrides it once one is created. */
    val googleDisplayName: String? = null,
    val photoUrl: String? = null,
)

/**
 * What Google handed back.
 *
 * [accessToken] is null on Android and present on iOS, and that asymmetry is the point. Android's
 * Credential Manager returns an ID token only, and Firebase's Android SDK takes a nullable access
 * token. Firebase's *iOS* SDK does not — `credentialWithIDToken:accessToken:` is a nonnull
 * parameter there, so passing nothing produces a credential Firebase rejects, which surfaces as a
 * bare "sign-in failed" after the user has already picked their account and come back.
 */
data class GoogleTokens(val idToken: String, val accessToken: String? = null)

/** Why a sign-in did not complete. Separated because only one of these is worth an error message. */
sealed interface AuthFailure {
    /** The user backed out of the account picker. Not an error; say nothing. */
    data object Cancelled : AuthFailure

    /** No account on the device, or the picker had nothing to offer. */
    data object NoAccountAvailable : AuthFailure

    data class Network(val cause: Throwable?) : AuthFailure

    data class Unexpected(val cause: Throwable?) : AuthFailure

    /** This build cannot sign in at all — desktop, or an iOS build without the SDK linked. */
    data object Unsupported : AuthFailure
}

/** The message to show, or null when the failure is one the user caused on purpose. */
fun AuthFailure.userMessage(): String? = when (this) {
    AuthFailure.Cancelled -> null

    AuthFailure.NoAccountAvailable ->
        "No Google account on this device. Add one in Settings and try again."

    is AuthFailure.Network -> "Could not reach Google. Check your connection and try again."

    AuthFailure.Unsupported -> "Signing in is not available in this build."

    // Includes what actually went wrong. Firebase's own messages are written for people — "the
    // supplied auth credential is malformed or has expired" tells a user (and whoever they report
    // it to) far more than "please try again", which is indistinguishable from a network blip.
    is AuthFailure.Unexpected -> cause?.message?.let { "Sign-in failed: $it" } ?: "Sign-in failed. Please try again."
}
