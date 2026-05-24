package com.yuwanadev.mdm.webrtc

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import org.webrtc.*

/**
 * A custom VideoCapturer that uses the shared MediaProjection instance.
 * Standard ScreenCapturerAndroid attempts to call getMediaProjection() internally,
 * which violates Android 14's strict single-use token constraints.
 */
class SharedMediaProjectionCapturer(
    private val mediaProjection: MediaProjection
) : VideoCapturer, VideoSink {

    private val TAG = "SharedCapturer"
    private var capturerObserver: CapturerObserver? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var virtualDisplay: VirtualDisplay? = null
    // Keep a strong reference to prevent GC from releasing the Surface
    private var surface: Surface? = null
    private var isDisposed = false
    private var isCapturing = false
    private var frameCount = 0L
    private var lastLogTime = 0L

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        context: Context?,
        capturerObserver: CapturerObserver?
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        if (isDisposed) {
            Log.w(TAG, "Cannot start capture, already disposed")
            return
        }

        Log.i(TAG, "Starting capture: ${width}x${height} @ $framerate FPS")
        surfaceTextureHelper?.setTextureSize(width, height)
        surfaceTextureHelper?.startListening(this)
        
        // Create and retain the Surface so it doesn't get GC'd
        surface = Surface(surfaceTextureHelper?.surfaceTexture)
        val density = 400 // Standard fallback density

        Handler(Looper.getMainLooper()).post {
            try {
                // Release old virtual display if restarting
                virtualDisplay?.release()
                virtualDisplay = null
                
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "WebRTC_Mirror",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, null
                )
                isCapturing = true
                frameCount = 0
                lastLogTime = System.currentTimeMillis()
                capturerObserver?.onCapturerStarted(true)
                Log.i(TAG, "VirtualDisplay created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create VirtualDisplay: ${e.message}", e)
                capturerObserver?.onCapturerStarted(false)
            }
        }
    }

    override fun stopCapture() {
        Log.i(TAG, "Stopping capture (delivered $frameCount frames)")
        isCapturing = false

        Handler(Looper.getMainLooper()).post {
            virtualDisplay?.release()
            virtualDisplay = null
        }
        surfaceTextureHelper?.stopListening()
        capturerObserver?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        Log.i(TAG, "changeCaptureFormat: ${width}x${height}")
    }

    override fun dispose() {
        isDisposed = true
        isCapturing = false
        surface?.release()
        surface = null
    }

    override fun isScreencast(): Boolean {
        return true
    }

    override fun onFrame(frame: VideoFrame?) {
        if (frame == null || !isCapturing) return
        
        try {
            capturerObserver?.onFrameCaptured(frame)
            frameCount++
            
            // Log frame stats every 10 seconds for diagnostics
            val now = System.currentTimeMillis()
            if (now - lastLogTime >= 10_000) {
                val elapsed = (now - lastLogTime) / 1000.0
                val fps = frameCount / elapsed
                Log.d(TAG, "Frame delivery: ${frameCount} frames, ~${"%.1f".format(fps)} fps")
                frameCount = 0
                lastLogTime = now
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding frame: ${e.message}")
        }
    }
}
