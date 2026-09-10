package com.healthyagent.running.data.sensor

import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.core.model.RoutePoint
import com.healthyagent.running.core.model.SensorSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge

/**
 * 手机传感器是跑步主链路，穿戴设备只补充心率和电量。
 * 穿戴设备失败或断开不会终止 GPS 数据流。
 */
class CompositeSensorDataSource(
    private val phone: SensorDataSource,
    private val wearable: SensorDataSource?,
) : SensorDataSource, RunLifecycleAwareSensor {
    override val connectionState: StateFlow<ConnectionState> = phone.connectionState
    override val wearableConnectionState: StateFlow<ConnectionState>? = wearable?.connectionState

    override val samples: Flow<SensorSample> = wearable?.let {
        merge(phone.samples, it.samples)
    } ?: phone.samples
    override val routePoints: Flow<RoutePoint> = phone.routePoints

    override val primaryDeviceInfo: DeviceInfo
        get() = phone.primaryDeviceInfo

    override val wearableDeviceInfo: DeviceInfo?
        get() = wearable?.deviceInfo

    /** 兼容旧调用方，但复合源的身份始终代表手机主链路。 */
    override val deviceInfo: DeviceInfo
        get() = primaryDeviceInfo

    override suspend fun connect() {
        phone.connect()
        runCatching { wearable?.connect() }
    }

    override suspend fun prepareConnection() {
        wearable?.prepareConnection()
    }

    override suspend fun disconnect() {
        runCatching { wearable?.disconnect() }
        phone.disconnect()
    }

    override fun onRunStarted(elapsedSeconds: Long) {
        (wearable as? RunLifecycleAwareSensor)?.onRunStarted(elapsedSeconds)
    }

    override fun onRunPaused(elapsedSeconds: Long) {
        (wearable as? RunLifecycleAwareSensor)?.onRunPaused(elapsedSeconds)
    }

    override fun onRunResumed(elapsedSeconds: Long) {
        (wearable as? RunLifecycleAwareSensor)?.onRunResumed(elapsedSeconds)
    }

    override fun onRunStopped() {
        (wearable as? RunLifecycleAwareSensor)?.onRunStopped()
    }

    override fun onRunMetricsUpdated(distanceMeters: Double, paceSecPerKm: Double?) {
        (wearable as? RunLifecycleAwareSensor)?.onRunMetricsUpdated(distanceMeters, paceSecPerKm)
    }
}
