package com.developerstring.satride.feature.arsky.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.layout.onSizeChanged
import co.touchlab.kermit.Logger
import com.developerstring.satride.feature.arsky.projection.CameraFov
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.CoreMedia.CMVideoFormatDescriptionGetDimensions
import platform.QuartzCore.CATransaction
import platform.UIKit.UIView
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private val logger = Logger.withTag("CameraLayer")

/**
 * `AVCaptureVideoPreviewLayer` hosted in a plain `UIView`.
 *
 * There is no UIKit view that shows a capture session, only a layer, so the view exists purely to
 * own one and keep it the size of its bounds.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CameraLayer(modifier: Modifier, onFov: (CameraFov) -> Unit, onUnavailable: () -> Unit) {
    var viewWidth by remember { mutableStateOf(0) }
    var viewHeight by remember { mutableStateOf(0) }
    var lens by remember { mutableStateOf<LensGeometry?>(null) }

    val session = remember { AVCaptureSession() }
    val previewLayer = remember {
        AVCaptureVideoPreviewLayer(session = session).apply {
            // Matches Android's FILL_CENTER: fill the bounds and crop the overflow, rather than
            // letterbox. The crop is accounted for in the reported field of view.
            videoGravity = AVLayerVideoGravityResizeAspectFill
        }
    }

    DisposableEffect(session) {
        val device = backCamera()
        val geometry = device?.let { camera ->
            runCatching {
                val input = AVCaptureDeviceInput.deviceInputWithDevice(camera, null)
                    ?: error("no capture input for ${camera.localizedName}")

                session.beginConfiguration()
                session.sessionPreset = AVCaptureSessionPresetHigh
                if (session.canAddInput(input)) session.addInput(input)
                session.commitConfiguration()

                val format = camera.activeFormat
                // `videoFieldOfView` is the *horizontal* angle of the active format in the
                // sensor's own landscape orientation — not of the whole sensor, and not of what
                // ends up on a portrait screen.
                val horizontalDeg = format.videoFieldOfView.toDouble()
                val dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                val streamAspect = dimensions.useContents {
                    if (height > 0) width.toDouble() / height.toDouble() else 0.0
                }
                if (horizontalDeg <= 0.0 || streamAspect <= 0.0) {
                    error("camera reports unusable optics (fov=$horizontalDeg, aspect=$streamAspect)")
                }

                LensGeometry(
                    // The reported angle already describes the active format, so the stream crop
                    // has been applied; the vertical angle follows from the stream's own shape.
                    streamFov = CameraFov.fromHorizontal(
                        horizontalDeg = horizontalDeg,
                        widthPx = streamAspect.toFloat(),
                        heightPx = 1f,
                    ),
                    streamAspect = streamAspect,
                )
            }.onFailure { logger.w(it) { "camera preview could not be started" } }.getOrNull()
        }

        lens = geometry
        if (geometry == null) {
            onUnavailable()
        } else {
            // startRunning blocks; off the main thread it goes, or the first frame of the AR view
            // is a dropped one.
            dispatch_async(dispatch_get_main_queue()) { session.startRunning() }
        }

        onDispose { if (session.isRunning()) session.stopRunning() }
    }

    val geometry = lens
    if (geometry != null && viewWidth > 0 && viewHeight > 0) {
        // The preview is rotated to the interface orientation by the layer's connection, so a
        // portrait viewport sees the stream transposed. Comparing the two aspect ratios detects
        // that without reaching for the orientation API, which reports the *device's* orientation
        // and disagrees with the interface whenever rotation is locked.
        val viewAspect = viewWidth.toDouble() / viewHeight.toDouble()
        val quarterTurns = if ((viewAspect < 1.0) != (geometry.streamAspect < 1.0)) 1 else 0
        val visible = CameraFovMath.visibleFov(
            sensorFov = geometry.streamFov,
            // Already the stream's own field of view, so this crop is a no-op and only the
            // orientation and the view crop below do any work.
            streamAspect = geometry.streamAspect,
            quarterTurns = quarterTurns,
            viewAspect = viewAspect,
        )
        LaunchedEffect(visible) {
            if (CameraFovMath.isPlausible(visible)) {
                logger.i { "camera FOV ${visible.horizontalDeg}° x ${visible.verticalDeg}°" }
                onFov(visible)
            } else {
                logger.w { "implausible camera FOV $visible; keeping the default" }
            }
        }
    }

    UIKitView(
        factory = {
            UIView().apply { layer.addSublayer(previewLayer) }
        },
        modifier = modifier.onSizeChanged { size ->
            viewWidth = size.width
            viewHeight = size.height
        },
        update = { view ->
            // Layers do not participate in Auto Layout, so the frame is set by hand. Without
            // disabling the implicit animation the preview slides into place over a quarter of a
            // second every time the view is laid out, which on rotation looks like a glitch.
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            previewLayer.setFrame(view.bounds)
            CATransaction.commit()
        },
    )
}

/** What the projection needs to know about the lens currently being previewed. */
private data class LensGeometry(val streamFov: CameraFov, val streamAspect: Double)

/**
 * The default video capture device, which on every iPhone is the rear wide camera.
 *
 * Deliberately not a discovery session filtered by position: the default device is the one the
 * system considers primary, and asking for it keeps working on whatever lens arrangement a future
 * phone ships with.
 */
private fun backCamera(): AVCaptureDevice? = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
