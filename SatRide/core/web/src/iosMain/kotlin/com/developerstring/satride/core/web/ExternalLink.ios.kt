package com.developerstring.satride.core.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

@Composable
actual fun rememberUriOpener(): (String) -> Unit = remember {
    { uri ->
        NSURL.URLWithString(uri)?.let { url ->
            UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>(), null)
        }
        Unit
    }
}
