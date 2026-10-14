package com.developerstring.satride.core.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The session on a build where Firebase is not available.
 *
 * Reached on desktop, which has no `google-services.json` and never initialises a `FirebaseApp`, and
 * on an iOS build before the SDK has been linked in Xcode. Reports [AuthState.SignedOut] — which is
 * the truth, and a state every screen already handles, because signing in was optional from the
 * start.
 *
 * The alternative was letting `Firebase.auth` throw at construction, which turns "this build cannot
 * sign in" into a crash the first time somebody opens the profile tab.
 */
class UnavailableAuthRepository : AuthRepository {
    override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedOut)

    override suspend fun signInWithGoogle(tokens: GoogleTokens): Result<SatRideUser> =
        Result.failure(IllegalStateException("Signing in is not available in this build."))

    override suspend fun signInWithEmail(email: String, password: String): Result<SatRideUser> =
        Result.failure(IllegalStateException("Signing in is not available in this build."))

    override suspend fun signOut() = Unit

    override suspend fun deleteAccount(): Result<Unit> =
        Result.failure(IllegalStateException("Signing in is not available in this build."))
}

/** Matching no-op profile store: nobody is signed in, so there is no profile to read or write. */
class UnavailableProfileRepository : ProfileRepository {
    override fun observe(): Flow<UserProfile?> = flowOf(null)

    override suspend fun save(profile: UserProfile): Result<Unit> =
        Result.failure(IllegalStateException("Sign in to save a profile."))

    override suspend fun delete(): Result<Unit> = Result.success(Unit)
}
