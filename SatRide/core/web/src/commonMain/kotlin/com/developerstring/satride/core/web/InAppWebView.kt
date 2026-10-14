package com.developerstring.satride.core.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/**
 * A web page rendered **inside** the app.
 *
 * Not a Custom Tab or an `SFSafariViewController`. Those are the usual advice and they are still a
 * browser: the app disappears, a URL bar and a "Done" button belonging to someone else appear, and
 * on Android the page may open in whatever browser the user has defaulted to. For the four pages
 * this exists to show — privacy, terms, data handling, deletion — the point is that the user never
 * leaves, so this is a real `WebView` / `WKWebView` embedded in the composition.
 *
 * Deliberately minimal: no JavaScript bridge, no file access, no cookie sharing with the system
 * browser. It renders static documents and nothing else, and every capability not needed for that
 * is a capability that cannot be abused.
 */
@Composable
expect fun InAppWebView(url: String, modifier: Modifier = Modifier, onLoadingChange: (Boolean) -> Unit = {})

/**
 * Whether this platform can render a page in-app at all.
 *
 * False on the desktop build, where there is no embedded engine. Callers use it to decide between
 * showing the page and offering the URL as text — which is honest, where a button that silently
 * does nothing is not.
 */
expect val inAppWebSupported: Boolean

/** The pages the app links to, all served by the SatRide site. */
@Immutable
enum class LegalPage(val title: String, val path: String) {
    PRIVACY("Privacy Policy", "/privacy"),
    TERMS("Terms & Conditions", "/terms"),
    DATA_HANDLING("Data Handling", "/data"),
    DELETE_ACCOUNT("Delete Your Account", "/delete-account"),
    ;

    val url: String get() = SITE_ROOT + path

    companion object {
        /**
         * The site root, in one place.
         *
         * Also the value the Play listing's privacy-policy field must match; if this ever moves,
         * the store entry moves with it or Google fetches a dead link.
         */
        const val SITE_ROOT: String = "https://satride.developerstring.com"

        /** Where support mail goes. Shown on the About screen and in the store listing. */
        const val CONTACT_EMAIL: String = "support@developerstring.com"
    }
}
