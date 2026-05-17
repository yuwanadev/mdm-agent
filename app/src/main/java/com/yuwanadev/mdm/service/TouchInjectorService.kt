package com.yuwanadev.mdm.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * TouchInjectorService uses Android's AccessibilityService API
 * to inject touch gestures (tap, swipe, long press) on the device.
 *
 * Requirements:
 * - Must be manually enabled in Settings → Accessibility
 * - Works without root
 * - Available on Android 7.0+ (API 24)
 */
class TouchInjectorService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchInjector"

        @Volatile
        private var instance: TouchInjectorService? = null

        fun getInstance(): TouchInjectorService? = instance

        fun isAvailable(): Boolean = instance != null

        /**
         * Inject a tap gesture at (x, y) screen coordinates.
         */
        fun injectTap(x: Float, y: Float): Boolean {
            val service = instance ?: run {
                Log.e(TAG, "Service not available — enable in Accessibility settings")
                return false
            }
            return service.performTap(x, y)
        }

        /**
         * Inject a swipe gesture from (x1, y1) to (x2, y2).
         */
        fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
            val service = instance ?: run {
                Log.e(TAG, "Service not available — enable in Accessibility settings")
                return false
            }
            return service.performSwipe(x1, y1, x2, y2, durationMs)
        }

        /**
         * Inject a long press gesture at (x, y).
         */
        fun injectLongPress(x: Float, y: Float, durationMs: Long = 800): Boolean {
            val service = instance ?: run {
                Log.e(TAG, "Service not available — enable in Accessibility settings")
                return false
            }
            return service.performLongPress(x, y, durationMs)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "TouchInjectorService connected and ready")
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "TouchInjectorService destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — we only need gesture dispatch
    }

    override fun onInterrupt() {
        Log.w(TAG, "TouchInjectorService interrupted")
    }

    // ── Gesture implementations ───────────────────────────

    private fun performTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gestures require Android 7.0+")
            return false
        }

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        Log.d(TAG, "Injecting tap at ($x, $y)")
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap cancelled")
            }
        }, null)
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gestures require Android 7.0+")
            return false
        }

        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        Log.d(TAG, "Injecting swipe ($x1,$y1) → ($x2,$y2) over ${durationMs}ms")
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe cancelled")
            }
        }, null)
    }

    private fun performLongPress(x: Float, y: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gestures require Android 7.0+")
            return false
        }

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        Log.d(TAG, "Injecting long press at ($x, $y) for ${durationMs}ms")
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Long press completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Long press cancelled")
            }
        }, null)
    }
}
