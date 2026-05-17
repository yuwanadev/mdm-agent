package com.yuwanadev.mdm.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * AgentMessageType defines the types of messages that can be exchanged between agent and server.
 */
object AgentMessageType {
    const val COMMAND = "COMMAND"
    const val COMMAND_RESULT = "COMMAND_RESULT"
    const val DEVICE_INFO = "DEVICE_INFO"
    const val HEARTBEAT = "STATUS_UPDATE"
    const val ACK = "ACK"
}

/**
 * WSMessage is the base wrapper for all WebSocket communications.
 */
@Serializable
data class WSMessage(
    val type: String,
    val payload: JsonElement,
    val timestamp: String
)
