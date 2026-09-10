package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample

internal class KeepB5GattEventMapper {
    fun map(
        guard: KeepB5GattGuard,
        signal: RawGattSignal,
    ): List<MappedGattOutput> = when (signal) {
        RawGattSignal.Connected -> control(BleTransportEvent.GattConnected(guard))
        is RawGattSignal.ServicesDiscovered -> control(BleTransportEvent.ServicesDiscovered(guard, signal.channel))
        is RawGattSignal.NotificationsEnabled -> control(BleTransportEvent.NotificationsEnabled(guard, signal.channel))
        is RawGattSignal.DeviceInfoUpdated -> control(BleTransportEvent.DeviceInfoUpdated(guard, signal.deviceInfo))
        is RawGattSignal.Disconnected -> control(BleTransportEvent.GattDisconnected(guard, signal.reason))
        is RawGattSignal.Error -> control(BleTransportEvent.GattError(guard, signal.reason))
        is RawGattSignal.PrivateNotification -> mapPrivate(guard, signal)
        is RawGattSignal.StandardHeartRateNotification -> parseBleHeartRate(signal.frame)?.let { heartRate ->
            listOf(sample(guard, SampleType.HEART_RATE, heartRate, signal.receivedAtMillis, signal.wallClockMillis, STANDARD_SOURCE))
        } ?: emptyList()
        is RawGattSignal.BatteryLevel -> listOf(
            sample(
                guard,
                SampleType.BATTERY,
                signal.percent,
                signal.receivedAtMillis,
                signal.wallClockMillis,
                STANDARD_SOURCE,
            ),
        )
    }

    private fun mapPrivate(
        guard: KeepB5GattGuard,
        signal: RawGattSignal.PrivateNotification,
    ): List<MappedGattOutput> {
        val parsed = KeepB5B3Protocol.parseWorkoutData(signal.frame) ?: return emptyList()
        return buildList {
            add(MappedGattOutput.Activity(PrivateWorkoutActivity(guard, signal.receivedAtMillis)))
            parsed.heartRate?.let { heartRate ->
                add(
                    sample(
                        guard,
                        SampleType.HEART_RATE,
                        heartRate,
                        signal.receivedAtMillis,
                        signal.wallClockMillis,
                        PRIVATE_SOURCE,
                    ),
                )
            }
        }
    }

    private fun sample(
        guard: KeepB5GattGuard,
        type: SampleType,
        value: Int,
        receivedAtMillis: Long,
        wallClockMillis: Long,
        source: String,
    ) = MappedGattOutput.Sample(
        TokenizedSensorSample(
            guard = guard,
            sample = SensorSample(type, value.toDouble(), wallClockMillis, source),
            receivedAtMillis = receivedAtMillis,
        ),
    )

    private fun control(event: BleTransportEvent) = listOf(MappedGattOutput.Control(event))

    private companion object {
        const val PRIVATE_SOURCE = "keep_b5_private"
        const val STANDARD_SOURCE = "keep_b5_standard"
    }
}
