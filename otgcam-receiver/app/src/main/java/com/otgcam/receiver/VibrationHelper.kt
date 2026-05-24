package com.otgcam.receiver

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Centralized vibration feedback controller.
 * All patterns use explicit timing arrays; no default device effects.
 */
object VibrationHelper {

    /**
     * One short vibration — 150ms.
     * Fired immediately after a photo or video is captured.
     */
    fun vibrateCaptureConfirm(context: Context) {
        val timings = longArrayOf(0L, 150L)
        vibrate(context, timings)
    }

    /**
     * Two short vibrations — 150ms on, 150ms off, 150ms on.
     * Fired when a photo or video upload completes successfully.
     */
    fun vibrateUploadSuccess(context: Context) {
        val timings = longArrayOf(0L, 150L, 150L, 150L)
        vibrate(context, timings)
    }

    /**
     * Three short vibrations — 150ms on, 100ms off, 150ms on, 100ms off, 150ms on.
     * Fired on the Agent when an incoming call is auto-accepted.
     */
    fun vibrateIncomingCallAccepted(context: Context) {
        val timings = longArrayOf(0L, 150L, 100L, 150L, 100L, 150L)
        vibrate(context, timings)
    }

    /**
     * One continuous 3-second vibration.
     * Fired on the Agent when the UVC camera is attached or detached.
     */
    fun vibrateUvcEvent(context: Context) {
        val timings = longArrayOf(0L, 3000L)
        vibrate(context, timings)
    }

    private fun vibrate(context: Context, timings: LongArray) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(timings, -1)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }

    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}
