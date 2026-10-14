package com.developerstring.satride.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import com.developerstring.satride.core.orbit.math.Quaternion
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * Device orientation from Android's fused rotation vector.
 *
 * `TYPE_ROTATION_VECTOR` is the platform's own gyro/accelerometer/magnetometer fusion. Reading
 * the three sensors and fusing them by hand would be strictly worse: the system version is
 * calibrated per device, runs in the sensor hub rather than the application processor, and is
 * what every other compass app on the phone agrees with.
 *
 * Its world frame is already east-north-up, so no axis permutation is needed — only a remap for
 * the display's rotation and, above this class, the magnetic-to-true-north correction.
 */
internal class AndroidAttitudeSource(private val context: Context) : PlatformAttitudeSource {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotationVector: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    override val northReference: NorthReference = NorthReference.MAGNETIC

    override val isAvailable: Boolean get() = rotationVector != null

    override fun samples(): Flow<RawAttitudeSample> = callbackFlow {
        val manager = sensorManager
        val sensor = rotationVector
        if (manager == null || sensor == null) {
            close()
            return@callbackFlow
        }

        val rotationMatrix = FloatArray(9)
        val remapped = FloatArray(9)
        val asDoubles = DoubleArray(9)
        var accuracy = CompassAccuracy.UNCALIBRATED

        // Read once and then only when it changes. Asking the display manager per sample would be
        // a binder call fifty times a second for a value that changes when the user rotates the
        // phone.
        var rotation = currentDisplayRotation()
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) rotation = currentDisplayRotation()
            }

            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        displayManager?.registerDisplayListener(displayListener, null)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val (axisX, axisY) = displayAxes(rotation)
                val matrix = if (axisX == SensorManager.AXIS_X && axisY == SensorManager.AXIS_Y) {
                    rotationMatrix
                } else {
                    SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
                    remapped
                }

                // Android's matrix maps device -> world, which is the direction this app's
                // convention wants, so it is used as-is rather than transposed.
                for (i in 0 until 9) asDoubles[i] = matrix[i].toDouble()

                trySend(
                    RawAttitudeSample(
                        rotation = Quaternion.fromRotationMatrix(asDoubles),
                        elapsedNanos = event.timestamp,
                        accuracy = accuracy,
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, newAccuracy: Int) {
                accuracy = newAccuracy.toCompassAccuracy()
            }
        }

        // SENSOR_DELAY_GAME is about 50 Hz: enough for a smooth 30 Hz render with samples to
        // spare for the filter, without the power draw of SENSOR_DELAY_FASTEST, which would
        // deliver several readings per frame that are then averaged away anyway.
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose {
            manager.unregisterListener(listener)
            displayManager?.unregisterDisplayListener(displayListener)
        }
    }
        // Newest sample wins. Orientation is a current value, not a stream of events: if the
        // consumer falls behind there is nothing to gain from delivering a stale reading, and
        // back-pressuring the sensor callback would stall the sensor thread itself.
        .buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Axes to remap the rotation matrix onto for the current display rotation.
     *
     * The sensor frame is fixed to the device's natural orientation, but the app's frame is the
     * screen's. On a phone held in landscape the two differ by 90 degrees, and without this the
     * sky would be drawn rotated by exactly that much.
     */
    private fun displayAxes(rotation: Int): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }

    /**
     * Display rotation, read through [DisplayManager].
     *
     * Deliberately not `context.display`. This class is built with the application context, which
     * is not a *visual* context, and asking one for a display throws — which crashed the app on
     * the very first sensor callback. `DisplayManager` answers from any context, which is what a
     * component living below the UI actually needs.
     */
    private fun currentDisplayRotation(): Int =
        runCatching { displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation }
            .getOrNull() ?: Surface.ROTATION_0
}

private fun Int.toCompassAccuracy(): CompassAccuracy = when (this) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassAccuracy.HIGH
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassAccuracy.MEDIUM
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CompassAccuracy.LOW
    else -> CompassAccuracy.UNCALIBRATED
}
