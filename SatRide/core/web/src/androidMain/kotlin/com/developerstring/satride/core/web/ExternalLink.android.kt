package com.developerstring.satride.core.web

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberUriOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { uri ->
            // Swallowed rather than crashed: a device with no mail client configured is unusual
            // but entirely legal, and taking the app down because somebody tapped an email address
            // is not a reasonable response to it.
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { if (it !is ActivityNotFoundException) throw it }
            Unit
        }
    }
}
