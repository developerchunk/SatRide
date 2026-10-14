package com.developerstring.satride.core.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIColor
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun InAppWebView(url: String, modifier: Modifier, onLoadingChange: (Boolean) -> Unit) {
    // The delegate is remembered separately from the view: WKWebView holds it *weakly*, so a
    // delegate created inline is collected almost immediately and the loading callbacks silently
    // stop arriving. Holding it here keeps it alive for as long as the view is composed.
    val delegate = remember {
        object : NSObject(), WKNavigationDelegateProtocol {
            // All three callbacks erase to the same Kotlin signature — (WKWebView, WKNavigation?)
            // — because Objective-C distinguishes them by selector keyword and Kotlin does not.
            // `@ObjCSignatureOverride` is what tells the compiler that is intended rather than a
            // redeclaration.
            @ObjCSignatureOverride
            override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
                onLoadingChange(true)
            }

            @ObjCSignatureOverride
            override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
                onLoadingChange(false)
            }

            @ObjCSignatureOverride
            override fun webView(
                webView: WKWebView,
                didFailProvisionalNavigation: WKNavigation?,
                withError: platform.Foundation.NSError,
            ) {
                onLoadingChange(false)
            }
        }
    }

    val webView = remember {
        WKWebView(frame = CGRectZero.readValue(), configuration = WKWebViewConfiguration()).apply {
            navigationDelegate = delegate
            // Transparent, so the app's dark background shows through rather than a white flash
            // while the page paints.
            setOpaque(false)
            backgroundColor = UIColor.clearColor
            scrollView.backgroundColor = UIColor.clearColor
        }
    }

    DisposableEffect(url) {
        NSURL.URLWithString(url)?.let { webView.loadRequest(NSURLRequest(it)) }
        onDispose { }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.navigationDelegate = null
        }
    }

    UIKitView(factory = { webView }, modifier = modifier)
}

actual val inAppWebSupported: Boolean = true
