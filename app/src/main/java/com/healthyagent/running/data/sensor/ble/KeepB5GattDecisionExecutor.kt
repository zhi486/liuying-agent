package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.diagnostics.BleFailureReason
import com.healthyagent.running.diagnostics.BleOperation
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticFailure
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.SafeDiagnosticSummary
import java.util.concurrent.CancellationException

internal class KeepB5GattDecisionExecutor(
    private val io: KeepB5GattIoPort,
    private val deviceName: String,
    private val signalSink: (RawGattSignal) -> Unit,
    private val diagnosticLogger: DiagnosticLogger,
    private val registrationGate: KeepB5GattRegistrationGate,
) {
    private var initialized = false
    private var notificationState = NotificationState.NONE
    private var standardFallback: KeepB5ChannelKind? = null
    private val attemptedChannels = mutableSetOf<KeepB5ChannelKind>()
    private var batteryPercent: Int? = null
    private var firmwareVersion: String? = null

    fun initialize() {
        if (initialized || !registrationGate.isActive()) return
        initialized = true
        emitDeviceInfo()
    }

    fun execute(decision: GattCallbackDecision) {
        if (!registrationGate.isActive()) return
        when (decision) {
            is GattCallbackDecision.Emit -> emit(decision.signal)
            GattCallbackDecision.DiscoverServices -> discoverServices()
            is GattCallbackDecision.EnableNotifications -> enable(decision)
            is GattCallbackDecision.NotificationDescriptorResult -> descriptorResult(decision)
            GattCallbackDecision.ReadDeviceMetadata -> readDeviceMetadata()
            GattCallbackDecision.ReadFirmware -> readFirmware()
            is GattCallbackDecision.UpdateBattery -> updateBattery(decision)
            is GattCallbackDecision.UpdateFirmware -> updateFirmware(decision.version)
        }
    }

    private fun discoverServices() {
        when (performIo(BleOperation.DISCOVER_SERVICES, io::discoverServices)) {
            GattIoSubmission.ACCEPTED, null -> Unit
            GattIoSubmission.REJECTED, GattIoSubmission.UNAVAILABLE -> terminalError()
        }
    }

    private fun enable(decision: GattCallbackDecision.EnableNotifications) {
        when (decision.channel) {
            KeepB5ChannelKind.STANDARD_HR -> {
                if (notificationState != NotificationState.NONE) return
                standardFallback = decision.fallbackOnFailure
                startEnable(KeepB5ChannelKind.STANDARD_HR)
            }
            KeepB5ChannelKind.PRIVATE_B3 -> {
                if (notificationState != NotificationState.NONE) return
                startEnable(KeepB5ChannelKind.PRIVATE_B3)
            }
        }
    }

    private fun startEnable(channel: KeepB5ChannelKind) {
        if (!attemptedChannels.add(channel) || !registrationGate.isActive()) return
        notificationState = when (channel) {
            KeepB5ChannelKind.STANDARD_HR -> NotificationState.STANDARD_PENDING
            KeepB5ChannelKind.PRIVATE_B3 -> NotificationState.PRIVATE_PENDING
        }
        emit(RawGattSignal.ServicesDiscovered(channel))
        if (!registrationGate.isActive()) return
        when (performIo(BleOperation.ENABLE_NOTIFICATIONS) { io.enableNotifications(channel) }) {
            GattIoSubmission.ACCEPTED, null -> Unit
            GattIoSubmission.REJECTED, GattIoSubmission.UNAVAILABLE -> notificationFailure(channel)
        }
    }

    private fun descriptorResult(decision: GattCallbackDecision.NotificationDescriptorResult) {
        when (decision.channel) {
            KeepB5ChannelKind.STANDARD_HR -> {
                if (notificationState != NotificationState.STANDARD_PENDING) return
                if (!decision.accepted) {
                    notificationFailure(KeepB5ChannelKind.STANDARD_HR)
                    return
                }
                notificationState = NotificationState.STANDARD_READY
                emit(RawGattSignal.NotificationsEnabled(KeepB5ChannelKind.STANDARD_HR))
                if (registrationGate.isActive()) readDeviceMetadata()
            }
            KeepB5ChannelKind.PRIVATE_B3 -> {
                if (notificationState != NotificationState.PRIVATE_PENDING) return
                if (!decision.accepted) {
                    notificationFailure(KeepB5ChannelKind.PRIVATE_B3)
                    return
                }
                notificationState = NotificationState.PRIVATE_READY
                emit(RawGattSignal.NotificationsEnabled(KeepB5ChannelKind.PRIVATE_B3))
            }
        }
    }

    private fun notificationFailure(channel: KeepB5ChannelKind) {
        if (!registrationGate.isActive()) return
        if (channel == KeepB5ChannelKind.STANDARD_HR &&
            notificationState == NotificationState.STANDARD_PENDING &&
            standardFallback == KeepB5ChannelKind.PRIVATE_B3
        ) {
            startEnable(KeepB5ChannelKind.PRIVATE_B3)
        } else if (
            (channel == KeepB5ChannelKind.STANDARD_HR && notificationState == NotificationState.STANDARD_PENDING) ||
            (channel == KeepB5ChannelKind.PRIVATE_B3 && notificationState == NotificationState.PRIVATE_PENDING)
        ) {
            terminalError()
        }
    }

    private fun terminalError() {
        if (notificationState == NotificationState.TERMINAL_ERROR || !registrationGate.isActive()) return
        notificationState = NotificationState.TERMINAL_ERROR
        emit(RawGattSignal.Error(BleFailureReason.GATT_ERROR))
    }

    private fun readDeviceMetadata() {
        when (performIo(BleOperation.READ_BATTERY, io::readBattery)) {
            GattIoSubmission.ACCEPTED, null -> Unit
            GattIoSubmission.REJECTED, GattIoSubmission.UNAVAILABLE -> readFirmware()
        }
    }

    private fun readFirmware() {
        performIo(BleOperation.READ_FIRMWARE, io::readFirmware)
    }

    private fun updateBattery(decision: GattCallbackDecision.UpdateBattery) {
        batteryPercent = decision.percent
        emitDeviceInfo()
        decision.percent?.let {
            emit(RawGattSignal.BatteryLevel(it, decision.receivedAtMillis, decision.wallClockMillis))
        }
    }

    private fun updateFirmware(version: String?) {
        firmwareVersion = version
        emitDeviceInfo()
    }

    private fun emitDeviceInfo() {
        emit(RawGattSignal.DeviceInfoUpdated(DeviceInfo(deviceName, batteryPercent, firmwareVersion)))
    }

    private fun emit(signal: RawGattSignal) {
        if (registrationGate.isActive()) signalSink(signal)
    }

    private fun performIo(
        operation: BleOperation,
        call: () -> GattIoSubmission,
    ): GattIoSubmission? {
        if (!registrationGate.tryAcquireExecution()) return null
        val result = try {
            call()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (registrationGate.isActive()) recordFailure(operation, error)
            return GattIoSubmission.REJECTED.takeIf { registrationGate.isActive() }
        }
        if (!registrationGate.isActive()) return null
        if (result == GattIoSubmission.REJECTED) {
            recordFailure(operation, IllegalStateException("GATT submission rejected"))
        }
        return result
    }

    private fun recordFailure(operation: BleOperation, error: Exception) {
        diagnosticLogger.record(
            DiagnosticEvent.BleOperationFailed(
                operation = operation,
                reason = BleFailureReason.GATT_ERROR,
                failure = DiagnosticFailure.from(error, SafeDiagnosticSummary.BLE_OPERATION_FAILED),
            ),
        )
    }

    private enum class NotificationState {
        NONE,
        STANDARD_PENDING,
        STANDARD_READY,
        PRIVATE_PENDING,
        PRIVATE_READY,
        TERMINAL_ERROR,
    }
}
