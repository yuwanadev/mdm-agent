package com.yuwanadev.mdm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DeviceInfo represents a snapshot of the device state.
 */
@Serializable
data class DeviceInfo(
    @SerialName("model") val model: String,
    @SerialName("os_version") val os_version: String,
    @SerialName("battery_level") val battery_level: Int,
    @SerialName("is_online") val is_online: Boolean,
    @SerialName("storage_used") val storage_used: Long,
    @SerialName("storage_total") val storage_total: Long,
    @SerialName("ram_used") val ram_used: Long,
    @SerialName("ram_total") val ram_total: Long,
    @SerialName("cpu_usage") val cpu_usage: Int,
    @SerialName("latitude") val latitude: Double,
    @SerialName("longitude") val longitude: Double
)

/**
 * CommandPayload represents an incoming command from the server.
 */
@Serializable
data class CommandPayload(
    @SerialName("command_id") val commandId: String,
    @SerialName("type") val type: String,
    @SerialName("payload") val payload: JsonElement? = null
)

/**
 * DeviceInfoPayload is sent by the agent on connect and on request.
 */
@Serializable
data class DeviceInfoPayload(
    @SerialName("model") val model: String,
    @SerialName("manufacturer") val manufacturer: String,
    @SerialName("android_version") val androidVersion: String,
    @SerialName("app_version") val appVersion: String
)

/**
 * AckPayload is received from the server on successful connection.
 */
@Serializable
data class AckPayload(
    @SerialName("device_id") val deviceId: String
)
