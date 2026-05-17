package com.yuwanadev.mdm.commands

import android.content.Context
import android.util.Log
import com.yuwanadev.mdm.capture.CaptureSessionCoordinator
import com.yuwanadev.mdm.webrtc.WebRTCClient
import com.yuwanadev.mdm.websocket.WebSocketManager
import kotlinx.coroutines.launch

/**
 * MirrorStreamer now wraps the WebRTCClient for live video streaming.
 */
class MirrorStreamer(
    private val context: Context,
    private val wsManager: WebSocketManager
) {
    companion object {
        private const val TAG = "MirrorStreamer"
    }

    private var webRTCClient: WebRTCClient? = null
    
    @Volatile
    private var isStreaming = false
    private var stateObserverJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    fun start() {
        if (isStreaming) {
            Log.w(TAG, "Already streaming")
            return
        }

        Log.i(TAG, "Starting MirrorStreamer via WebRTC")
        
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            CaptureSessionCoordinator.sessionState.collect { state ->
                if (state == com.yuwanadev.mdm.capture.SessionState.FAILED) {
                    Log.w(TAG, "Capture session failed or revoked by system. Stopping stream.")
                    val errorPayload = kotlinx.serialization.json.buildJsonObject {
                        put("error", kotlinx.serialization.json.JsonPrimitive("MEDIA_PROJECTION_REVOKED"))
                    }
                    wsManager.send(com.yuwanadev.mdm.model.WSMessage(type = "ERROR", payload = errorPayload, timestamp = java.time.Instant.now().toString()))
                    stop()
                }
            }
        }
        CaptureSessionCoordinator.acquireMirrorSession(context) { proj ->
            if (proj == null) {
                Log.e(TAG, "Failed to acquire mirror session")
                isStreaming = false
                return@acquireMirrorSession
            }

            try {
                if (webRTCClient == null) {
                    webRTCClient = WebRTCClient(context, wsManager)
                }
                
                webRTCClient?.startStream(proj)
                isStreaming = true

                Log.i(TAG, "WebRTC mirror pipeline started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "WebRTC pipeline failed: \${e.message}", e)
                CaptureSessionCoordinator.releaseMirrorSession()
                isStreaming = false
            }
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping mirror stream")
        isStreaming = false
        stateObserverJob?.cancel()
        stateObserverJob = null
        
        webRTCClient?.stopStream()
        webRTCClient?.destroy()
        webRTCClient = null
        
        CaptureSessionCoordinator.releaseMirrorSession()
    }
    
    fun handleWebRTCSignal(payload: kotlinx.serialization.json.JsonObject) {
        if (webRTCClient == null) {
            Log.w(TAG, "Received signal but WebRTCClient is null")
            return
        }
        
        val type = payload["type"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null }
        when (type) {
            "offer" -> {
                val sdp = payload["sdp"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null }
                if (sdp != null) webRTCClient?.handleRemoteOffer(sdp)
            }
            "answer" -> {
                val sdp = payload["sdp"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null }
                if (sdp != null) webRTCClient?.handleRemoteAnswer(sdp)
            }
            "ice_candidate" -> {
                val candidateObj = payload["candidate"] as? kotlinx.serialization.json.JsonObject
                if (candidateObj != null) {
                    val sdpMid = candidateObj["sdpMid"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else "" } ?: ""
                    val sdpMLineIndex = candidateObj["sdpMLineIndex"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content.toIntOrNull() ?: 0 else 0 } ?: 0
                    val sdp = candidateObj["candidate"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else "" } ?: ""
                    webRTCClient?.handleRemoteIceCandidate(sdpMid, sdpMLineIndex, sdp)
                }
            }
        }
    }
}
