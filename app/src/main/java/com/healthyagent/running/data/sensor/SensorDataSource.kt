package com.healthyagent.running.data.sensor

import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.core.model.SensorSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 传感器数据源接口 — 可插拔设计
 *
 * Mock 实现和 BLE 实现完全互换，只需切换 Koin 绑定。
 */
interface SensorDataSource {
    /** 连接状态 */
    val connectionState: StateFlow<ConnectionState>

    /** 传感器原始样本流（1Hz 为主） */
    val samples: Flow<SensorSample>

    /** 设备基础信息 */
    val deviceInfo: DeviceInfo

    /** 异步连接 */
    suspend fun connect()

    /** 断开连接 */
    suspend fun disconnect()
}
