package com.developerstring.satride.feature.arsky.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.touchlab.kermit.Logger
import com.developerstring.satride.feature.arsky.projection.CameraFov

private val logger = Logger.withTag("CameraLayer")

/**
 * CameraX preview, filling its bounds.
 *
 * `PreviewView` rather than a raw `SurfaceView`: it handles display rotation, scale-to-fill and
 * the surface lifecycle, all of which are device-specific enough that hand-rolling them would
 * mean a long tail of phones showing a stretched or sideways sky.
 */
@Composable
actual fun CameraLayer(modifier: Modifier, onFov: (CameraFov) -> Unit, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var viewWidth by remember { mutableStateOf(0) }
    var viewHeight by remember { mutableStateOf(0) }
    var lens by remember { mutableStateOf<LensGeometry?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            // Fill rather than fit: a letterboxed preview with black bars would break the
            // illusion the whole feature rests on. The crop this causes is accounted for in the
            // reported field of view rather than ignored.
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            val geometry = runCatching {
                val cameraProvider = providerFuture.get()
                provider = cameraProvider

                val preview = Preview.Builder().build()
                preview.surfaceProvider = previewView.surfaceProvider

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                )

                // Read from the camera that was actually bound, not from camera 0: on a
                // multi-lens phone those are routinely different lenses whose fields of view
                // differ by tens of degrees.
                val sensorFov = camera.cameraInfo.sensorFieldOfView(context)
                val resolution = preview.resolutionInfo?.resolution
                if (sensorFov == null || resolution == null || resolution.height <= 0) {
                    null
                } else {
                    LensGeometry(
                        sensorFov = sensorFov,
                        streamAspect = resolution.width.toDouble() / resolution.height.toDouble(),
                        quarterTurns = camera.cameraInfo.sensorRotationDegrees / 90,
                    )
                }
            }.onFailure { logger.w(it) { "camera preview could not be bound" } }.getOrNull()

            lens = geometry
            if (geometry == null) onUnavailable()
        }, ContextCompat.getMainExecutor(context))

        onDispose { provider?.unbindAll() }
    }

    val geometry = lens
    if (geometry != null && viewWidth > 0 && viewHeight > 0) {
        val visible = CameraFovMath.visibleFov(
            sensorFov = geometry.sensorFov,
            streamAspect = geometry.streamAspect,
            quarterTurns = geometry.quarterTurns,
            viewAspect = viewWidth.toDouble() / viewHeight.toDouble(),
        )
        // Keyed on the value, so rotating the phone re-reports rather than leaving the projection
        // working from the portrait figure while the user holds the phone in landscape.
        LaunchedEffect(visible) {
            if (CameraFovMath.isPlausible(visible)) {
                logger.i {
                    "camera FOV ${visible.horizontalDeg.oneDecimal()}° x ${visible.verticalDeg.oneDecimal()}°"
                }
                onFov(visible)
            } else {
                logger.w { "implausible camera FOV $visible; keeping the default" }
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.onSizeChanged { size ->
            viewWidth = size.width
            viewHeight = size.height
        },
    )
}

/** What the projection needs to know about the lens currently being previewed. */
private data class LensGeometry(val sensorFov: CameraFov, val streamAspect: Double, val quarterTurns: Int)

/**
 * Field of view of the whole sensor behind a bound camera.
 *
 * Camera2 is asked directly because CameraX exposes neither focal length nor physical sensor
 * size, and the field of view is precisely the ratio between them.
 */
private fun CameraInfo.sensorFieldOfView(context: Context): CameraFov? {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
    val cameraId = runCatching { Camera2CameraInfo.from(this).cameraId }.getOrNull() ?: return null

    return runCatching {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val focalLength = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
            ?.toDouble()
        val size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

        if (focalLength == null || size == null || focalLength <= 0.0 ||
            size.width <= 0f || size.height <= 0f
        ) {
            // Some devices genuinely report zeroes here. Returning null falls back to the
            // planetarium field of view, which is wrong by a few degrees rather than by the
            // factor a zero would produce.
            logger.w { "camera $cameraId reports unusable optics (focal=$focalLength, sensor=$size)" }
            null
        } else {
            CameraFovMath.fromSensor(
                focalLengthMm = focalLength,
                sensorWidthMm = size.width.toDouble(),
                sensorHeightMm = size.height.toDouble(),
            )
        }
    }.onFailure { logger.w(it) { "could not read camera characteristics for $cameraId" } }.getOrNull()
}

private fun Double.oneDecimal(): String {
    val scaled = kotlin.math.round(this * 10.0).toInt()
    return "${scaled / 10}.${scaled % 10}"
}
