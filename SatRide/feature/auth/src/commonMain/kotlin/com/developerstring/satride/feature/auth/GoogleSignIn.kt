package com.developerstring.satride.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.developerstring.satride.core.auth.AuthFailure
import com.developerstring.satride.core.auth.GoogleTokens

/**
 * Obtains a Google ID token.
 *
 * This is the only part of signing in that is platform-specific, and it lives in the UI layer on
 * purpose: Android's Credential Manager needs an `Activity` and iOS needs a presenting view
 * controller. Both are things a composable has and a repository does not. The token then goes to
 * `AuthRepository`, which is ordinary common code and testable without a device.
 *
 * Modelled on `rememberCameraPermission` in `feature:arsky`, which solves the same shape of problem.
 */
@Stable
interface GoogleSignInLauncher {
    /** False where signing in cannot work at all — desktop, or iOS without the SDK linked. */
    val isAvailable: Boolean

    /**
     * Shows the account picker and returns an ID token.
     *
     * Suspends until the user chooses or dismisses. A dismissal is [AuthFailure.Cancelled] and is
     * not an error — the caller should say nothing at all.
     */
    suspend fun requestTokens(): Result<GoogleTokens>
}

@Composable
expect fun rememberGoogleSignIn(): GoogleSignInLauncher

/** For desktop and previews. */
internal object UnavailableGoogleSignIn : GoogleSignInLauncher {
    override val isAvailable: Boolean = false
    override suspend fun requestTokens(): Result<GoogleTokens> =
        Result.failure(GoogleSignInException(AuthFailure.Unsupported))
}

/** Carries an [AuthFailure] through `Result`, so the screen can tell a cancel from a real error. */
class GoogleSignInException(val failure: AuthFailure) : Exception(failure.toString())
