package com.developerstring.satride.core.auth

import co.touchlab.kermit.Logger
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.FirebaseAuth
import dev.gitlive.firebase.auth.GoogleAuthProvider
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The session.
 *
 * Sign-in is *optional* in SatRide, and this interface is written so that nothing can quietly forget
 * it: there is no `requireUser()`, no suspending `currentUser()` that blocks a screen, and
 * [AuthState.SignedOut] is an ordinary value rather than an error. Every screen that existed before
 * accounts did must keep working for someone who never signs in.
 */
interface AuthRepository {
    val state: StateFlow<AuthState>

    /**
     * Exchanges a Google ID token for a Firebase session.
     *
     * Takes the token rather than fetching it, because obtaining one needs an Activity on Android
     * and a view controller on iOS — both of which belong to the UI layer. That split is also what
     * makes this method testable without a device.
     */
    suspend fun signInWithGoogle(tokens: GoogleTokens): Result<SatRideUser>

    /**
     * Signs in with an email and password.
     *
     * Exists for **reviewers and internal testers**, not for the public: there is no registration
     * form anywhere in the app, so the only accounts that can use it are ones created by hand in
     * the Firebase console. Google's review process needs a way into the signed-in half of the app
     * that does not involve sharing a personal Google account — Google's own risk checks routinely
     * block a shared Google account signing in from review infrastructure, and sharing one breaks
     * their account terms — and this is that way.
     *
     * Deliberately the plainest possible flow: no second factor, no email link, no one-time code.
     * A reviewer who has to complete a challenge on a device they do not own cannot review the app.
     */
    suspend fun signInWithEmail(email: String, password: String): Result<SatRideUser>

    suspend fun signOut()

    /** Removes the Firebase Auth user. The profile document is deleted separately, first. */
    suspend fun deleteAccount(): Result<Unit>
}

class FirebaseAuthRepository(scope: CoroutineScope, private val auth: FirebaseAuth = Firebase.auth) : AuthRepository {

    private val logger = Logger.withTag("Auth")

    override val state: StateFlow<AuthState> = auth.authStateChanged
        // An anonymous session is not an account. Multi-Play creates one so a guest can hold a
        // seat in a room the security rules can attribute; the profile tab must keep offering
        // sign-in to that person, and nothing gated on "signed in" should open for them.
        .map { user ->
            if (user == null || user.isAnonymous) AuthState.SignedOut else AuthState.SignedIn(user.toSatRideUser())
        }
        // Catching here, not only around the constructor.
        //
        // `Firebase.auth` resolves fine on a platform with no `FirebaseApp` — it is the *flow* that
        // throws, asynchronously, when it is first collected. On desktop that surfaced as
        // "Default FirebaseApp is not initialized in this process" thrown into the app scope: the
        // supervisor kept the app alive, so it worked, but every launch printed a stack trace that
        // looks exactly like a crash. Signed out is the truthful answer for a build that cannot
        // sign in.
        .catch { error ->
            logger.i { "Firebase auth unavailable in this build (${error.message}); treating as signed out" }
            emit(AuthState.SignedOut)
        }
        .stateIn(
            scope = scope,
            // Eagerly, and seeded with Unknown: the session is restored asynchronously, and a
            // WhileSubscribed flow would leave the first collector waiting on a value that only
            // arrives when someone is already listening.
            started = SharingStarted.Eagerly,
            initialValue = AuthState.Unknown,
        )

    override suspend fun signInWithGoogle(tokens: GoogleTokens): Result<SatRideUser> = runCatching {
        // The access token is passed through rather than dropped. SatRide never calls a Google API on
        // the user's behalf, so it has no use for one — but Firebase's iOS SDK takes it as a nonnull
        // parameter, and a credential built without it is rejected *after* the user has picked their
        // account and returned to the app. See [GoogleTokens].
        val credential = GoogleAuthProvider.credential(
            idToken = tokens.idToken,
            accessToken = tokens.accessToken,
        )
        val result = auth.signInWithCredential(credential)
        val user = result.user ?: error("Firebase accepted the credential but returned no user.")
        user.toSatRideUser()
    }.onFailure { logger.w(it) { "Google sign-in failed" } }

    override suspend fun signInWithEmail(email: String, password: String): Result<SatRideUser> = runCatching {
        val result = auth.signInWithEmailAndPassword(email.trim(), password)
        val user = result.user ?: error("Firebase accepted the credentials but returned no user.")
        user.toSatRideUser()
    }.onFailure {
        // The email is not logged. It identifies a person, and a failed sign-in is exactly the
        // event most likely to be pasted into a bug report.
        logger.w(it) { "email sign-in failed" }
    }

    override suspend fun signOut() {
        runCatching { auth.signOut() }
            .onFailure { logger.w(it) { "sign-out failed" } }
    }

    override suspend fun deleteAccount(): Result<Unit> = runCatching {
        val user = auth.currentUser ?: error("No signed-in user to delete.")
        user.delete()
    }.onFailure { logger.w(it) { "account deletion failed" } }
}

private fun dev.gitlive.firebase.auth.FirebaseUser.toSatRideUser(): SatRideUser = SatRideUser(
    uid = uid,
    email = email,
    googleDisplayName = displayName,
    photoUrl = photoURL,
)
