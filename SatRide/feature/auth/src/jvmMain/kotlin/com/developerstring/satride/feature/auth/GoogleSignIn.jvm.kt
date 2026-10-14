package com.developerstring.satride.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Google sign-in on the desktop: the system browser and a loopback redirect.
 *
 * There is no Credential Manager and no GoogleSignIn SDK on a JVM, so this is the flow Google
 * prescribes for installed applications — open the account chooser in the user's own browser,
 * receive the authorisation code on a one-shot `127.0.0.1` listener, and exchange it with PKCE.
 * The ID token that comes back is handed to the very same `AuthRepository` the phones use.
 *
 * It needs an OAuth client of type **Desktop app** from the Firebase project's Google Cloud
 * console; see [DesktopOAuthClient] for where the id is read from. Without one, or without a
 * `google-services.json`, the button is disabled with an explanation — the same honest state the
 * desktop build has always shown.
 */
@Composable
actual fun rememberGoogleSignIn(): GoogleSignInLauncher = remember { DesktopGoogleSignIn(DesktopOAuthClient.load()) }
