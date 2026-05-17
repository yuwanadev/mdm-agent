package com.yuwanadev.mdm.webrtc

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import com.yuwanadev.mdm.capture.CodecCapabilityManager
import com.yuwanadev.mdm.model.WSMessage
import com.yuwanadev.mdm.websocket.WebSocketManager
import kotlinx.serialization.json.*
import org.webrtc.*

/**
 * Manages the WebRTC PeerConnection for the MirrorSession.
 */
class WebRTCClient(
    private val context: Context,
    private val wsManager: WebSocketManager
) {
    private val TAG = "WebRTCClient"

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: SharedMediaProjectionCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // For diagnostics & adaptive streaming
    private var targetWidth = 1280
    private var targetHeight = 720
    private var targetFps = 30
    private var targetBitrateKbps = 1500

    init {
        // Run diagnostics before initializing
        val diagnostics = CodecCapabilityManager.runDiagnostics()
        targetWidth = diagnostics.maxSupportedWidth.coerceAtMost(1280)
        targetHeight = diagnostics.maxSupportedHeight.coerceAtMost(720)
        targetBitrateKbps = diagnostics.recommendedBitrateKbps

        initializeWebRTC()
    }

    private fun initializeWebRTC() {
        Log.i(TAG, "Initializing WebRTC")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()
        
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase?.eglBaseContext, 
            true, /* enableIntelVp8Encoder */ 
            true  /* enableH264HighProfile */
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    /**
     * Start the WebRTC stream using the provided shared MediaProjection token.
     */
    fun startStream(mediaProjection: MediaProjection) {
        Log.i(TAG, "Starting WebRTC stream")
        
        createPeerConnection()
        
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
        videoCapturer = SharedMediaProjectionCapturer(mediaProjection)
        
        videoSource = factory?.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        
        videoTrack = factory?.createVideoTrack("ARDAMSv0", videoSource)
        videoTrack?.setEnabled(true)
        
        peerConnection?.addTrack(videoTrack, listOf("ARDAMS"))
        
        videoCapturer?.startCapture(targetWidth, targetHeight, targetFps)
        
        registerDisplayListener()
        
        // Generate the offer once capturer is fully initialized
        createOffer()
    }

    private var displayManager: android.hardware.display.DisplayManager? = null
    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null
    private var currentRotation: Int = -1

    fun stopStream() {
        Log.i(TAG, "Stopping WebRTC stream")
        try {
            unregisterDisplayListener()
            
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoSource?.dispose()
            surfaceTextureHelper?.dispose()
            
            peerConnection?.close()
            peerConnection?.dispose()
            
            videoCapturer = null
            videoSource = null
            surfaceTextureHelper = null
            peerConnection = null
            videoTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping stream: \${e.message}")
        }
    }

    fun destroy() {
        stopStream()
        factory?.dispose()
        eglBase?.release()
    }

    private fun registerDisplayListener() {
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        currentRotation = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: -1

        displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                    val newRotation = displayManager?.getDisplay(displayId)?.rotation ?: -1
                    if (newRotation != currentRotation) {
                        currentRotation = newRotation
                        handleRotation()
                    }
                }
            }
        }
        displayManager?.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
    }
    
    private fun unregisterDisplayListener() {
        displayListener?.let { displayManager?.unregisterDisplayListener(it) }
        displayListener = null
    }

    private fun handleRotation() {
        Log.i(TAG, "Display rotated, safely restarting video capturer")
        videoCapturer?.stopCapture()
        
        // Wait a short moment for surface bounds to settle
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Let the capturer restart with the same requested dims
            videoCapturer?.startCapture(targetWidth, targetHeight, targetFps)
        }, 500)
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ICE Connection State: \$newState")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
            
            override fun onIceCandidate(candidate: IceCandidate) {
                sendIceCandidate(candidate)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dataChannel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
        })
    }

    // --- Signaling Handlers ---

    fun handleRemoteOffer(sdp: String) {
        Log.i(TAG, "Received Remote Offer")
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                createAnswer()
            }
            override fun onCreateFailure(reason: String?) {}
            override fun onSetFailure(reason: String?) {}
        }, sessionDescription)
    }

    fun handleRemoteAnswer(sdp: String) {
        Log.i(TAG, "Received Remote Answer")
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(reason: String?) {}
            override fun onSetFailure(reason: String?) {}
        }, sessionDescription)
    }

    fun handleRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
        peerConnection?.addIceCandidate(candidate)
    }

    private fun createOffer() {
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onSetSuccess() {
                        sendOffer(desc)
                    }
                    override fun onCreateFailure(reason: String?) {}
                    override fun onSetFailure(reason: String?) {}
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(reason: String?) {
                Log.e(TAG, "createOffer failed: $reason")
            }
            override fun onSetFailure(reason: String?) {}
        }, MediaConstraints())
    }

    private fun sendOffer(desc: SessionDescription) {
        val payload = buildJsonObject {
            put("type", "offer")
            put("sdp", desc.description)
        }
        val msg = WSMessage(type = "WEBRTC_SIGNAL", payload = payload, timestamp = java.time.Instant.now().toString())
        wsManager.send(msg)
    }

    private fun createAnswer() {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onSetSuccess() {
                        sendAnswer(desc)
                    }
                    override fun onCreateFailure(reason: String?) {}
                    override fun onSetFailure(reason: String?) {}
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(reason: String?) {
                Log.e(TAG, "createAnswer failed: \$reason")
            }
            override fun onSetFailure(reason: String?) {}
        }, MediaConstraints())
    }

    private fun sendAnswer(desc: SessionDescription) {
        val payload = buildJsonObject {
            put("type", "answer")
            put("sdp", desc.description)
        }
        val msg = WSMessage(type = "WEBRTC_SIGNAL", payload = payload, timestamp = java.time.Instant.now().toString())
        wsManager.send(msg)
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val payload = buildJsonObject {
            put("type", "ice_candidate")
            put("candidate", buildJsonObject {
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            })
        }
        val msg = WSMessage(type = "WEBRTC_SIGNAL", payload = payload, timestamp = java.time.Instant.now().toString())
        wsManager.send(msg)
    }
}
