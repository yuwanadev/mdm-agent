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
    private var isDisposed = false

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
        capturerObserver?.onCapturerStarted(true)
        
        val surface = Surface(surfaceTextureHelper?.surfaceTexture)
        val density = 400 // Standard fallback density

        Handler(Looper.getMainLooper()).post {
            try {
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "WebRTC_Mirror",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, null
                )
                Log.i(TAG, "VirtualDisplay created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create VirtualDisplay: \${e.message}", e)
            }
        }
    }

    override fun stopCapture() {
        Log.i(TAG, "Stopping capture")
        Handler(Looper.getMainLooper()).post {
            virtualDisplay?.release()
            virtualDisplay = null
        }
        surfaceTextureHelper?.stopListening()
        capturerObserver?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        // Ideally we would tear down and recreate the VirtualDisplay with new bounds here
        // for dynamic rotation/resizing support.
        Log.i(TAG, "changeCaptureFormat: \${width}x\${height}")
    }

    override fun dispose() {
        isDisposed = true
    }

    override fun isScreencast(): Boolean {
        return true
    }

    override fun onFrame(frame: VideoFrame?) {
        if (frame != null) {
            capturerObserver?.onFrameCaptured(frame)
        }
    }
}
