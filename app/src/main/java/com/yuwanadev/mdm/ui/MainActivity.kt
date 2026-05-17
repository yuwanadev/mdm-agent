package com.yuwanadev.mdm.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yuwanadev.mdm.MdmApplication
import com.yuwanadev.mdm.R
import com.yuwanadev.mdm.commands.DeviceInfoCommand
import com.yuwanadev.mdm.databinding.ActivityMainBinding
import com.yuwanadev.mdm.service.AgentService
import com.yuwanadev.mdm.websocket.ConnectionState
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.yuwanadev.mdm.receiver.MdmDeviceAdminReceiver
import android.provider.Settings
import android.widget.Toast

/**
 * Main activity showing the current connection status and device info. Displays live battery,
 * temperature, RAM, and storage stats.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: MdmApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as MdmApplication

        // Check if configured — redirect to setup if not
        lifecycleScope.launch {
            if (!app.configStore.isReady()) {
                startActivity(Intent(this@MainActivity, SetupActivity::class.java))
                finish()
                return@launch
            }

            // Observe server URL and device ID
            val serverUrl = app.configStore.getServerUrl()
            binding.tvServerUrl.text = "Server: ${serverUrl ?: "—"}"

            launch {
                app.configStore.deviceId.collectLatest { deviceId ->
                    binding.tvDeviceId.text = "Device ID: ${deviceId ?: "Not assigned yet"}"
                }
            }

            // Observe connection state
            launch { app.connectionState.collectLatest { state -> updateStatusUI(state) } }

            // Ensure service is running
            AgentService.start(this@MainActivity)
        }

        // Set up status dot shape
        val dot =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.RED)
                }
        binding.viewStatusDot.background = dot

        // Disconnect button
        binding.btnDisconnect.setOnClickListener {
            AgentService.stop(this)
            updateStatusUI(ConnectionState.DISCONNECTED)
        }

        // Reconfigure button
        binding.btnReconfigure.setOnClickListener {
            AgentService.stop(this)
            lifecycleScope.launch {
                app.configStore.clear()
                startActivity(Intent(this@MainActivity, SetupActivity::class.java))
                finish()
            }
        }

        // Check permissions and admin status
        checkPermissions()
        checkAdminStatus()

        // Start UI update loop
        startDeviceInfoUpdater()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        } else {
            // Fine location already granted, now request background location
            requestBackgroundLocationIfNeeded()
        }

        // Check Usage Stats permission (special setting)
        checkUsageStatsPermission()

        // Check Overlay permission
        checkOverlayPermission()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            // After granting fine location, request background location separately
            requestBackgroundLocationIfNeeded()
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), 101)
            }
        }
    }

    private fun checkUsageStatsPermission() {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }

        if (mode != android.app.AppOpsManager.MODE_ALLOWED) {
            Toast.makeText(this, "Please enable Usage Access for app monitoring", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun checkOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please enable 'Display over other apps' to allow screen overlays", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }


    private fun checkAdminStatus() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, MdmDeviceAdminReceiver::class.java)
        
        if (!dpm.isAdminActive(admin)) {
            Toast.makeText(this, "Please enable Device Admin to use all features", Toast.LENGTH_LONG).show()
            // Optional: Auto-redirect to admin settings
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "YuwanaDev MDM requires Admin permission to manage device security.")
            }
            startActivity(intent)
        }
    }

    /** Periodically updates device info on screen (every 5 seconds). */
    private fun startDeviceInfoUpdater() {
        lifecycleScope.launch {
            while (isActive) {
                updateDeviceInfo()
                delay(5000)
            }
        }
    }

    private fun updateDeviceInfo() {
        val battery = DeviceInfoCommand.getBatteryLevel(this)
        val temp = DeviceInfoCommand.getBatteryTemperature(this)
        val ram = DeviceInfoCommand.getRAMUsageMB(this)
        val storageTotal = DeviceInfoCommand.getStorageTotalMB()
        val storageUsed = DeviceInfoCommand.getStorageUsedMB()

        binding.tvBattery.text = battery?.let { "$it%" } ?: "—"
        binding.tvTemperature.text = temp?.let { "%.1f°C".format(it) } ?: "—"
        binding.tvRam.text = ram?.let { "${it} MB" } ?: "—"
        binding.tvStorage.text =
                if (storageTotal != null && storageUsed != null) {
                    "${storageUsed} / ${storageTotal} MB"
                } else "—"
    }

    private fun updateStatusUI(state: ConnectionState) {
        val (text, color) =
                when (state) {
                    ConnectionState.CONNECTED ->
                            getString(R.string.status_connected) to Color.parseColor("#4CAF50")
                    ConnectionState.CONNECTING ->
                            getString(R.string.status_connecting) to Color.parseColor("#FFC107")
                    ConnectionState.RECONNECTING ->
                            getString(R.string.status_reconnecting) to Color.parseColor("#FFC107")
                    ConnectionState.DISCONNECTED ->
                            getString(R.string.status_disconnected) to Color.parseColor("#F44336")
                }

        binding.tvConnectionStatus.text = text
        (binding.viewStatusDot.background as? GradientDrawable)?.setColor(color)
    }
}
