package com.otgcam.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Restarts the CameraService after device boot if it was running prior to shutdown.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("otgcam_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("service_running", false)) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CameraService::class.java).apply {
                    action = CameraService.ACTION_START
                }
            )
        }
    }
}
