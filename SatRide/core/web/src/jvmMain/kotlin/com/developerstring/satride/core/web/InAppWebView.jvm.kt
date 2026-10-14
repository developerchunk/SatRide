package com.developerstring.satride.core.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The desktop build has no embedded browser engine.
 *
 * Compose for Desktop ships no WebView, and pulling in JCEF to render four static documents would
 * add roughly a hundred megabytes to the download. So the page is opened in the user's own
 * browser the moment this screen appears — on a desktop that is where people read web pages anyway
 * — and the screen says what just happened, shows the address, and offers to open it again.
 */
@Composable
actual fun InAppWebView(url: String, modifier: Modifier, onLoadingChange: (Boolean) -> Unit) {
    val open = rememberUriOpener()
    LaunchedEffect(url) {
        onLoadingChange(false)
        open(url)
    }

    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "This page opened in your browser.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = { open(url) }) { Text("Open again") }
        }
    }
}

actual val inAppWebSupported: Boolean = false
