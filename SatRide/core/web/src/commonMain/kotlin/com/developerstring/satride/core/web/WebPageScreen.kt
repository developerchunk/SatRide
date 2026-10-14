package com.developerstring.satride.core.web

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * A legal page, hosted inside the app.
 *
 * The progress bar is the whole reason this wrapper exists rather than callers embedding
 * [InAppWebView] directly: a web view is a blank rectangle until the page paints, and on a slow
 * connection that is several seconds of a screen that looks broken. A bar that appears while
 * loading and leaves when done costs nothing and answers the only question the user has.
 */
@Composable
fun WebPageScreen(url: String, modifier: Modifier = Modifier) {
    var loading by remember(url) { mutableStateOf(true) }

    Box(modifier.fillMaxSize()) {
        InAppWebView(
            url = url,
            modifier = Modifier.fillMaxSize(),
            onLoadingChange = { loading = it },
        )

        AnimatedVisibility(
            visible = loading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LinearProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
