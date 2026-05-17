package com.yuwanadev.mdm.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class DownloadHelper(private val context: Context) {
    companion object {
        private const val TAG = "DownloadHelper"
    }

    private val client = OkHttpClient()

    fun downloadAndInstall(url: String, onProgress: (Int) -> Unit, onResult: (Boolean, String) -> Unit) {
        val request = Request.Builder().url(url).build()
        
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                onResult(false, "Download failed: ${response.code}")
                return
            }

            val body = response.body ?: throw Exception("Empty body")
            val totalSize = body.contentLength()
            
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
            if (file.exists()) file.delete()

            val input = body.byteStream()
            val output = FileOutputStream(file)
            
            val buffer = ByteArray(1024)
            var bytesRead: Int
            var totalRead = 0L

            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalSize > 0) {
                    onProgress(((totalRead * 100) / totalSize).toInt())
                }
            }

            output.flush()
            output.close()
            input.close()

            Log.i(TAG, "Download complete: ${file.absolutePath}")
            installApk(file, onResult)

        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            onResult(false, "Error: ${e.message}")
        }
    }

    private fun installApk(file: File, onResult: (Boolean, String) -> Unit) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(intent)
            onResult(true, "Installation started")
        } catch (e: Exception) {
            Log.e(TAG, "Install failed: ${e.message}")
            onResult(false, "Install failed: ${e.message}")
        }
    }
}
