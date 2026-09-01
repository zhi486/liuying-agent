package com.healthyagent.running.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class BridgeEnvelope(
    val contractVersion: Int,
    val type: BridgeMessageType,
    val id: String,
    val sentAt: String,
    val payload: JsonObject,
)

@Serializable
enum class BridgeMessageType {
    @SerialName("hello")
    HELLO,

    @SerialName("ready")
    READY,

    @SerialName("snapshot")
    SNAPSHOT,

    @SerialName("command")
    COMMAND,

    @SerialName("result")
    RESULT,

    @SerialName("event")
    EVENT,
}

sealed interface BridgeDecodeResult {
    data class Accepted(val envelope: BridgeEnvelope) : BridgeDecodeResult

    data class Rejected(val error: BridgeErrorCode) : BridgeDecodeResult
}
