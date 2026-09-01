package com.healthyagent.running.bridge

import kotlinx.serialization.Serializable

@Serializable
enum class BridgeErrorCode {
    BRIDGE_VERSION_UNSUPPORTED,
    INVALID_MESSAGE,
    COMMAND_NOT_ALLOWED,
    PERMISSION_REQUIRED,
    SESSION_CONFLICT,
    RESOURCE_UNAVAILABLE,
    WEB_RUNTIME_FAILED,
    INTERNAL_ERROR,
}
