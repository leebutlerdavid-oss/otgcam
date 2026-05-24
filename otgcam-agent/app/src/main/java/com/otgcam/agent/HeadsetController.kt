package com.otgcam.agent

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Singleton tap-detection state machine for headset media button events.
 * Supports single, double, and triple tap within a 600ms window.
 */
object HeadsetController {

    private var tapCount: Int = 0
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null

    /**
     * Process a headset button press. Must be called on the main thread.
     * @param context Application or activity context used to dispatch service intents.
     */
    fun handlePress(context: Context) {
        tapCount++
        pendingRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            val count = tapCount
            tapCount = 0
            when (count) {
                1 -> dispatchToService(context, CameraService.ACTION_CAPTURE_PHOTO)
                2 -> dispatchToService(context, CameraService.ACTION_CAPTURE_VIDEO)
                3 -> dispatchToService(context, CameraService.ACTION_TOGGLE_LIVE_AUDIO)
            }
        }
        pendingRunnable = runnable
        handler.postDelayed(runnable, 600L)
    }

    private fun dispatchToService(context: Context, action: String) {
        val intent = Intent(context, CameraService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
