package com.otgcam.agent

import android.content.Context
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.otgcam.agent.model.CallSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Listens for incoming call signals via Telegram long-polling and automatically
 * accepts calls by creating WebRTC answers.
 */
class CallAutoAcceptor(
    private val uploader: TelegramUploader,
    private val rtcManager: WebRtcManager,
    private val context: Context
) {
    private var pollingJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Begin polling Telegram for signaling messages directed at this agent.
     */
    fun startListening() {
        pollingJob = scope.launch {
            uploader.pollForSignals { signal ->
                when (signal.event) {
                    "call_request" -> {
                        VibrationHelper.vibrateIncomingCallAccepted(context)
                        broadcastStatus(context, "Incoming call — auto-accepting...")
                        rtcManager.handleIncomingCall(signal.sdp ?: "") { answerJson ->
                            scope.launch {
                                val answerSignal = CallSignal.fromJson(answerJson)
                                if (answerSignal != null) {
                                    uploader.sendSignal(answerSignal)
                                }
                                broadcastStatus(context, "Live call active.")
                            }
                        }
                    }
                    "call_end" -> {
                        rtcManager.endCall()
                        broadcastStatus(context, "Call ended by Receiver.")
                    }
                    "ice_candidate" -> {
                        rtcManager.addIceCandidate(signal.candidate ?: "")
                    }
                }
            }
        }
    }

    /**
     * Stop the polling coroutine.
     */
    fun stopListening() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun broadcastStatus(ctx: Context, message: String) {
        val intent = android.content.Intent(CameraService.ACTION_STATUS_UPDATE).apply {
            putExtra(CameraService.EXTRA_STATUS_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }
}
