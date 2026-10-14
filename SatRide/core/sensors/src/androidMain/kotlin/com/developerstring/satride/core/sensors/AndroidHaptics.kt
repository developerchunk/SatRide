package com.developerstring.satride.core.sensors

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptics through the system vibrator.
 *
 * Uses predefined effects where the device offers them. A hand-specified duration and amplitude
 * feels different on every phone — the same 20 ms pulse is a crisp tap on one actuator and a buzz
 * on another — whereas `EFFECT_TICK` is whatever the manufacturer decided a tick should feel like
 * on that hardware, which is the thing worth matching.
 */
internal class AndroidHaptics(context: Context) : Haptics {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override val isAvailable: Boolean get() = vibrator?.hasVibrator() == true

    override fun tick() {
        play(VibrationEffect.EFFECT_TICK, fallbackMillis = 12L)
    }

    override fun lock() {
        play(VibrationEffect.EFFECT_HEAVY_CLICK, fallbackMillis = 30L)
    }

    private fun play(effectId: Int, fallbackMillis: Long) {
        val motor = vibrator ?: return
        if (!motor.hasVibrator()) return
        runCatching {
            motor.vibrate(VibrationEffect.createPredefined(effectId))
        }.onFailure {
            // Some devices report a vibrator but reject predefined effects. A plain one-shot is
            // worse but present, and a guide loop with no feedback at all is the failure that
            // actually matters.
            runCatching { motor.vibrate(VibrationEffect.createOneShot(fallbackMillis, DEFAULT_AMPLITUDE)) }
        }
    }

    private companion object {
        const val DEFAULT_AMPLITUDE = VibrationEffect.DEFAULT_AMPLITUDE
    }
}
