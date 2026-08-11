package com.healthyagent.running.core.model

/**
 * 传感器连接状态
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * 设备信息
 */
data class DeviceInfo(
    val name: String,
    val batteryPercent: Int? = null,
    val firmwareVersion: String? = null,
)
