package com.developerstring.satride.core.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.net.URI

@Composable
actual fun rememberUriOpener(): (String) -> Unit = remember {
    { uri ->
        runCatching {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(uri))
        }
        Unit
    }
}
