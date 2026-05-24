package com.otgcam.agent

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import com.otgcam.agent.model.AppConfig
import com.otgcam.agent.model.CallSignal
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Manages WebRTC peer connection lifecycle, SDP negotiation, and media tracks
 * for the Agent application.
 */
class WebRtcManager(
    private val context: Context,
    private val config: AppConfig,
    private val onLocalIceCandidate: ((String) -> Unit)? = null
) {

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var uvcVideoCapturer: UvcVideoCapturer? = null
    private var eglBase: EglBase? = null

    var isCallActive: Boolean = false
        private set

    /**
     * Initialize the WebRTC engine and create the local audio track.
     */
    fun initialize() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(null, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(null)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource)
    }

    /**
     * Initiate an outgoing call by creating an SDP offer.
     * @param onOfferCreated Callback invoked with the serialized offer JSON.
     */
    fun startOutgoingCall(onOfferCreated: (String) -> Unit) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder(config.stunServerUrl).createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, peerConnectionObserver)
        peerConnection?.addTrack(localAudioTrack, listOf("stream"))
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("stream")) }

        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        peerConnection?.createOffer(object : SdpAdapter("createOffer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SdpAdapter("setLocalOffer"), sdp)
                val json = JSONObject().apply {
                    put("event", "live_start")
                    put("sdp", sdp.description)
                    put("videoEnabled", localVideoTrack != null)
                    put("agentId", config.agentId)
                }.toString()
                onOfferCreated(json)
                isCallActive = true
            }
        }, constraints)
    }

    /**
     * Handle an incoming call offer from the Receiver and create an SDP answer.
     * @param sdpJson Serialized offer JSON.
     * @param onAnswerCreated Callback invoked with the serialized answer JSON.
     */
    fun handleIncomingCall(sdpJson: String, onAnswerCreated: (String) -> Unit) {
        val remoteSdp = parseSdp(sdpJson, SessionDescription.Type.OFFER) ?: return
        val iceServers = listOf(
            PeerConnection.IceServer.builder(config.stunServerUrl).createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, peerConnectionObserver)
        peerConnection?.addTrack(localAudioTrack, listOf("stream"))
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("stream")) }

        peerConnection?.setRemoteDescription(SdpAdapter("setRemoteOffer"), remoteSdp)
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpAdapter("createAnswer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SdpAdapter("setLocalAnswer"), sdp)
                val json = JSONObject().apply {
                    put("event", "call_answer")
                    put("sdp", sdp.description)
                    put("agentId", config.agentId)
                }.toString()
                onAnswerCreated(json)
                isCallActive = true
            }
        }, constraints)
    }

    /**
     * Apply the remote SDP answer received from the Receiver.
     * @param sdpJson Serialized answer JSON.
     */
    fun handleAnswer(sdpJson: String) {
        val remoteSdp = parseSdp(sdpJson, SessionDescription.Type.ANSWER) ?: return
        peerConnection?.setRemoteDescription(SdpAdapter("setRemoteAnswer"), remoteSdp)
    }

    /**
     * Add a remote ICE candidate received via signaling.
     * @param candidateJson Serialized ICE candidate JSON.
     */
    fun addIceCandidate(candidateJson: String) {
        try {
            val obj = JSONObject(candidateJson)
            val candidate = obj.getJSONObject("candidate")
            val sdpMid = candidate.getString("sdpMid")
            val sdpMLineIndex = candidate.getInt("sdpMLineIndex")
            val sdp = candidate.getString("sdp")
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
            peerConnection?.addIceCandidate(iceCandidate)
        } catch (e: Exception) {
            // Invalid candidate format.
        }
    }

    /**
     * Toggle the live call state. If inactive, starts an outgoing call.
     * If active, ends the call.
     */
    fun toggleLiveCall() {
        if (isCallActive) {
            endCall()
        } else {
            startOutgoingCall { offerJson ->
                val signal = CallSignal.fromJson(offerJson)
                if (signal != null) {
                    // Signal will be sent by the caller (CameraService) via TelegramUploader
                }
            }
        }
    }

    /**
     * Terminate the active call and dispose of all media resources.
     */
    fun endCall() {
        uvcVideoCapturer?.stopCapture()
        uvcVideoCapturer?.dispose()
        uvcVideoCapturer = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        peerConnection?.close()
        peerConnection = null
        isCallActive = false
    }

    /**
     * Create and attach a video track sourced from the UVC camera's preview surface.
     * @param textureView The [TextureView] displaying the UVC preview.
     * @return The created [VideoTrack] or null if initialization fails.
     */
    fun attachUvcVideoSource(textureView: TextureView): VideoTrack? {
        eglBase = EglBase.create()
        val surfaceTextureHelper = SurfaceTextureHelper.create("UvcThread", eglBase?.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(false)
        val capturer = UvcVideoCapturer(textureView)
        capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        capturer.startCapture(1280, 720, 15)
        uvcVideoCapturer = capturer
        val videoTrack = peerConnectionFactory.createVideoTrack("uvc_video", videoSource)
        localVideoTrack = videoTrack
        return videoTrack
    }

    private fun parseSdp(json: String, type: SessionDescription.Type): SessionDescription? {
        return try {
            val obj = JSONObject(json)
            val sdp = obj.getString("sdp")
            SessionDescription(type, sdp)
        } catch (e: Exception) {
            null
        }
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(candidate: IceCandidate) {
            val json = JSONObject().apply {
                put("event", "ice_candidate")
                put("candidate", JSONObject().apply {
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("sdp", candidate.sdp)
                })
                put("agentId", config.agentId)
            }.toString()
            onLocalIceCandidate?.invoke(json)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: org.webrtc.MediaStream) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
        override fun onDataChannel(dataChannel: org.webrtc.DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
    }

    /**
     * Custom [VideoCapturer] that reads frames from a [TextureView]
     * by capturing its bitmap and converting to I420.
     */
    private class UvcVideoCapturer(private val textureView: TextureView) : VideoCapturer {

        private var surfaceTextureHelper: SurfaceTextureHelper? = null
        private var capturerObserver: VideoCapturer.CapturerObserver? = null
        private var handler: Handler? = null
        private var isCapturing = false
        private var captureWidth = 1280
        private var captureHeight = 720
        private var captureFps = 15

        override fun initialize(
            surfaceTextureHelper: SurfaceTextureHelper,
            context: Context,
            capturerObserver: VideoCapturer.CapturerObserver
        ) {
            this.surfaceTextureHelper = surfaceTextureHelper
            this.capturerObserver = capturerObserver
        }

        override fun startCapture(width: Int, height: Int, fps: Int) {
            isCapturing = true
            captureWidth = width
            captureHeight = height
            captureFps = fps
            handler = Handler(Looper.getMainLooper())
            scheduleCapture()
        }

        private fun scheduleCapture() {
            if (!isCapturing) return
            handler?.postDelayed({
                if (!isCapturing) return@postDelayed
                val bitmap = textureView.bitmap
                if (bitmap != null) {
                    val buffer = bitmapToI420(bitmap, captureWidth, captureHeight)
                    val timestampNs = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
                    val frame = VideoFrame(buffer, 0, timestampNs)
                    capturerObserver?.onFrameCaptured(frame)
                    frame.release()
                    bitmap.recycle()
                }
                scheduleCapture()
            }, (1000 / captureFps).toLong())
        }

        override fun stopCapture() {
            isCapturing = false
            handler?.removeCallbacksAndMessages(null)
        }

        override fun changeCaptureFormat(width: Int, height: Int, fps: Int) {
            stopCapture()
            startCapture(width, height, fps)
        }

        override fun dispose() {
            stopCapture()
        }

        override fun isScreencast(): Boolean = false

        private fun bitmapToI420(bitmap: Bitmap, width: Int, height: Int): VideoFrame.I420Buffer {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val pixels = IntArray(width * height)
            scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            scaledBitmap.recycle()

            val ySize = width * height
            val uvSize = ySize / 4
            val yBuffer = ByteBuffer.allocateDirect(ySize)
            val uBuffer = ByteBuffer.allocateDirect(uvSize)
            val vBuffer = ByteBuffer.allocateDirect(uvSize)

            for (i in 0 until height) {
                for (j in 0 until width) {
                    val pixel = pixels[i * width + j]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    yBuffer.put(i * width + j, y.coerceIn(0, 255).toByte())
                    if (i % 2 == 0 && j % 2 == 0) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        uBuffer.put((i / 2) * (width / 2) + (j / 2), u.coerceIn(0, 255).toByte())
                        vBuffer.put((i / 2) * (width / 2) + (j / 2), v.coerceIn(0, 255).toByte())
                    }
                }
            }

            return org.webrtc.JavaI420Buffer.wrap(
                width, height,
                yBuffer, width,
                uBuffer, width / 2,
                vBuffer, width / 2,
                null
            )
        }
    }

    private open class SdpAdapter(private val tag: String) : org.webrtc.SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }
}
