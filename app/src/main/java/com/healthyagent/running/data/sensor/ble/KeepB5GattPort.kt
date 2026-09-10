package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.diagnostics.BleFailureReason
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

internal class KeepB5GattRegistrationGate {
    private val state = AtomicReference(State.PENDING)
    private val activation = CompletableDeferred<Boolean>()

    fun activate(): Boolean {
        if (!state.compareAndSet(State.PENDING, State.ACTIVE)) return false
        activation.complete(true)
        return true
    }

    fun close(): Boolean {
        while (true) {
            when (val current = state.get()) {
                State.CLOSED -> return false
                State.PENDING,
                State.ACTIVE,
                -> if (state.compareAndSet(current, State.CLOSED)) {
                    if (current == State.PENDING) activation.complete(false)
                    return true
                }
            }
        }
    }

    suspend fun awaitActivation(): Boolean = activation.await()

    fun tryAcquireExecution(): Boolean = state.compareAndSet(State.ACTIVE, State.ACTIVE)

    fun isActive(): Boolean = state.get() == State.ACTIVE

    private enum class State { PENDING, ACTIVE, CLOSED }
}

internal data class GattCharacteristicSnapshot(
    val uuid: UUID,
    val properties: Int,
)

internal data class GattServiceSnapshot(
    val characteristicsByService: Map<UUID, List<GattCharacteristicSnapshot>>,
)

internal sealed interface RawGattCallbackEvent {
    data class ConnectionStateChanged(
        val status: Int,
        val newState: Int,
    ) : RawGattCallbackEvent
    data class ServicesDiscovered(
        val status: Int,
        val services: GattServiceSnapshot,
    ) : RawGattCallbackEvent
    data class DescriptorWrite(
        val status: Int,
        val serviceUuid: UUID,
        val descriptorUuid: UUID,
        val characteristicUuid: UUID,
    ) : RawGattCallbackEvent
    data class CharacteristicChanged(
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val value: ByteArray,
        val receivedAtMillis: Long,
        val wallClockMillis: Long,
    ) : RawGattCallbackEvent
    data class CharacteristicRead(
        val status: Int,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val value: ByteArray,
        val receivedAtMillis: Long,
        val wallClockMillis: Long,
    ) : RawGattCallbackEvent
}

internal sealed interface GattCallbackDecision {
    data class Emit(val signal: RawGattSignal) : GattCallbackDecision
    data object DiscoverServices : GattCallbackDecision
    data class EnableNotifications(
        val channel: KeepB5ChannelKind,
        val fallbackOnFailure: KeepB5ChannelKind? = null,
    ) : GattCallbackDecision
    data class NotificationDescriptorResult(
        val channel: KeepB5ChannelKind,
        val accepted: Boolean,
    ) : GattCallbackDecision
    data object ReadDeviceMetadata : GattCallbackDecision
    data object ReadFirmware : GattCallbackDecision
    data class UpdateBattery(
        val percent: Int?,
        val receivedAtMillis: Long,
        val wallClockMillis: Long,
    ) : GattCallbackDecision
    data class UpdateFirmware(val version: String?) : GattCallbackDecision
}

internal enum class GattIoSubmission { ACCEPTED, REJECTED, UNAVAILABLE }

internal interface KeepB5GattIoPort {
    fun discoverServices(): GattIoSubmission
    fun enableNotifications(channel: KeepB5ChannelKind): GattIoSubmission
    fun readBattery(): GattIoSubmission
    fun readFirmware(): GattIoSubmission
}

internal sealed interface RawGattSignal {
    data object Connected : RawGattSignal
    data class ServicesDiscovered(val channel: KeepB5ChannelKind) : RawGattSignal
    data class NotificationsEnabled(val channel: KeepB5ChannelKind) : RawGattSignal
    data class PrivateNotification(
        val frame: ByteArray,
        val receivedAtMillis: Long,
        val wallClockMillis: Long,
    ) : RawGattSignal
    data class StandardHeartRateNotification(
        val frame: ByteArray,
        val receivedAtMillis: Long,
        val wallClockMillis: Long,
    ) : RawGattSignal
    data class BatteryLevel(
        val percent: Int,
        val receivedAtMillis: Long,
        val wallClockMillis: Long,
    ) : RawGattSignal
    data class DeviceInfoUpdated(val deviceInfo: DeviceInfo) : RawGattSignal
    data class Disconnected(val reason: BleFailureReason?) : RawGattSignal
    data class Error(val reason: BleFailureReason) : RawGattSignal
}

internal sealed interface MappedGattOutput {
    data class Control(val event: BleTransportEvent) : MappedGattOutput
    data class Activity(val value: PrivateWorkoutActivity) : MappedGattOutput
    data class Sample(val value: TokenizedSensorSample) : MappedGattOutput
}

internal interface KeepB5GattSession {
    fun sendStart(epochSeconds: Long): Boolean
    fun sendWorkoutUpdate(
        elapsedSeconds: Long,
        displayMetrics: KeepB5DisplayMetrics = KeepB5DisplayMetrics(),
    ): Boolean
    fun sendHeartbeat(): Boolean
    fun shutdownCallbacks(): Job
    fun disconnect()
    fun close()
}

internal fun interface KeepB5GattConnector {
    fun connect(
        target: KeepB5ConnectTarget,
        registrationGate: KeepB5GattRegistrationGate,
        listener: KeepB5GattSessionListener,
    ): KeepB5GattSession?
}

internal interface KeepB5GattSessionListener {
    fun onSignal(session: KeepB5GattSession, signal: RawGattSignal)
}
