package com.developerstring.satride.core.web

import androidx.compose.runtime.Composable

/**
 * Hands a URI to the platform — the mail client, mostly.
 *
 * Distinct from [InAppWebView] on purpose. Web pages stay inside the app; a `mailto:` cannot,
 * because composing mail is the mail app's job and no web view can do it. This is the one case
 * where leaving is the correct behaviour rather than a failure to keep the user.
 */
@Composable
expect fun rememberUriOpener(): (String) -> Unit
