package com.yuwanadev.mdm.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yuwanadev.mdm.MdmApplication
import com.yuwanadev.mdm.R
import com.yuwanadev.mdm.commands.CommandExecutor
import com.yuwanadev.mdm.data.ConfigStore
import com.yuwanadev.mdm.ui.MainActivity
import com.yuwanadev.mdm.websocket.ConnectionState
import com.yuwanadev.mdm.websocket.MessageHandler
import com.yuwanadev.mdm.websocket.WebSocketManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Foreground service that maintains the WebSocket connection to the MDM server.
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "AgentService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mdm_agent_channel"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }

        fun updateForegroundType(context: Context, isMediaProjection: Boolean) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = if (isMediaProjection) "ACTION_UPGRADE_MEDIA" else "ACTION_DOWNGRADE_MEDIA"
            }
            context.startService(intent)
        }
    }

    private lateinit var configStore: ConfigStore
    private lateinit var wsManager: WebSocketManager
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var messageHandler: MessageHandler

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        createNotificationChannel()
        configStore = ConfigStore(applicationContext)

        // Initialize WebSocket manager
        wsManager =
                WebSocketManager(
                        onMessage = { msg: com.yuwanadev.mdm.model.WSMessage -> messageHandler.handle(msg) },
                        onConnected = {
                            updateNotification(getString(R.string.notification_text_connected))
                            startHeartbeat()
                        },
                        onDisconnected = {
                            updateNotification(getString(R.string.notification_text_disconnected))
                            stopHeartbeat()
                        }
                )

        // Initialize command executor and message handler
        commandExecutor = CommandExecutor(applicationContext, wsManager)
        messageHandler = MessageHandler(this, wsManager, commandExecutor, configStore, scope)

        // Register network callback
        registerNetworkCallback()

        // Acquire wake lock to keep CPU running
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        if (action == "ACTION_UPGRADE_MEDIA" || action == "ACTION_DOWNGRADE_MEDIA") {
            val notification = buildNotification(getString(R.string.notification_text_connected))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val type = if (action == "ACTION_UPGRADE_MEDIA") {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(NOTIFICATION_ID, notification, type)
            }
            return START_STICKY
        }

        Log.i(TAG, "Service started")
        
        // Start as foreground service
        val notification = buildNotification(getString(R.string.notification_text_connecting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Connect to server
        scope.launch {
            val serverUrl = configStore.getServerUrl()
            val token = configStore.getToken()

            if (serverUrl != null && token != null) {
                // Backend expects token in query parameter: ?token=<token>
                val urlWithToken = if (serverUrl.contains("?")) {
                    "$serverUrl&token=$token"
                } else {
                    "$serverUrl?token=$token"
                }
                wsManager.connect(urlWithToken, token)
            } else {
                Log.e(TAG, "No config — stopping service")
                stopSelf()
            }
        }

        // Observe connection state for notification updates
        scope.launch {
            wsManager.state.collectLatest { state ->
                (application as MdmApplication).connectionState.value = state
                val text =
                        when (state) {
                            ConnectionState.CONNECTED ->
                                    getString(R.string.notification_text_connected)
                            ConnectionState.CONNECTING ->
                                    getString(R.string.notification_text_connecting)
                            ConnectionState.RECONNECTING ->
                                    getString(R.string.notification_text_connecting)
                            ConnectionState.DISCONNECTED ->
                                    getString(R.string.notification_text_disconnected)
                        }
                updateNotification(text)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        wsManager.disconnect()
        wsManager.destroy()
        stopHeartbeat()
        unregisterNetworkCallback()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Heartbeat ──────────────────────────────────────────────────

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob =
                scope.launch {
                    // Send first heartbeat immediately
                    if (wsManager.isConnected()) {
                        try {
                            commandExecutor.sendHeartbeat()
                        } catch (e: Exception) {
                            Log.e(TAG, "First heartbeat failed: ${e.message}")
                        }
                    }
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        if (wsManager.isConnected()) {
                            try {
                                commandExecutor.sendHeartbeat()
                            } catch (e: Exception) {
                                Log.e(TAG, "Heartbeat error: ${e.message}")
                            }
                        }
                    }
                }
        Log.i(TAG, "Heartbeat started (${HEARTBEAT_INTERVAL_MS}ms)")
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ── Network Monitoring ─────────────────────────────────────────

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request =
                NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()

        networkCallback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.i(TAG, "Network available — triggering reconnect")
                        if (!wsManager.isConnected()) {
                            scope.launch {
                                val serverUrl = configStore.getServerUrl()
                                val token = configStore.getToken()
                                if (serverUrl != null && token != null) {
                                    val urlWithToken = if (serverUrl.contains("?")) {
                                        "$serverUrl&token=$token"
                                    } else {
                                        "$serverUrl?token=$token"
                                    }
                                    wsManager.connect(urlWithToken, token)
                                }
                            }
                        }
                    }

                    override fun onLost(network: Network) {
                        Log.i(TAG, "Network lost")
                    }
                }

        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
    }

    // ── Notification ───────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                    NotificationChannel(
                                    CHANNEL_ID,
                                    getString(R.string.notification_channel_name),
                                    NotificationManager.IMPORTANCE_LOW
                            )
                            .apply {
                                description = getString(R.string.notification_channel_desc)
                                setShowBadge(false)
                            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ── Wake Lock ──────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MDMAgent::WebSocketWakeLock")
                        .apply {
                            acquire(10 * 60 * 1000L) // 10 minutes, will be re-acquired
                        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
