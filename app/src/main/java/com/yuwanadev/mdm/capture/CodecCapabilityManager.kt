package com.yuwanadev.mdm.capture

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

/**
 * CodecCapabilityManager
 * Evaluates device hardware capabilities for WebRTC encoding.
 * Prevents crashes on broken vendor ROMs by detecting missing AVC support.
 */
object CodecCapabilityManager {
    private const val TAG = "CodecCapability"
    private const val MIME_TYPE_H264 = "video/avc"

    data class CodecDiagnostics(
        val hasH264HardwareEncoder: Boolean,
        val maxSupportedWidth: Int,
        val maxSupportedHeight: Int,
        val recommendedBitrateKbps: Int
    )

    fun runDiagnostics(): CodecDiagnostics {
        var hasH264Hardware = false
        var maxWidth = 1280
        var maxHeight = 720

        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                
                val types = info.supportedTypes
                if (types.contains(MIME_TYPE_H264)) {
                    // Check if it's a hardware encoder
                    // On older APIs, we check if the name doesn't start with "OMX.google."
                    val isHardware = !info.name.startsWith("OMX.google.") && 
                                     !info.name.lowercase().contains("sw")
                    
                    if (isHardware) {
                        hasH264Hardware = true
                        Log.i(TAG, "Found H264 HW Encoder: \${info.name}")

                        try {
                            val caps = info.getCapabilitiesForType(MIME_TYPE_H264)
                            val videoCaps = caps.videoCapabilities
                            if (videoCaps != null) {
                                maxWidth = videoCaps.supportedWidths.upper ?: 1920
                                maxHeight = videoCaps.supportedHeights.upper ?: 1080
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to get video caps for \${info.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Codec diagnostics failed: \${e.message}")
        }

        // Recommend a safe default bitrate (1.5 Mbps for 720p hardware)
        val recommendedBitrate = if (hasH264Hardware) 1500 else 800

        val diag = CodecDiagnostics(
            hasH264HardwareEncoder = hasH264Hardware,
            maxSupportedWidth = maxWidth,
            maxSupportedHeight = maxHeight,
            recommendedBitrateKbps = recommendedBitrate
        )

        Log.i(TAG, "Diagnostics Complete: \$diag")
        return diag
    }
}
