package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.diagnostics.BleFailureReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow

internal sealed interface BleTransportEvent {
    data class ScanMatched(val guard: KeepB5AttemptGuard) : BleTransportEvent
    data class ScanFailed(
        val guard: KeepB5AttemptGuard,
        val reason: BleFailureReason,
    ) : BleTransportEvent
    data class ConnectFailed(
        val guard: KeepB5AttemptGuard,
        val reason: BleFailureReason,
    ) : BleTransportEvent
    data class GattCreated(val guard: KeepB5GattGuard) : BleTransportEvent
    data class GattConnected(val guard: KeepB5GattGuard) : BleTransportEvent
    data class ServicesDiscovered(
        val guard: KeepB5GattGuard,
        val channel: KeepB5ChannelKind,
    ) : BleTransportEvent
    data class NotificationsEnabled(
        val guard: KeepB5GattGuard,
        val channel: KeepB5ChannelKind,
    ) : BleTransportEvent
    data class StartWriteCompleted(
        val guard: KeepB5GattGuard,
        val accepted: Boolean,
    ) : BleTransportEvent
    data class SyncWriteCompleted(
        val submission: KeepB5SubmittedWrite,
        val accepted: Boolean,
    ) : BleTransportEvent
    data class GattDisconnected(
        val guard: KeepB5GattGuard,
        val reason: BleFailureReason?,
    ) : BleTransportEvent
    data class GattError(
        val guard: KeepB5GattGuard,
        val reason: BleFailureReason,
    ) : BleTransportEvent
    data class DeviceInfoUpdated(
        val guard: KeepB5GattGuard,
        val deviceInfo: DeviceInfo,
    ) : BleTransportEvent
}

internal interface BleTransport {
    val controlEvents: Flow<BleTransportEvent>
    val privateActivities: Flow<PrivateWorkoutActivity>
    val samples: Flow<TokenizedSensorSample>

    fun startScan(guard: KeepB5AttemptGuard): Boolean
    fun stopScan(guard: KeepB5AttemptGuard, preserveMatchedTarget: Boolean)
    fun connectRemembered(guard: KeepB5AttemptGuard): Boolean
    fun cancelPendingConnect(guard: KeepB5AttemptGuard)
    fun activateGattData(guard: KeepB5GattGuard)
    fun quiesceGattCallbacks(guard: KeepB5GattGuard): Job
    fun sendStart(guard: KeepB5GattGuard, epochSeconds: Long): Boolean
    fun sendWorkoutUpdate(
        submission: KeepB5SubmittedWrite,
        elapsedSeconds: Long,
        displayMetrics: KeepB5DisplayMetrics = KeepB5DisplayMetrics(),
    ): Boolean
    fun sendHeartbeat(submission: KeepB5SubmittedWrite): Boolean
    fun closeGatt(guard: KeepB5GattGuard, disconnectFirst: Boolean): Job
    fun shutdown(): Job
}

internal interface ReconnectBackoffPolicy {
    fun nextDelayMillis(): Long
    fun reset()
}
