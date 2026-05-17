package com.yuwanadev.mdm.commands

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.yuwanadev.mdm.model.DeviceInfo
import kotlinx.serialization.json.*
import java.io.File

/**
 * Utility for collecting device information.
 */
object DeviceInfoCommand {

    fun collect(context: Context): DeviceInfo {
        val locationJson = getLatestLocation(context)
        val lat = try { (locationJson as? kotlinx.serialization.json.JsonObject)?.get("lat")?.jsonPrimitive?.double ?: 0.0 } catch(e: Exception) { 0.0 }
        val lon = try { (locationJson as? kotlinx.serialization.json.JsonObject)?.get("lon")?.jsonPrimitive?.double ?: 0.0 } catch(e: Exception) { 0.0 }

        return DeviceInfo(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            os_version = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            battery_level = getBatteryLevel(context) ?: 0,
            is_online = true,
            storage_used = getStorageUsedMB() ?: 0,
            storage_total = getStorageTotalMB() ?: 0,
            ram_used = getRAMUsageMB(context) ?: 0,
            ram_total = getRAMTotalMB(context) ?: 0,
            cpu_usage = 0, // Hard to get accurately on modern Android without native code
            latitude = lat,
            longitude = lon
        )
    }

    fun getBatteryLevel(context: Context): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else null
        }
    }

    fun getBatteryTemperature(context: Context): Float? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.let {
            val temp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (temp != -1) temp / 10f else null
        }
    }

    fun getBatteryHealth(context: Context): String? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.let {
            when (it.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                else -> "Unknown"
            }
        }
    }

    fun getBatteryStatus(context: Context): String? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.let {
            when (it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                else -> "Unknown"
            }
        }
    }

    fun getBatteryTechnology(context: Context): String? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
    }

    fun getBatteryVoltage(context: Context): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
    }

    fun getAppVersion(context: Context): String? {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName
        } catch (e: Exception) {
            "Unknown"
        }
    }

    fun getNetworkInfo(context: Context): kotlinx.serialization.json.JsonElement? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return null
        
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> "Other"
        }

        return kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive(type))
            put("ip", kotlinx.serialization.json.JsonPrimitive(getLocalIpAddress() ?: "Unknown"))
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }

    fun getForegroundApp(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 1000 * 60 // last minute
        val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        
        if (stats != null && stats.isNotEmpty()) {
            val sortedStats = stats.sortedByDescending { it.lastTimeUsed }
            return sortedStats[0].packageName
        }

        // Fallback for older devices or if permission is not granted
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = am.getRunningTasks(1)
        return if (tasks.isNotEmpty()) tasks[0].topActivity?.packageName else "Unknown"
    }

    fun getNetworkStrength(context: Context): Int? {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                telephonyManager.signalStrength?.level ?: -1
            } else {
                -1 // Fallback for older devices
            }
        } catch (e: Exception) {
            -1
        }
    }

    fun getLatestLocation(context: Context): kotlinx.serialization.json.JsonElement? {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = locationManager.getProviders(true)
            var bestLocation: android.location.Location? = null
            
            for (provider in providers) {
                val l = try {
                    locationManager.getLastKnownLocation(provider)
                } catch (e: SecurityException) {
                    null
                }
                if (l != null && (bestLocation == null || l.accuracy < bestLocation!!.accuracy)) {
                    bestLocation = l
                }
            }

            bestLocation?.let { location ->
                kotlinx.serialization.json.buildJsonObject {
                    put("lat", kotlinx.serialization.json.JsonPrimitive(location.latitude))
                    put("lon", kotlinx.serialization.json.JsonPrimitive(location.longitude))
                    put("acc", kotlinx.serialization.json.JsonPrimitive(location.accuracy))
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getRAMTotalMB(context: Context): Long? {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    fun getRAMUsageMB(context: Context): Long? {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        return (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
    }

    fun getStorageTotalMB(): Long? {
        val stat = StatFs(Environment.getDataDirectory().path)
        return stat.totalBytes / (1024 * 1024)
    }

    fun getStorageUsedMB(): Long? {
        val stat = StatFs(Environment.getDataDirectory().path)
        return (stat.totalBytes - stat.availableBytes) / (1024 * 1024)
    }
}
