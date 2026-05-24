package com.otgcam.receiver

import android.content.Context
import com.otgcam.receiver.model.AppConfig
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.CameraVideoCapturer
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
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Manages WebRTC peer connection lifecycle, SDP negotiation, and media tracks
 * for the Receiver application.
 */
class WebRtcManager(private val context: Context, private val config: AppConfig) {

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var eglBase: EglBase? = null
    private var localVideoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

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
     * Create an SDP offer and initiate a call to the Agent.
     * @param videoEnabled Whether to include video in the offer.
     * @param onOfferReady Callback invoked with the serialized offer JSON.
     */
    fun createOffer(videoEnabled: Boolean, onOfferReady: (String) -> Unit) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder(config.stunServerUrl).createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, peerConnectionObserver)
        peerConnection?.addTrack(localAudioTrack, listOf("stream"))

        if (videoEnabled) {
            val videoTrack = createLocalVideoTrack()
            videoTrack?.let { peerConnection?.addTrack(it, listOf("stream")) }
        }

        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        peerConnection?.createOffer(object : SdpAdapter("createOffer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SdpAdapter("setLocalOffer"), sdp)
                val json = JSONObject().apply {
                    put("event", "call_request")
                    put("sdp", sdp.description)
                    put("videoEnabled", videoEnabled)
                    put("agentId", config.agentId)
                }.toString()
                onOfferReady(json)
                isCallActive = true
            }
        }, constraints)
    }

    /**
     * Apply the remote SDP answer received from the Agent.
     * @param sdpJson Serialized answer JSON.
     */
    fun setRemoteAnswer(sdpJson: String) {
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
     * Bind the remote video stream to the given renderer.
     * @param renderer SurfaceViewRenderer for the Agent's camera feed.
     */
    fun attachRemoteVideoRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase?.eglBaseContext, null)
        remoteVideoTrack?.addSink(renderer)
    }

    /**
     * Bind the local video stream to the given renderer (PIP view).
     * @param renderer SurfaceViewRenderer for the Receiver's camera feed.
     */
    fun attachLocalVideoRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase?.eglBaseContext, null)
        localVideoTrack?.addSink(renderer)
    }

    /**
     * Terminate the active call and dispose of all media resources.
     */
    fun endCall() {
        localVideoCapturer?.stopCapture()
        localVideoCapturer?.dispose()
        localVideoCapturer = null
        surfaceTextureHelper?.stopListening()
        surfaceTextureHelper = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        remoteVideoTrack?.dispose()
        remoteVideoTrack = null
        peerConnection?.close()
        peerConnection = null
        isCallActive = false
    }

    private fun createLocalVideoTrack(): VideoTrack? {
        eglBase = EglBase.create()
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(false)
        val enumerator = Camera1Enumerator(false)
        val deviceNames = enumerator.deviceNames
        val capturer = if (deviceNames.isNotEmpty()) {
            enumerator.createCapturer(deviceNames[0], null)
        } else null
        capturer ?: return null
        capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        capturer.startCapture(1280, 720, 30)
        localVideoCapturer = capturer
        val videoTrack = peerConnectionFactory.createVideoTrack("local_video", videoSource)
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
            // Receiver sends ICE candidates via TelegramPoller.
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: org.webrtc.MediaStream) {
            if (stream.videoTracks.isNotEmpty()) {
                remoteVideoTrack = stream.videoTracks[0]
            }
        }
        override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
        override fun onDataChannel(dataChannel: org.webrtc.DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
            val track = receiver.track()
            if (track is VideoTrack) {
                remoteVideoTrack = track
            }
        }
    }

    private open class SdpAdapter(private val tag: String) : org.webrtc.SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }
}
