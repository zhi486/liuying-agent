package com.healthyagent.running.data.sensor

import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.core.model.RoutePoint
import com.healthyagent.running.core.model.SensorSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 传感器数据源接口 — 可插拔设计
 *
 * Mock 实现和 BLE 实现完全互换，只需切换 Koin 绑定。
 */
interface SensorDataSource {
    /** 连接状态 */
    val connectionState: StateFlow<ConnectionState>

    /** 可选穿戴设备状态；手机主数据源实现无需提供。 */
    val wearableConnectionState: StateFlow<ConnectionState>?
        get() = null

    /** 可选运动链路事实；预连接、初次握手不等同于运动就绪。 */
    val wearableLinkObservation: StateFlow<WearableLinkObservation>?
        get() = null

    /** 传感器原始样本流（1Hz 为主） */
    val samples: Flow<SensorSample>

    /** 仅手机定位主源提供；Mock/BLE 默认为空。 */
    val routePoints: Flow<RoutePoint>
        get() = emptyFlow()

    /** 设备基础信息 */
    val deviceInfo: DeviceInfo

    /** 主数据源信息；默认兼容单一数据源实现。 */
    val primaryDeviceInfo: DeviceInfo
        get() = deviceInfo

    /** 可选穿戴设备信息；手机或 Mock 单一数据源默认为空。 */
    val wearableDeviceInfo: DeviceInfo?
        get() = null

    /** 异步连接 */
    suspend fun connect()

    /** 开跑前仅准备可选手环连接；不得启动手机采样或手环运动。 */
    suspend fun prepareConnection() = Unit

    /** 断开连接 */
    suspend fun disconnect()
}

/**
 * 跑步会话生命周期通知。
 *
 * 实现必须保证以下调用快速且线程安全，不得阻塞协调器线程。
 */
interface RunLifecycleAwareSensor {
    /** 新跑开始时通知当前累计秒数。 */
    fun onRunStarted(elapsedSeconds: Long)

    /** 跑步暂停并冻结后通知冻结的累计秒数。 */
    fun onRunPaused(elapsedSeconds: Long)

    /** 跑步继续前通知当前累计秒数。 */
    fun onRunResumed(elapsedSeconds: Long)

    /** 当前跑步已累计的手机指标；无有效配速传 null，不得阻塞协调器。 */
    fun onRunMetricsUpdated(distanceMeters: Double, paceSecPerKm: Double?) = Unit

    /** 跑步结束前通知停止。 */
    fun onRunStopped()
}
