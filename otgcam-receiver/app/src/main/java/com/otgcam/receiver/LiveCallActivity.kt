package com.otgcam.receiver

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Chronometer
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.otgcam.receiver.databinding.ActivityLiveCallBinding
import com.otgcam.receiver.model.AppConfig
import com.otgcam.receiver.model.CallSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

/**
 * Full-screen activity for active WebRTC audio/video calls.
 * Displays the Agent's UVC camera stream and the Receiver's own camera in PIP (video calls only).
 */
class LiveCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveCallBinding
    private lateinit var webRtcManager: WebRtcManager
    private lateinit var telegramPoller: TelegramPoller
    private lateinit var audioManager: AudioManager
    private var isMuted = false
    private var isSpeakerOn = true
    private var chronometer: Chronometer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityLiveCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoEnabled = intent.getBooleanExtra(EXTRA_VIDEO_ENABLED, false)
        val config = loadConfig() ?: run {
            finish()
            return
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = isSpeakerOn

        webRtcManager = WebRtcManager(this, config)
        webRtcManager.initialize()

        telegramPoller = TelegramPoller(config, this)

        binding.remoteRenderer.init(null, null)
        webRtcManager.attachRemoteVideoRenderer(binding.remoteRenderer)

        if (videoEnabled) {
            binding.localRenderer.visibility = android.view.View.VISIBLE
            binding.localRenderer.init(null, null)
            webRtcManager.attachLocalVideoRenderer(binding.localRenderer)
        } else {
            binding.localRenderer.visibility = android.view.View.GONE
            binding.tvStatus.text = getString(R.string.audio_only)
        }

        webRtcManager.createOffer(videoEnabled) { offerJson ->
            lifecycleScope.launch(Dispatchers.IO) {
                val signal = CallSignal.fromJson(offerJson)
                if (signal != null) {
                    telegramPoller.sendSignal(signal)
                }
            }
        }

        binding.tvStatus.text = getString(R.string.connecting)

        lifecycleScope.launch(Dispatchers.IO) {
            telegramPoller.startPolling(
                onPhoto = {},
                onVideo = {},
                onSignal = { signal ->
                    when (signal.event) {
                        "call_answer" -> {
                            signal.sdp?.let { webRtcManager.setRemoteAnswer(it) }
                            runOnUiThread {
                                binding.tvStatus.text = getString(R.string.live)
                                chronometer = Chronometer(this@LiveCallActivity).apply {
                                    base = SystemClock.elapsedRealtime()
                                    start()
                                }
                            }
                        }
                        "call_end" -> {
                            runOnUiThread { endCallAndFinish() }
                        }
                        "ice_candidate" -> {
                            webRtcManager.addIceCandidate(signal.candidate ?: "")
                        }
                    }
                }
            )
        }

        binding.btnMute.setOnClickListener {
            isMuted = !isMuted
            webRtcManager.localAudioTrack?.setEnabled(!isMuted)
            binding.btnMute.setImageResource(
                if (isMuted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_btn_speak_now
            )
        }

        binding.btnSpeaker.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            audioManager.isSpeakerphoneOn = isSpeakerOn
            binding.btnSpeaker.setImageResource(
                if (isSpeakerOn) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_lock_silent_mode
            )
        }

        binding.btnEndCall.setOnClickListener {
            endCallAndFinish()
        }

        binding.btnBack.setOnClickListener {
            endCallAndFinish()
        }
    }

    private fun loadConfig(): AppConfig? {
        return try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                this,
                "otgcam_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val token = prefs.getString("bot_token", null) ?: return null
            val chatId = prefs.getString("chat_id", null) ?: return null
            val agentId = prefs.getString("agent_id", null) ?: return null
            AppConfig(token, chatId, agentId)
        } catch (e: Exception) {
            null
        }
    }

    private fun endCallAndFinish() {
        webRtcManager.endCall()
        lifecycleScope.launch(Dispatchers.IO) {
            val signal = CallSignal(event = "call_end", agentId = loadConfig()?.agentId ?: "")
            telegramPoller.sendSignal(signal)
        }
        CallManager.endCall()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        webRtcManager.endCall()
        telegramPoller.stopPolling()
        binding.remoteRenderer.release()
        binding.localRenderer.release()
    }

    companion object {
        /**
         * Intent extra key for enabling video in the call.
         */
        const val EXTRA_VIDEO_ENABLED = "extra_video_enabled"
    }
}
