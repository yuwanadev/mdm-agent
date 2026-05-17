package com.yuwanadev.mdm.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yuwanadev.mdm.service.AgentService
import com.yuwanadev.mdm.ui.ScreenshotPermissionActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SessionState {
    IDLE,
    REQUESTING_PERMISSION,
    STARTING,
    STREAMING,
    STOPPING,
    FAILED
}

/**
 * CaptureSessionCoordinator manages the SharedProjectionSession.
 * It enforces the rule: Only ONE active MediaProjection token system-wide.
 * It handles the 30s idle timeout to automatically release resources.
 */
object CaptureSessionCoordinator {
    private const val TAG = "CaptureCoordinator"
    private const val IDLE_TIMEOUT_MS = 30_000L

    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState

    // The single shared MediaProjection token
    private var sharedProjection: MediaProjection? = null
    
    // Track active consumers
    private var isMirrorActive = false
    private var isScreenshotActive = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var idleTimeoutJob: Job? = null

    // Callbacks for when projection is ready
    private val pendingCallbacks = mutableListOf<(MediaProjection?) -> Unit>()

    /**
     * Request the shared MediaProjection for Mirroring.
     */
    fun acquireMirrorSession(context: Context, onReady: (MediaProjection?) -> Unit) {
        Log.i(TAG, "acquireMirrorSession requested")
        isMirrorActive = true
        cancelIdleTimeout()
        requestProjection(context, onReady)
    }

    /**
     * Request the shared MediaProjection for a Screenshot.
     * Note: If mirror is already active, you could optionally just grab a frame from it.
     */
    fun acquireScreenshotSession(context: Context, onReady: (MediaProjection?) -> Unit) {
        Log.i(TAG, "acquireScreenshotSession requested")
        isScreenshotActive = true
        cancelIdleTimeout()
        requestProjection(context, onReady)
    }

    /**
     * Release the Mirror session.
     */
    fun releaseMirrorSession() {
        Log.i(TAG, "releaseMirrorSession called")
        isMirrorActive = false
        checkIdleState()
    }

    /**
     * Release the Screenshot session.
     */
    fun releaseScreenshotSession() {
        Log.i(TAG, "releaseScreenshotSession called")
        isScreenshotActive = false
        checkIdleState()
    }

    private fun requestProjection(context: Context, onReady: (MediaProjection?) -> Unit) {
        val currentProj = sharedProjection
        if (currentProj != null) {
            // Already active, immediately return it
            onReady(currentProj)
            return
        }

        pendingCallbacks.add(onReady)

        if (_sessionState.value == SessionState.REQUESTING_PERMISSION || _sessionState.value == SessionState.STARTING) {
            // Already in progress, just wait
            return
        }

        _sessionState.value = SessionState.REQUESTING_PERMISSION

        // 1. Request permission natively
        // On Android 14+, MediaProjection intent data is single-use. We MUST launch the activity
        // every time we need a new token. (If user checks 'remember', the OS skips the dialog automatically)

        // 2. Request permission
        ScreenshotPermissionActivity.setPendingCaptureCallback { resultCode, intentData ->
            if (resultCode == Activity.RESULT_OK && intentData != null) {
                startProjection(context)
            } else {
                failSession("Permission denied")
            }
        }

        val intent = Intent(context, ScreenshotPermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun startProjection(context: Context) {
        _sessionState.value = SessionState.STARTING

        val (resultCode, data) = ScreenshotPermissionActivity.getPermissionData()
        if (resultCode != Activity.RESULT_OK || data == null) {
            failSession("No valid permission data")
            return
        }

        // Ensure foreground service is upgraded before calling getMediaProjection
        AgentService.updateForegroundType(context, true)

        // Wait a moment for service to upgrade
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mpManager.getMediaProjection(resultCode, data)
                
                // Android 14+ projection death callback
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    projection.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection stopped by system")
                            handleProjectionDeath(context)
                        }
                    }, Handler(Looper.getMainLooper()))
                }

                sharedProjection = projection
                _sessionState.value = SessionState.STREAMING
                Log.i(TAG, "Shared MediaProjection established")

                // Dispatch to pending consumers
                val callbacks = pendingCallbacks.toList()
                pendingCallbacks.clear()
                callbacks.forEach { it(projection) }

            } catch (e: Exception) {
                failSession("Failed to create MediaProjection: ${e.message}")
                ScreenshotPermissionActivity.reset()
            }
        }, 800)
    }

    private fun handleProjectionDeath(context: Context) {
        sharedProjection = null
        isMirrorActive = false
        isScreenshotActive = false
        _sessionState.value = SessionState.FAILED
        ScreenshotPermissionActivity.reset()
        AgentService.updateForegroundType(context, false)
        Log.w(TAG, "Projection died. Cleaned up.")
        // Optionally: Notify websocket that stream failed
    }

    private fun failSession(reason: String) {
        Log.e(TAG, "Session failed: \$reason")
        _sessionState.value = SessionState.FAILED
        val callbacks = pendingCallbacks.toList()
        pendingCallbacks.clear()
        callbacks.forEach { it(null) }
    }

    private fun checkIdleState() {
        if (!isMirrorActive && !isScreenshotActive) {
            startIdleTimeout()
        }
    }

    private fun startIdleTimeout() {
        cancelIdleTimeout()
        Log.i(TAG, "No active consumers. Starting 30s idle timeout.")
        idleTimeoutJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (!isMirrorActive && !isScreenshotActive) {
                Log.i(TAG, "Idle timeout reached. Tearing down MediaProjection.")
                teardown()
            }
        }
    }

    private fun cancelIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
    }

    private fun teardown() {
        _sessionState.value = SessionState.STOPPING
        try { sharedProjection?.stop() } catch (_: Exception) {}
        sharedProjection = null
        ScreenshotPermissionActivity.reset()
        
        // Context is tricky here, but AgentService can be downgraded if we keep an app context
        // We will expose a method on AgentService to downgrade it statically if possible,
        // or just let the consumers trigger downgrade.
        _sessionState.value = SessionState.IDLE
    }

    // Pass an app context to teardown explicitly
    fun forceTeardown(context: Context) {
        teardown()
        AgentService.updateForegroundType(context, false)
    }
}
