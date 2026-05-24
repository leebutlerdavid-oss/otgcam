package com.otgcam.receiver

import android.content.Context
import android.content.Intent

/**
 * Singleton coordinator for call state across the Receiver application.
 */
object CallManager {
    var isCallActive: Boolean = false
        private set
    var isVideoEnabled: Boolean = false
        private set

    /**
     * Initiate a call to the Agent.
     * @param videoEnabled True for video call, false for audio-only.
     * @param context Context used to launch [LiveCallActivity].
     */
    fun initiateCall(videoEnabled: Boolean, context: Context) {
        isCallActive = true
        isVideoEnabled = videoEnabled
        val intent = Intent(context, LiveCallActivity::class.java).apply {
            putExtra(LiveCallActivity.EXTRA_VIDEO_ENABLED, videoEnabled)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Mark the call as ended.
     */
    fun endCall() {
        isCallActive = false
        isVideoEnabled = false
    }
}
