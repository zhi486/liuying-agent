package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample
import com.healthyagent.running.diagnostics.BleFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepB5GattEventMapperTest {
    private val mapper = KeepB5GattEventMapper()
    private val guard = KeepB5GattGuard(epoch = 3L, connectionAttemptToken = 5L, gattToken = 8L)

    @Test
    fun `private valid activity without heart rate has activity only while 79 has matching dual-clock sample`() {
        assertEquals(
            listOf(MappedGattOutput.Activity(PrivateWorkoutActivity(guard, RECEIVED))),
            mapper.map(
                guard,
                RawGattSignal.PrivateNotification(hex("e7 2a 02 00 00 0e 08 84 e6"), RECEIVED, WALL),
            ),
        )
        assertEquals(
            listOf(
                MappedGattOutput.Activity(PrivateWorkoutActivity(guard, RECEIVED)),
                MappedGattOutput.Sample(
                    TokenizedSensorSample(
                        guard,
                        SensorSample(SampleType.HEART_RATE, 79.0, WALL, "keep_b5_private"),
                        RECEIVED,
                    ),
                ),
            ),
            mapper.map(
                guard,
                RawGattSignal.PrivateNotification(hex("ea 2a 02 00 00 0e 08 08 4f da d0"), RECEIVED, WALL),
            ),
        )
    }

    @Test
    fun `standard heart rate and battery preserve monotonic received and wall clock timestamp with exact source`() {
        assertEquals(
            listOf(
                MappedGattOutput.Sample(
                    TokenizedSensorSample(
                        guard,
                        SensorSample(SampleType.HEART_RATE, 79.0, WALL, "keep_b5_standard"),
                        RECEIVED,
                    ),
                ),
            ),
            mapper.map(
                guard,
                RawGattSignal.StandardHeartRateNotification(byteArrayOf(0, 79), RECEIVED, WALL),
            ),
        )
        assertEquals(
            listOf(
                MappedGattOutput.Sample(
                    TokenizedSensorSample(
                        guard,
                        SensorSample(SampleType.BATTERY, 88.0, WALL, "keep_b5_standard"),
                        RECEIVED,
                    ),
                ),
            ),
            mapper.map(guard, RawGattSignal.BatteryLevel(88, RECEIVED, WALL)),
        )
    }

    @Test
    fun `wall clock rollback never replaces monotonic received time`() {
        val newerReceived = mapper.map(
            guard,
            RawGattSignal.StandardHeartRateNotification(byteArrayOf(0, 80), 12_001L, WALL - 20_000L),
        ).single() as MappedGattOutput.Sample
        assertEquals(12_001L, newerReceived.value.receivedAtMillis)
        assertEquals(WALL - 20_000L, newerReceived.value.sample.timestampMillis)
    }

    @Test
    fun `unknown private responses and malformed standard heart rate map to nothing`() {
        assertTrue(
            mapper.map(
                guard,
                RawGattSignal.PrivateNotification(hex("63 00 00 00 00 0e 10 a6 5a"), RECEIVED, WALL),
            ).isEmpty(),
        )
        assertTrue(
            mapper.map(
                guard,
                RawGattSignal.StandardHeartRateNotification(byteArrayOf(0), RECEIVED, WALL),
            ).isEmpty(),
        )
    }

    @Test
    fun `all control signals map exactly once with original guard`() {
        val info = DeviceInfo("Keep B5", 91, "1.0")
        val cases = listOf(
            RawGattSignal.Connected to BleTransportEvent.GattConnected(guard),
            RawGattSignal.ServicesDiscovered(KeepB5ChannelKind.STANDARD_HR) to
                BleTransportEvent.ServicesDiscovered(guard, KeepB5ChannelKind.STANDARD_HR),
            RawGattSignal.NotificationsEnabled(KeepB5ChannelKind.PRIVATE_B3) to
                BleTransportEvent.NotificationsEnabled(guard, KeepB5ChannelKind.PRIVATE_B3),
            RawGattSignal.DeviceInfoUpdated(info) to BleTransportEvent.DeviceInfoUpdated(guard, info),
            RawGattSignal.Disconnected(null) to BleTransportEvent.GattDisconnected(guard, null),
            RawGattSignal.Error(BleFailureReason.GATT_ERROR) to
                BleTransportEvent.GattError(guard, BleFailureReason.GATT_ERROR),
        )
        cases.forEach { (signal, expected) ->
            assertEquals(listOf(MappedGattOutput.Control(expected)), mapper.map(guard, signal))
        }
    }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val RECEIVED = 12_345L
        const val WALL = 1_700_000_000_000L
    }
}
