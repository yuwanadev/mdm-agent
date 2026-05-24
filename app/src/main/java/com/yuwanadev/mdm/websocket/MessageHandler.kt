package com.yuwanadev.mdm.websocket

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.yuwanadev.mdm.commands.CommandExecutor
import com.yuwanadev.mdm.commands.MirrorStreamer
import com.yuwanadev.mdm.data.ConfigStore
import com.yuwanadev.mdm.model.WSMessage
import com.yuwanadev.mdm.model.AgentMessageType
import com.yuwanadev.mdm.model.CommandPayload
import com.yuwanadev.mdm.service.TouchInjectorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/**
 * MessageHandler routes incoming WebSocket messages to the appropriate component.
 */
class MessageHandler(
    private val context: Context,
    private val wsManager: WebSocketManager,
    private val commandExecutor: CommandExecutor,
    private val configStore: ConfigStore,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MessageHandler"
        private val json = Json { ignoreUnknownKeys = true }
    }

    private var mirrorStreamer: MirrorStreamer? = null

    fun handle(message: WSMessage) {
        Log.d(TAG, "Handling message type: ${message.type}")
        
        when (message.type) {
            AgentMessageType.COMMAND -> {
                try {
                    val payload = json.decodeFromJsonElement<CommandPayload>(message.payload)
                    commandExecutor.execute(payload)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode command payload", e)
                }
            }
            AgentMessageType.ACK -> {
                Log.i(TAG, "Connection acknowledged by server")
                val deviceId = message.payload.jsonObject["device_id"]?.jsonPrimitive?.content
                if (deviceId != null) {
                    scope.launch {
                        configStore.saveDeviceId(deviceId)
                    }
                }
                commandExecutor.sendDeviceInfo()
                commandExecutor.sendDeviceAccounts()
            }
            "START_MIRROR" -> {
                Log.i(TAG, "Starting mirror session")
                if (mirrorStreamer == null) {
                    mirrorStreamer = MirrorStreamer(context, wsManager)
                }
                mirrorStreamer?.start()
            }
            "STOP_MIRROR" -> {
                Log.i(TAG, "Stopping mirror session")
                mirrorStreamer?.stop()
                mirrorStreamer = null
            }
            "TOUCH_EVENT" -> {
                handleTouchEvent(message.payload)
            }
            "WEBRTC_SIGNAL" -> {
                Log.d(TAG, "Routing WebRTC signal")
                mirrorStreamer?.handleWebRTCSignal(message.payload.jsonObject)
            }
            else -> {
                Log.w(TAG, "Unhandled message type: \${message.type}")
            }
        }
    }

    private fun handleTouchEvent(payload: JsonElement) {
        try {
            // Handle both direct JsonObject and double-encoded string payload
            val obj = when {
                payload is JsonObject -> payload
                payload is JsonPrimitive && payload.isString -> {
                    Log.w(TAG, "Touch payload was double-encoded string, re-parsing")
                    Json.parseToJsonElement(payload.content).jsonObject
                }
                else -> {
                    Log.e(TAG, "Unexpected touch payload type: ${payload::class.simpleName}")
                    return
                }
            }

            val action = obj["action"]?.jsonPrimitive?.content ?: "tap"
            val normalizedX = obj["x"]?.jsonPrimitive?.double ?: return
            val normalizedY = obj["y"]?.jsonPrimitive?.double ?: return

            // Convert normalized (0-1) coordinates to actual screen pixels
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val screenW = metrics.widthPixels.toFloat()
            val screenH = metrics.heightPixels.toFloat()

            val x = (normalizedX * screenW).toFloat()
            val y = (normalizedY * screenH).toFloat()

            Log.d(TAG, "Touch event: action=$action normalized=($normalizedX,$normalizedY) screen=($x,$y) display=${screenW}x${screenH}")

            when (action) {
                "tap" -> {
                    Log.d(TAG, "Touch tap at ($x, $y)")
                    val result = TouchInjectorService.injectTap(x, y)
                    Log.d(TAG, "Touch tap result: $result, service available: ${TouchInjectorService.isAvailable()}")
                }
                "swipe" -> {
                    val endX = ((obj["end_x"]?.jsonPrimitive?.double ?: normalizedX) * screenW).toFloat()
                    val endY = ((obj["end_y"]?.jsonPrimitive?.double ?: normalizedY) * screenH).toFloat()
                    val duration = obj["duration"]?.jsonPrimitive?.long ?: 300L
                    Log.d(TAG, "Touch swipe ($x,$y) → ($endX,$endY)")
                    val result = TouchInjectorService.injectSwipe(x, y, endX, endY, duration)
                    Log.d(TAG, "Touch swipe result: $result, service available: ${TouchInjectorService.isAvailable()}")
                }
                "long_press" -> {
                    val duration = obj["duration"]?.jsonPrimitive?.long ?: 800L
                    Log.d(TAG, "Touch long press at ($x, $y)")
                    val result = TouchInjectorService.injectLongPress(x, y, duration)
                    Log.d(TAG, "Touch long press result: $result, service available: ${TouchInjectorService.isAvailable()}")
                }
                else -> Log.w(TAG, "Unknown touch action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Touch event error: ${e.message}", e)
        }
    }

    fun stopMirror() {
        mirrorStreamer?.stop()
        mirrorStreamer = null
    }
}
