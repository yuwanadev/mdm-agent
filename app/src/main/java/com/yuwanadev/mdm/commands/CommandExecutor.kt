package com.yuwanadev.mdm.commands

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yuwanadev.mdm.model.*
import com.yuwanadev.mdm.receiver.MdmDeviceAdminReceiver
import com.yuwanadev.mdm.ui.ScreenshotPermissionActivity
import com.yuwanadev.mdm.utils.DownloadHelper
import com.yuwanadev.mdm.websocket.WebSocketManager
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Routes incoming commands to the appropriate handler and sends results back. */
class CommandExecutor(private val context: Context, private val wsManager: WebSocketManager) {
    companion object {
        private const val TAG = "CommandExecutor"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, MdmDeviceAdminReceiver::class.java)

    /** Execute a command and send the result back to the server. */
    fun execute(command: CommandPayload) {
        Log.i(TAG, "Executing command: ${command.type}")

        try {
            scope.launch {
                try {
                    when (command.type) {
                        "PING" -> handlePing(command.commandId)
                        "TAKE_SCREENSHOT" -> handleTakeScreenshot(command.commandId)
                        "GET_DEVICE_INFO" -> handleGetDeviceInfo(command.commandId)
                        "GET_BATTERY" -> handleGetBattery(command.commandId)
                        "GET_STORAGE" -> handleGetStorage(command.commandId)
                        "SHOW_ALERT" -> handleShowAlert(command.commandId, command.payload)
                        "LOCK_DEVICE" -> handleLockDevice(command.commandId)
                        "FACTORY_RESET" -> handleFactoryReset(command.commandId)
                        "INSTALL_APK" -> handleInstallApk(command.commandId, command.payload)
                        "SET_DEV_MODE" -> handleSetDevMode(command.commandId, command.payload)
                        "SET_USB_DEBUGGING" -> handleSetUsbDebugging(command.commandId, command.payload)
                        else -> {
                            Log.w(TAG, "Unknown command type: ${command.type}")
                            sendResult(command.commandId, false, "Unknown command: ${command.type}")
                        }
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security violation: ${e.message}")
                    sendResult(
                            command.commandId,
                            false,
                            "Security Error: This action requires Device Owner (YuwanaDev MDM) mode."
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Internal command error: ${e.message}")
                    sendResult(command.commandId, false, "Error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch command scope: ${e.message}")
            sendResult(command.commandId, false, "Launch error: ${e.message}")
        }
    }

    /** Send device info to the server (called on connect after ACK). */
    fun sendDeviceInfo() {
        val info =
                DeviceInfoPayload(
                        model = android.os.Build.MODEL,
                        manufacturer = android.os.Build.MANUFACTURER,
                        androidVersion = android.os.Build.VERSION.RELEASE,
                        appVersion = DeviceInfoCommand.getAppVersion(context) ?: "1.0.0"
                )
        val payload = json.encodeToJsonElement(info)
        val message =
                WSMessage(
                        type = AgentMessageType.DEVICE_INFO,
                        payload = payload,
                        timestamp = java.time.Instant.now().toString()
                )
        wsManager.send(message)
        Log.i(TAG, "Sent device info: ${info.model}")
    }

    /** Send a heartbeat with current device stats. */
    fun sendHeartbeat() {
        val heartbeat = HeartbeatPayload(
            battery = try { DeviceInfoCommand.getBatteryLevel(context) } catch (e: Exception) { null },
            temperature = try { DeviceInfoCommand.getBatteryTemperature(context) } catch (e: Exception) { null },
            batteryHealth = try { DeviceInfoCommand.getBatteryHealth(context) } catch (e: Exception) { null },
            batteryStatus = try { DeviceInfoCommand.getBatteryStatus(context) } catch (e: Exception) { null },
            batteryTechnology = try { DeviceInfoCommand.getBatteryTechnology(context) } catch (e: Exception) { null },
            batteryVoltage = try { DeviceInfoCommand.getBatteryVoltage(context) } catch (e: Exception) { null },
            ramUsage = try { DeviceInfoCommand.getRAMUsageMB(context) } catch (e: Exception) { null },
            storageTotal = try { DeviceInfoCommand.getStorageTotalMB() } catch (e: Exception) { null },
            storageUsed = try { DeviceInfoCommand.getStorageUsedMB() } catch (e: Exception) { null },
            appVersion = try { DeviceInfoCommand.getAppVersion(context) } catch (e: Exception) { null },
            networkInfo = try { DeviceInfoCommand.getNetworkInfo(context) } catch (e: Exception) { null },
            foregroundApp = try { DeviceInfoCommand.getForegroundApp(context) } catch (e: Exception) { null },
            networkStrength = try { DeviceInfoCommand.getNetworkStrength(context) } catch (e: Exception) { null },
            location = try { DeviceInfoCommand.getLatestLocation(context) } catch (e: Exception) { null }
        )
        val payload = json.encodeToJsonElement(heartbeat)
        val message = WSMessage(
            type = AgentMessageType.HEARTBEAT,
            payload = payload,
            timestamp = Instant.now().toString()
        )
        wsManager.send(message)
        Log.d(TAG, "Sent heartbeat: battery=${heartbeat.battery}%, app=${heartbeat.foregroundApp}")
    }

    // ── Command handlers ──────────────────────────────────────────

    private fun handlePing(commandId: String) {
        sendResult(commandId, true, "pong")
    }

    private fun handleGetDeviceInfo(commandId: String) {
        val info = DeviceInfoCommand.collect(context)
        val data = json.encodeToJsonElement(info)
        sendResult(commandId, true, "Device info collected", data)
    }

    private fun handleTakeScreenshot(commandId: String) {
        // If the capture pipeline is already running, just grab a frame — no dialog needed
        if (ScreenshotHelper.isReady()) {
            Log.i(TAG, "Pipeline active — grabbing frame directly")
            ScreenshotHelper(context).grabFrame { result ->
                handleCaptureResult(commandId, result)
            }
            return
        }

        // Pipeline not active — need to request permission first
        if (!ScreenshotPermissionActivity.hasPermission()) {
            Log.i(TAG, "No screenshot permission, requesting with auto-capture callback...")
            
            ScreenshotPermissionActivity.setPendingCaptureCallback { resultCode, _ ->
                if (resultCode == android.app.Activity.RESULT_OK) {
                    Log.i(TAG, "Permission granted via callback, initializing pipeline...")
                    initializeAndCapture(commandId)
                } else {
                    sendResult(commandId, false, "Screenshot permission denied by user")
                }
            }
            
            val intent = Intent(context, ScreenshotPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }

        // Have permission data but pipeline not initialized yet
        initializeAndCapture(commandId)
    }

    private fun initializeAndCapture(commandId: String) {
        Log.i(TAG, "Initializing capture pipeline...")
        // Upgrade service type for Android 14+
        com.yuwanadev.mdm.service.AgentService.updateForegroundType(context, true)
        
        // Wait for service type upgrade, then init pipeline
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            ScreenshotHelper(context).initPipeline { result ->
                handleCaptureResult(commandId, result)
            }
        }, 800)
    }

    private fun handleCaptureResult(commandId: String, result: ScreenshotHelper.CaptureResult) {
        if (result.base64 != null) {
            Log.i(TAG, "Screenshot captured (${result.base64.length} chars), sending...")
            sendResult(commandId, true, "Screenshot captured", JsonPrimitive(result.base64))
        } else {
            val errorMsg = result.error ?: "Unknown capture error"
            Log.e(TAG, "Screenshot failed: $errorMsg")
            sendResult(commandId, false, "Screenshot failed: $errorMsg")
        }
    }

    private fun handleGetBattery(commandId: String) {
        Log.d(TAG, "Handling GET_BATTERY for $commandId")
        try {
            val level = DeviceInfoCommand.getBatteryLevel(context) ?: -1
            val temp = DeviceInfoCommand.getBatteryTemperature(context) ?: 0f

            val data = buildJsonObject {
                put("battery", JsonPrimitive(level))
                put("temperature", JsonPrimitive(temp))
            }

            sendResult(commandId, true, "Battery: $level%", data)
        } catch (e: Exception) {
            sendResult(commandId, false, "Failed to get battery: ${e.message}")
        }
    }

    private fun handleGetStorage(commandId: String) {
        val total = DeviceInfoCommand.getStorageTotalMB() ?: 0
        val used = DeviceInfoCommand.getStorageUsedMB() ?: 0

        val data = buildJsonObject {
            put("storage_total", JsonPrimitive(total))
            put("storage_used", JsonPrimitive(used))
        }

        sendResult(commandId, true, "Storage: ${used}MB / ${total}MB", data)
    }

    private fun handleShowAlert(commandId: String, payload: JsonElement?) {
        val message =
                try {
                    payload?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Alert from YuwanaDev MDM"
                } catch (e: Exception) {
                    "Alert from YuwanaDev MDM"
                }

        // Show as Toast for now, but in production this would be a full-screen Activity or Dialog
        scope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(
                            context,
                            "ALERT: $message",
                            android.widget.Toast.LENGTH_LONG
                    )
                    .show()
        }
        sendResult(commandId, true, "Alert displayed: $message")
    }

    private fun handleLockDevice(commandId: String) {
        if (!isDeviceAdminActive()) {
            sendResult(
                    commandId,
                    false,
                    "Device Admin not enabled. Please enable 'YuwanaDev MDM' in System Settings -> Device Admin Apps."
            )
            return
        }

        try {
            dpm.lockNow()
            sendResult(commandId, true, "Device locked")
        } catch (e: Exception) {
            sendResult(commandId, false, "Failed to lock device: ${e.message}")
        }
    }

    private fun handleFactoryReset(commandId: String) {
        if (!isDeviceAdminActive()) {
            sendResult(commandId, false, "Device Admin not enabled. Cannot factory reset.")
            return
        }

        try {
            // WARNING: This will immediately wipe the device!
            // dpm.wipeData(0)
            // For safety during development, let's just log it and send success
            Log.w(TAG, "FACTORY_RESET command received - WIPE ACTION TRIGGERED")
            sendResult(commandId, true, "Factory reset signal received (Simulated for safety)")
        } catch (e: Exception) {
            sendResult(commandId, false, "Failed to initiate factory reset: ${e.message}")
        }
    }

    private fun handleInstallApk(commandId: String, payload: JsonElement?) {
        val url =
                try {
                    payload?.jsonObject?.get("url")?.jsonPrimitive?.content
                } catch (e: Exception) {
                    null
                }

        if (url == null) {
            sendResult(commandId, false, "Missing APK URL in payload")
            return
        }

        Log.i(TAG, "Starting APK download from $url")
        scope.launch(Dispatchers.IO) {
            DownloadHelper(context)
                    .downloadAndInstall(
                            url = url,
                            onProgress = { progress ->
                                // Optional: send progress updates to server
                                Log.d(TAG, "Download progress: $progress%")
                            },
                            onResult = { success, message ->
                                sendResult(commandId, success, message)
                            }
                    )
        }
    }

    private fun handleSetDevMode(commandId: String, payload: JsonElement?) {
        val enabled = try { payload?.jsonObject?.get("enabled")?.jsonPrimitive?.boolean ?: false } catch(e: Exception) { false }
        val value = if (enabled) 1 else 0
        val settingKey = "development_settings_enabled"
        
        // Check if permission is actually granted
        val permStatus = context.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
        val hasPermission = permStatus == android.content.pm.PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "WRITE_SECURE_SETTINGS granted: $hasPermission (status=$permStatus)")
        
        if (!hasPermission) {
            sendResult(commandId, false, "WRITE_SECURE_SETTINGS not granted (status=$permStatus). Run: adb -s <device> shell pm grant com.yuwanadev.mdm android.permission.WRITE_SECURE_SETTINGS")
            return
        }

        // Try Settings.Global first (more compatible), then Settings.Secure
        var success = false
        var lastError: String? = null
        
        try {
            success = android.provider.Settings.Global.putInt(context.contentResolver, settingKey, value)
            if (success) Log.i(TAG, "Set dev mode via Settings.Global")
        } catch (e: Exception) {
            lastError = "Global: ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "Settings.Global failed: $lastError")
        }
        
        if (!success) {
            try {
                success = android.provider.Settings.Secure.putInt(context.contentResolver, settingKey, value)
                if (success) Log.i(TAG, "Set dev mode via Settings.Secure")
            } catch (e: Exception) {
                lastError = "Secure: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "Settings.Secure failed: $lastError")
            }
        }

        if (success) {
            sendResult(commandId, true, "Development settings ${if (enabled) "enabled" else "disabled"}")
        } else {
            sendResult(commandId, false, "Failed to set dev mode. ${lastError ?: "Unknown error"}")
        }
    }

    private fun handleSetUsbDebugging(commandId: String, payload: JsonElement?) {
        val enabled = try { payload?.jsonObject?.get("enabled")?.jsonPrimitive?.boolean ?: false } catch(e: Exception) { false }
        val value = if (enabled) 1 else 0
        try {
            // ADB_ENABLED is in Settings.Global
            val success = android.provider.Settings.Global.putInt(
                context.contentResolver,
                android.provider.Settings.Global.ADB_ENABLED,
                value
            )
            if (success) {
                sendResult(commandId, true, "USB debugging ${if (enabled) "enabled" else "disabled"}")
            } else {
                sendResult(commandId, false, "Failed to set USB debugging. Grant WRITE_SECURE_SETTINGS via ADB.")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to set USB debugging — missing WRITE_SECURE_SETTINGS", e)
            sendResult(commandId, false, "SecurityException: ${e.message}. Re-run: adb -s <device> shell pm grant com.yuwanadev.mdm android.permission.WRITE_SECURE_SETTINGS")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set USB debugging", e)
            sendResult(commandId, false, "Failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun isDeviceAdminActive(): Boolean {
        return dpm.isAdminActive(adminComponent)
    }

    // ── Result sender ──────────────────────────────────────────────

    private fun sendResult(
            commandId: String,
            success: Boolean,
            message: String,
            data: JsonElement? = null
    ) {
        val result =
                CommandResult(
                        commandId = commandId,
                        success = success,
                        message = message,
                        data = data
                )
        val payload = json.encodeToJsonElement(result)
        val wsMessage =
                WSMessage(
                        type = AgentMessageType.COMMAND_RESULT,
                        payload = payload,
                        timestamp = Instant.now().toString()
                )
        wsManager.send(wsMessage)
        Log.d(TAG, "Sent result for $commandId: success=$success")
    }
}
