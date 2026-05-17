package com.yuwanadev.mdm.commands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.yuwanadev.mdm.ui.ScreenshotPermissionActivity
import java.io.ByteArrayOutputStream

/**
 * ScreenshotHelper handles screen capture using MediaProjection.
 *
 * Android 14+ enforces strict single-use rules:
 *   - MediaProjection tokens are one-time use
 *   - createVirtualDisplay can only be called ONCE per MediaProjection instance
 *
 * Strategy: keep the entire capture pipeline (projection + virtual display + image reader)
 * alive between captures. To take a screenshot, just acquireLatestImage() from the
 * already-running image reader. User only consents once per session.
 */
class ScreenshotHelper(private val context: Context) {

    companion object {
        private const val TAG = "ScreenshotHelper"

        // Persistent capture pipeline — all kept alive between screenshots
        private var activeProjection: MediaProjection? = null
        private var activeVirtualDisplay: VirtualDisplay? = null
        private var activeImageReader: ImageReader? = null
        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        /**
         * Check if the capture pipeline is already running.
         */
        fun isReady(): Boolean = activeProjection != null && activeVirtualDisplay != null && activeImageReader != null

        /**
         * Tear down the entire capture pipeline.
         */
        fun invalidate() {
            Log.d(TAG, "Invalidating capture pipeline")
            try { activeVirtualDisplay?.release() } catch (_: Exception) {}
            try { activeImageReader?.close() } catch (_: Exception) {}
            activeVirtualDisplay = null
            activeImageReader = null
            activeProjection = null
            com.yuwanadev.mdm.capture.CaptureSessionCoordinator.releaseScreenshotSession()
        }
    }

    data class CaptureResult(val base64: String?, val error: String?)

    fun initPipeline(onResult: (CaptureResult) -> Unit) {
        try {
            com.yuwanadev.mdm.capture.CaptureSessionCoordinator.acquireScreenshotSession(context) { projection ->
                if (projection == null) {
                    onResult(CaptureResult(null, "Failed to acquire MediaProjection session"))
                    return@acquireScreenshotSession
                }

                // Get screen dimensions
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
                val density = metrics.densityDpi

                val imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

                val virtualDisplay: VirtualDisplay
                try {
                    virtualDisplay = projection.createVirtualDisplay(
                        "MDM-Screenshot",
                        screenWidth, screenHeight, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.surface, null, null
                    )
                    Log.d(TAG, "Pipeline initialized: \${screenWidth}x\${screenHeight}")
                } catch (e: Exception) {
                    val msg = "createVirtualDisplay failed: \${e.message}"
                    Log.e(TAG, msg, e)
                    imageReader.close()
                    com.yuwanadev.mdm.capture.CaptureSessionCoordinator.releaseScreenshotSession()
                    onResult(CaptureResult(null, msg))
                    return@acquireScreenshotSession
                }

                // Store the active pipeline
                activeProjection = projection
                activeVirtualDisplay = virtualDisplay
                activeImageReader = imageReader

                // Wait for the first frame to be ready, then capture
                Handler(Looper.getMainLooper()).postDelayed({
                    grabFrame(onResult)
                }, 1000)
            }
        } catch (e: Exception) {
            val msg = "Init error: \${e.message}"
            Log.e(TAG, msg, e)
            onResult(CaptureResult(null, msg))
        }
    }

    /**
     * Grab a frame from the already-running pipeline.
     * First tries to use a buffered frame. If buffer is empty, waits for next frame via listener.
     */
    fun grabFrame(onResult: (CaptureResult) -> Unit) {
        val reader = activeImageReader
        if (reader == null) {
            onResult(CaptureResult(null, "Pipeline not active — need permission"))
            return
        }

        val handler = Handler(Looper.getMainLooper())

        // Try to grab a buffered frame immediately
        try {
            val image = reader.acquireLatestImage()
            if (image != null) {
                Log.d(TAG, "Got buffered frame immediately")
                val result = processImage(image)
                onResult(result)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct acquire failed: ${e.message}")
        }

        // No buffered frame — wait for VirtualDisplay to produce a new one
        Log.d(TAG, "No buffered frame, waiting for next via listener...")
        var delivered = false

        reader.setOnImageAvailableListener({ imgReader ->
            if (delivered) return@setOnImageAvailableListener
            delivered = true
            imgReader.setOnImageAvailableListener(null, null)

            try {
                val image = imgReader.acquireLatestImage()
                if (image != null) {
                    val result = processImage(image)
                    onResult(result)
                } else {
                    onResult(CaptureResult(null, "Listener fired but no image available"))
                }
            } catch (e: Exception) {
                val msg = "Frame error: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, msg, e)
                invalidate()
                onResult(CaptureResult(null, msg))
            }
        }, handler)

        // Timeout after 3 seconds
        handler.postDelayed({
            if (!delivered) {
                delivered = true
                reader.setOnImageAvailableListener(null, null)
                Log.w(TAG, "Frame grab timed out — pipeline may be stale, reinitializing on next capture")
                invalidate()
                onResult(CaptureResult(null, "Timed out — pipeline was stale. Try again."))
            }
        }, 3000)
    }

    private fun processImage(image: android.media.Image): CaptureResult {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            if (bitmap != croppedBitmap) bitmap.recycle()

            val outputStream = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            croppedBitmap.recycle()

            val b64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            Log.i(TAG, "Frame processed: ${b64.length} chars")
            CaptureResult(b64, null)
        } catch (e: Exception) {
            try { image.close() } catch (_: Exception) {}
            val msg = "processImage error: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, msg, e)
            CaptureResult(null, msg)
        }
    }
}
