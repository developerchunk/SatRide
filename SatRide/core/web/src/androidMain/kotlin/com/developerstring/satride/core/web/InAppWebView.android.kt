package com.developerstring.satride.core.web

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The app's own dark navy, as an ARGB int.
 *
 * The WebView is given a **solid** background rather than a transparent one. A transparent Android
 * WebView is a well-known way to get a blank page: on a hardware-accelerated view the transparent
 * surface and the page's own compositing fight, and the result on a good number of devices is that
 * the content never paints at all — which is exactly "the web view is not opening". A solid colour
 * that matches the app costs nothing and renders reliably; the brief flash before the page paints
 * is this navy rather than white.
 */
private const val APP_BACKGROUND: Int = 0xFF060B1E.toInt()

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun InAppWebView(url: String, modifier: Modifier, onLoadingChange: (Boolean) -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onLoadingChange(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoadingChange(false)
                    }

                    // A failed load must not leave the spinner turning forever. Only the main
                    // document's failure counts — a page routinely has a sub-resource 404 that
                    // says nothing about whether the page itself loaded.
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) onLoadingChange(false)
                    }

                    /**
                     * Keeps every navigation inside this view.
                     *
                     * Returning false would hand off-site links to the system browser, which is
                     * precisely what this component exists to avoid. The pages it loads only link
                     * to each other and to a `mailto:`, and a `mailto:` has no business being
                     * loaded as a page either — so anything that is not http(s) is refused rather
                     * than fired off as an intent.
                     */
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val scheme = request?.url?.scheme?.lowercase()
                        return scheme != "http" && scheme != "https"
                    }
                }

                settings.apply {
                    // The site is a React SPA: without JavaScript it renders an empty div.
                    javaScriptEnabled = true
                    // Everything else stays off. None of it is needed to show four static
                    // documents, and each one is a way for a compromised page to reach further
                    // than it should.
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = true
                    databaseEnabled = false
                    setGeolocationEnabled(false)
                    // The pages are responsive; let them lay out to the viewport rather than
                    // rendering a desktop page scaled down to illegibility.
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = false
                }

                // Solid, not transparent — see [APP_BACKGROUND]. This is the fix for a WebView
                // that renders blank.
                setBackgroundColor(APP_BACKGROUND)
                loadUrl(url)
            }
        },
        // Only reloads when the URL actually changes; a recomposition alone must not restart the
        // page and throw away the user's scroll position.
        update = { view -> if (view.url != url) view.loadUrl(url) },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
        },
    )
}

actual val inAppWebSupported: Boolean = true
