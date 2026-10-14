package com.developerstring.satride.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.developerstring.satride.core.auth.AuthFailure
import com.developerstring.satride.core.auth.GoogleTokens
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google sign-in on iOS, supplied by Swift.
 *
 * Kotlin cannot call the GoogleSignIn SDK directly: it is a Swift/ObjC package added to the Xcode
 * project, and this module has no cinterop for it. Introducing one would mean checking a generated
 * `.def` and a framework path into the Gradle build and keeping them in step with whatever SPM
 * resolves — for a single call that returns a single string.
 *
 * So the dependency is inverted. `iOSApp.swift` presents `GIDSignIn`, and hands the resulting ID
 * token back through [IosGoogleSignIn.handler]. Kotlin stays free of the SDK, and the Xcode side
 * owns the part that genuinely belongs to it — presenting a view controller.
 *
 * Until Swift registers a handler, [isAvailable] is false and the sign-in button is disabled with
 * an honest explanation rather than a button that does nothing.
 */
@Composable
actual fun rememberGoogleSignIn(): GoogleSignInLauncher = remember { IosGoogleSignIn }

object IosGoogleSignIn : GoogleSignInLauncher {

    /**
     * Set from Swift at launch.
     *
     * The callback takes a completion of `(idToken, accessToken, errorDescription)`: either the
     * first two are set or the last one is. A plain function type keeps the Objective-C bridge
     * trivial; a suspend function would arrive in Swift as a completion handler anyway.
     *
     * The access token matters — Firebase's iOS SDK rejects a credential built without one. See
     * [GoogleTokens].
     */
    var handler: ((completion: (String?, String?, String?) -> Unit) -> Unit)? = null

    override val isAvailable: Boolean get() = handler != null

    override suspend fun requestTokens(): Result<GoogleTokens> {
        val current = handler
            ?: return Result.failure(GoogleSignInException(AuthFailure.Unsupported))

        return suspendCancellableCoroutine { continuation ->
            current { idToken, accessToken, error ->
                when {
                    idToken != null -> continuation.resume(
                        Result.success(GoogleTokens(idToken = idToken, accessToken = accessToken)),
                    )

                    // Swift reports a user-cancelled flow with this exact string, so the app can
                    // stay silent for it rather than showing an error the user caused on purpose.
                    error == CANCELLED -> continuation.resume(
                        Result.failure(GoogleSignInException(AuthFailure.Cancelled)),
                    )

                    else -> continuation.resume(
                        Result.failure(GoogleSignInException(AuthFailure.Unexpected(IllegalStateException(error)))),
                    )
                }
            }
        }
    }

    /** The sentinel Swift sends when the user dismissed the sheet. */
    const val CANCELLED: String = "cancelled"
}
