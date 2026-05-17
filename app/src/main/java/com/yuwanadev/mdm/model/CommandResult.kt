package com.yuwanadev.mdm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class HeartbeatPayload(
    @SerialName("battery") val battery: Int?,
    @SerialName("temperature") val temperature: Float?,
    @SerialName("battery_health") val batteryHealth: String?,
    @SerialName("battery_status") val batteryStatus: String?,
    @SerialName("battery_technology") val batteryTechnology: String?,
    @SerialName("battery_voltage") val batteryVoltage: Int?,
    @SerialName("ram_usage") val ramUsage: Long?,
    @SerialName("storage_total") val storageTotal: Long?,
    @SerialName("storage_used") val storageUsed: Long?,
    @SerialName("app_version") val appVersion: String?,
    @SerialName("network_info") val networkInfo: JsonElement?,
    @SerialName("foreground_app") val foregroundApp: String?,
    @SerialName("network_strength") val networkStrength: Int?,
    @SerialName("location") val location: JsonElement?
)

@Serializable
data class CommandResult(
    @SerialName("command_id") val commandId: String,
    @SerialName("success") val success: Boolean,
    @SerialName("message") val message: String,
    @SerialName("data") val data: JsonElement? = null
)
