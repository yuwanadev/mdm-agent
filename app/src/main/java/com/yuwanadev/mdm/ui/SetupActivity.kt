package com.yuwanadev.mdm.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yuwanadev.mdm.MdmApplication
import com.yuwanadev.mdm.databinding.ActivitySetupBinding
import com.yuwanadev.mdm.service.AgentService
import kotlinx.coroutines.launch

/**
 * Setup activity — first screen shown on launch. Collects server URL and device token, then starts
 * the agent service.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var app: MdmApplication

    private val notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    proceedToConnect()
                } else {
                    // Still allow connection without notification permission
                    proceedToConnect()
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as MdmApplication

        // Check if already configured — skip to main
        lifecycleScope.launch {
            if (app.configStore.isReady()) {
                navigateToMain()
                return@launch
            }
        }

        binding.btnConnect.setOnClickListener { handleConnect() }
    }

    private fun handleConnect() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        val token = binding.etToken.text.toString().trim()

        // Validate
        if (serverUrl.isEmpty()) {
            binding.tilServerUrl.error = "Server URL is required"
            return
        }
        binding.tilServerUrl.error = null

        if (token.isEmpty()) {
            binding.tilToken.error = "Device token is required"
            return
        }
        binding.tilToken.error = null

        // Ensure URL has ws:// or wss:// prefix and /ws/device suffix
        var normalizedUrl =
                when {
                    serverUrl.startsWith("ws://") || serverUrl.startsWith("wss://") -> serverUrl
                    serverUrl.startsWith("http://") -> serverUrl.replace("http://", "ws://")
                    serverUrl.startsWith("https://") -> serverUrl.replace("https://", "wss://")
                    else -> "ws://$serverUrl"
                }

        if (!normalizedUrl.contains("/ws/device") && !normalizedUrl.contains("/ws/dashboard")) {
            normalizedUrl = normalizedUrl.trimEnd('/') + "/ws/device"
        }

        // Show loading
        binding.btnConnect.visibility = View.INVISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE

        // Save config
        lifecycleScope.launch {
            app.configStore.saveConfig(normalizedUrl, token)

            // Request notification permission (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                                this@SetupActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return@launch
                }
            }

            proceedToConnect()
        }
    }

    private fun proceedToConnect() {
        // Request battery optimization exemption
        requestBatteryOptimizationWhitelist()

        // Start agent service
        AgentService.start(this)

        // Navigate to main
        navigateToMain()
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun requestBatteryOptimizationWhitelist() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent =
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                startActivity(intent)
            } catch (_: Exception) {
                // Some OEMs don't support this intent
            }
        }
    }
}
