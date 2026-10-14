package com.developerstring.satride.feature.arsky.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.developerstring.satride.feature.arsky.projection.CameraFov

/**
 * No camera on the desktop build.
 *
 * The desktop app exists to exercise the shared code, not to be shipped, and there is no
 * meaningful AR without a phone to point. Reporting unavailability puts the sky view straight
 * into planetarium mode — which is also the mode the desktop build is genuinely useful in, since
 * it is where the renderer can be worked on without a device.
 */
@Composable
actual fun CameraLayer(modifier: Modifier, onFov: (CameraFov) -> Unit, onUnavailable: () -> Unit) {
    LaunchedEffect(Unit) { onUnavailable() }
}
