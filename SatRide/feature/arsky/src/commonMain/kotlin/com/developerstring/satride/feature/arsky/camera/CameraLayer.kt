package com.developerstring.satride.feature.arsky.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.developerstring.satride.feature.arsky.projection.CameraFov

/**
 * The live camera image the sky is drawn over.
 *
 * Bottom of the AR stack and deliberately dumb: it shows a preview and reports the field of view
 * it is showing it at. It knows nothing about satellites, and the sky layer above it knows
 * nothing about cameras, so planetarium mode is the same renderer with this layer absent rather
 * than a second code path.
 *
 * Renders nothing at all when there is no camera to show — permission refused, no camera
 * hardware, or a platform with no preview (the desktop build). The screen treats that as the
 * signal to fall back to planetarium mode, which is a supported way to use SatRide rather than a
 * degraded one.
 *
 * @param onFov reports the field of view **actually visible in this composable's bounds**, after
 *   every crop between the sensor and the screen. Called on first measurement and again whenever
 *   the viewport or the active camera format changes. The projection is only as right as this
 *   number, so it is measured rather than assumed.
 * @param onUnavailable called once if no preview can be shown, so the host can switch modes.
 */
@Composable
expect fun CameraLayer(modifier: Modifier, onFov: (CameraFov) -> Unit, onUnavailable: () -> Unit)
