package com.yuwanadev.mdm.websocket

import android.util.Log
import com.yuwanadev.mdm.model.WSMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

class WebSocketManager(
    private val onMessage: (WSMessage) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    companion object {
        private const val TAG = "WebSocketManager"
        private const val RECONNECT_DELAY_MS = 5000L
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isConnecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun connect(url: String, token: String) {
        if (isConnecting.get() || _state.value == ConnectionState.CONNECTED) return
        
        reconnectJob?.cancel()
        isConnecting.set(true)
        _state.value = if (_state.value == ConnectionState.DISCONNECTED) ConnectionState.CONNECTING else ConnectionState.RECONNECTING
        
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = ConnectionState.CONNECTED
                isConnecting.set(false)
                Log.i(TAG, "WebSocket Connected")
                onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = json.decodeFromString<WSMessage>(text)
                    onMessage(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Message decode error: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                handleDisconnect(url, token)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure: ${t.message}")
                handleDisconnect(url, token)
            }
        })
    }

    private fun handleDisconnect(url: String, token: String) {
        _state.value = ConnectionState.DISCONNECTED
        isConnecting.set(false)
        onDisconnected()
        scheduleReconnect(url, token)
    }

    private fun scheduleReconnect(url: String, token: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            connect(url, token)
        }
    }

    fun send(message: WSMessage) {
        try {
            val text = json.encodeToString(message)
            webSocket?.send(text)
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}")
        }
    }

    fun isConnected(): Boolean = _state.value == ConnectionState.CONNECTED

    /**
     * Send a mirror frame (base64 JPEG) to the backend.
     * Used by MirrorStreamer for live screen mirroring.
     */
    fun sendMirrorFrame(base64Data: String) {
        try {
            val msg = WSMessage(
                type = "MIRROR_FRAME",
                payload = kotlinx.serialization.json.buildJsonObject {
                    put("data", kotlinx.serialization.json.JsonPrimitive(base64Data))
                },
                timestamp = java.time.Instant.now().toString()
            )
            val text = json.encodeToString(msg)
            webSocket?.send(text)
        } catch (e: Exception) {
            Log.e(TAG, "Mirror frame send error: ${e.message}")
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnect")
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
