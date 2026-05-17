package com.yuwanadev.mdm.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

import com.yuwanadev.mdm.service.AgentService

/**
 * A transparent activity that requests MediaProjection permission.
 * This is needed because MediaProjection permission request requires an Activity context.
 *
 * When a pending capture callback is registered, it will be invoked after permission
 * is granted and the service type is upgraded to MEDIA_PROJECTION.
 */
class ScreenshotPermissionActivity : Activity() {

    companion object {
        private const val TAG = "ScreenshotPermission"
        private const val REQUEST_CODE = 1001

        private var permissionIntent: Intent? = null
        private var permissionResult: Int = RESULT_CANCELED

        // Callback for auto-capture after permission grant
        private var pendingCaptureCallback: ((Int, Intent?) -> Unit)? = null

        /** Get the granted permission intent, if any. */
        fun getPermissionData(): Pair<Int, Intent?> {
            return Pair(permissionResult, permissionIntent)
        }

        /** Check if we have a valid permission intent. */
        fun hasPermission(): Boolean {
            return permissionResult == RESULT_OK && permissionIntent != null
        }

        /** Reset permission data (tokens are one-time use on Android 14+). */
        fun reset() {
            permissionResult = RESULT_CANCELED
            permissionIntent = null
        }

        /** Set a callback to be invoked when permission is granted. */
        fun setPendingCaptureCallback(callback: ((Int, Intent?) -> Unit)?) {
            pendingCaptureCallback = callback
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_CODE)
        Log.d(TAG, "Started MediaProjection permission request")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE) {
            permissionResult = resultCode
            permissionIntent = data
            
            if (resultCode == RESULT_OK) {
                Log.i(TAG, "MediaProjection permission granted")
                
                // CRITICAL: Upgrade service type IMMEDIATELY while we're in the foreground.
                // Android 14+ requires FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION to be active
                // BEFORE getMediaProjection() is called.
                AgentService.updateForegroundType(this, true)
                
                val callback = pendingCaptureCallback
                pendingCaptureCallback = null
                
                if (callback != null) {
                    Log.i(TAG, "Waiting for service type upgrade before invoking capture...")
                    // Wait 800ms for the service to process the type upgrade intent,
                    // THEN invoke the capture callback
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.i(TAG, "Invoking pending capture callback")
                        callback.invoke(resultCode, data)
                    }, 800)
                    
                    // Delay finish to keep activity alive during full capture lifecycle
                    // 800ms (service upgrade) + 500ms (unused now) + 800ms (frame capture) + buffer
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "Delayed finish after capture")
                        finish()
                    }, 5000)
                } else {
                    finish()
                }
            } else {
                Log.w(TAG, "MediaProjection permission denied")
                pendingCaptureCallback = null
                finish()
            }
        }
    }
}
