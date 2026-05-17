package com.yuwanadev.mdm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yuwanadev.mdm.data.ConfigStore
import com.yuwanadev.mdm.service.AgentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Starts the MDM Agent service automatically after device reboot.
 * Checks ConfigStore (DataStore) to verify the device was previously registered.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            
            Log.i(TAG, "Device booted — checking if agent should auto-start...")

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val configStore = ConfigStore(context)
                    if (configStore.isReady()) {
                        Log.i(TAG, "Device registered — starting AgentService")
                        AgentService.start(context)
                    } else {
                        Log.w(TAG, "Device not registered — skipping auto-start")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during boot check: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
