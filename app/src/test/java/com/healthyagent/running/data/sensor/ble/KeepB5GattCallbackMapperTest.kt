package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.diagnostics.BleFailureReason
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepB5GattCallbackMapperTest {
    private val mapper = KeepB5GattCallbackMapper()

    @Test
    fun `successful connection emits connected before discovery and failures are normalized`() {
        assertEquals(
            listOf(GattCallbackDecision.Emit(RawGattSignal.Connected), GattCallbackDecision.DiscoverServices),
            mapper.mapCallback(RawGattCallbackEvent.ConnectionStateChanged(status = 0, newState = 2)),
        )
        assertEquals(
            listOf(GattCallbackDecision.Emit(RawGattSignal.Disconnected(null))),
            mapper.mapCallback(RawGattCallbackEvent.ConnectionStateChanged(status = 0, newState = 0)),
        )
        assertEquals(
            listOf(GattCallbackDecision.Emit(RawGattSignal.Disconnected(BleFailureReason.GATT_ERROR))),
            mapper.mapCallback(RawGattCallbackEvent.ConnectionStateChanged(status = 133, newState = 0)),
        )
        assertEquals(
            listOf(GattCallbackDecision.Emit(RawGattSignal.Error(BleFailureReason.GATT_ERROR))),
            mapper.mapCallback(RawGattCallbackEvent.ConnectionStateChanged(status = 133, newState = 2)),
        )
    }

    @Test
    fun `service discovery is standard first and requires complete properties in the correct service`() {
        assertEquals(
            listOf(
                GattCallbackDecision.EnableNotifications(
                    KeepB5ChannelKind.STANDARD_HR,
                    fallbackOnFailure = KeepB5ChannelKind.PRIVATE_B3,
                ),
            ),
            mapper.mapCallback(services(standardProperties = NOTIFY, commandProperties = WRITE_NO_RESPONSE, privateProperties = INDICATE)),
        )
        assertEquals(
            listOf(GattCallbackDecision.EnableNotifications(KeepB5ChannelKind.PRIVATE_B3)),
            mapper.mapCallback(services(standardProperties = 0, commandProperties = WRITE_NO_RESPONSE, privateProperties = NOTIFY)),
        )
        assertEquals(
            listOf(GattCallbackDecision.EnableNotifications(KeepB5ChannelKind.STANDARD_HR)),
            mapper.mapCallback(services(standardProperties = INDICATE, commandProperties = 0, privateProperties = NOTIFY)),
        )
        assertEquals(
            listOf(GattCallbackDecision.Emit(RawGattSignal.Error(BleFailureReason.GATT_ERROR))),
            mapper.mapCallback(services(standardProperties = 0, commandProperties = WRITE_NO_RESPONSE, privateProperties = 0)),
        )

        val wrongService = GattServiceSnapshot(
            mapOf(
                BATTERY_SERVICE to listOf(GattCharacteristicSnapshot(HR_MEASUREMENT, NOTIFY)),
                PRIVATE_SERVICE to listOf(
                    GattCharacteristicSnapshot(PRIVATE_COMMAND, WRITE_NO_RESPONSE),
                    GattCharacteristicSnapshot(PRIVATE_NOTIFY, NOTIFY),
                ),
            ),
        )
        assertEquals(
            listOf(GattCallbackDecision.EnableNotifications(KeepB5ChannelKind.PRIVATE_B3)),
            mapper.mapCallback(RawGattCallbackEvent.ServicesDiscovered(0, wrongService)),
        )
        assertEquals(
            listOf(GattCallbackDecision.Emit(RawGattSignal.Error(BleFailureReason.GATT_ERROR))),
            mapper.mapCallback(RawGattCallbackEvent.ServicesDiscovered(7, wrongService)),
        )
    }

    @Test
    fun `descriptor writes only map matching CCCD owner and never append follow-up decisions`() {
        listOf(
            HR_SERVICE to (HR_MEASUREMENT to KeepB5ChannelKind.STANDARD_HR),
            PRIVATE_SERVICE to (PRIVATE_NOTIFY to KeepB5ChannelKind.PRIVATE_B3),
        ).forEach { (service, ownerAndChannel) ->
            val (owner, channel) = ownerAndChannel
            assertEquals(
                listOf(GattCallbackDecision.NotificationDescriptorResult(channel, accepted = true)),
                mapper.mapCallback(RawGattCallbackEvent.DescriptorWrite(0, service, CCCD, owner)),
            )
            assertEquals(
                listOf(GattCallbackDecision.NotificationDescriptorResult(channel, accepted = false)),
                mapper.mapCallback(RawGattCallbackEvent.DescriptorWrite(5, service, CCCD, owner)),
            )
        }
        assertTrue(mapper.mapCallback(RawGattCallbackEvent.DescriptorWrite(0, HR_SERVICE, UUID.randomUUID(), HR_MEASUREMENT)).isEmpty())
        assertTrue(mapper.mapCallback(RawGattCallbackEvent.DescriptorWrite(0, BATTERY_SERVICE, CCCD, HR_MEASUREMENT)).isEmpty())
    }

    @Test
    fun `notifications use exact service copy payload and suppress only same-session duplicates for 250ms`() {
        val privatePayload = byteArrayOf(1, 2, 3)
        val first = mapper.mapCallback(changed(PRIVATE_SERVICE, PRIVATE_NOTIFY, privatePayload, 1_000L, 10_000L))
        privatePayload[0] = 99
        val privateSignal = (first.single() as GattCallbackDecision.Emit).signal as RawGattSignal.PrivateNotification
        assertArrayEquals(byteArrayOf(1, 2, 3), privateSignal.frame)
        assertEquals(1_000L, privateSignal.receivedAtMillis)
        assertEquals(10_000L, privateSignal.wallClockMillis)
        assertTrue(mapper.mapCallback(changed(PRIVATE_SERVICE, PRIVATE_NOTIFY, byteArrayOf(1, 2, 3), 1_250L, 11_000L)).isEmpty())
        assertEquals(1, mapper.mapCallback(changed(PRIVATE_SERVICE, PRIVATE_NOTIFY, byteArrayOf(1, 2, 3), 1_251L, 12_000L)).size)

        val standard = mapper.mapCallback(changed(HR_SERVICE, HR_MEASUREMENT, byteArrayOf(0, 79), 2_000L, 20_000L))
        assertTrue(((standard.single() as GattCallbackDecision.Emit).signal) is RawGattSignal.StandardHeartRateNotification)
        assertTrue(mapper.mapCallback(changed(BATTERY_SERVICE, HR_MEASUREMENT, byteArrayOf(0, 79), 2_001L, 20_001L)).isEmpty())

        val independent = KeepB5GattCallbackMapper()
        assertEquals(1, independent.mapCallback(changed(PRIVATE_SERVICE, PRIVATE_NOTIFY, byteArrayOf(1, 2, 3), 1_100L, 10_100L)).size)

        val interleaved = KeepB5GattCallbackMapper()
        assertEquals(1, interleaved.mapCallback(changed(PRIVATE_SERVICE, PRIVATE_NOTIFY, byteArrayOf(7), 3_000L, 30_000L)).size)
        assertEquals(1, interleaved.mapCallback(changed(HR_SERVICE, HR_MEASUREMENT, byteArrayOf(0, 80), 3_100L, 30_100L)).size)
        assertTrue(interleaved.mapCallback(changed(PRIVATE_SERVICE, PRIVATE_NOTIFY, byteArrayOf(7), 3_200L, 30_200L)).isEmpty())
    }

    @Test
    fun `battery reads dedupe independently and always continue firmware while firmware parses nullable text`() {
        assertEquals(
            listOf(GattCallbackDecision.UpdateBattery(80, 100L, 200L), GattCallbackDecision.ReadFirmware),
            mapper.mapCallback(read(0, BATTERY_SERVICE, BATTERY_LEVEL, byteArrayOf(80), 100L, 200L)),
        )
        assertTrue(mapper.mapCallback(read(0, BATTERY_SERVICE, BATTERY_LEVEL, byteArrayOf(80), 200L, 300L)).isEmpty())
        assertEquals(
            listOf(GattCallbackDecision.UpdateBattery(null, 351L, 451L), GattCallbackDecision.ReadFirmware),
            mapper.mapCallback(read(0, BATTERY_SERVICE, BATTERY_LEVEL, byteArrayOf(101), 351L, 451L)),
        )
        assertEquals(
            listOf(GattCallbackDecision.ReadFirmware),
            mapper.mapCallback(read(6, BATTERY_SERVICE, BATTERY_LEVEL, byteArrayOf(40), 700L, 800L)),
        )
        assertEquals(
            listOf(GattCallbackDecision.UpdateFirmware("1.2.3")),
            mapper.mapCallback(read(0, DEVICE_INFO_SERVICE, FIRMWARE_REVISION, "1.2.3\u0000".encodeToByteArray(), 900L, 1_000L)),
        )
        assertEquals(
            listOf(GattCallbackDecision.UpdateFirmware(null)),
            mapper.mapCallback(read(0, DEVICE_INFO_SERVICE, FIRMWARE_REVISION, byteArrayOf(0), 1_200L, 1_300L)),
        )
        assertTrue(mapper.mapCallback(read(0, BATTERY_SERVICE, FIRMWARE_REVISION, "bad-service".encodeToByteArray(), 2_000L, 3_000L)).isEmpty())
    }

    private fun services(
        standardProperties: Int,
        commandProperties: Int,
        privateProperties: Int,
    ) = RawGattCallbackEvent.ServicesDiscovered(
        status = 0,
        services = GattServiceSnapshot(
            mapOf(
                HR_SERVICE to listOf(GattCharacteristicSnapshot(HR_MEASUREMENT, standardProperties)),
                PRIVATE_SERVICE to listOf(
                    GattCharacteristicSnapshot(PRIVATE_COMMAND, commandProperties),
                    GattCharacteristicSnapshot(PRIVATE_NOTIFY, privateProperties),
                ),
            ),
        ),
    )

    private fun changed(service: UUID, characteristic: UUID, value: ByteArray, received: Long, wall: Long) =
        RawGattCallbackEvent.CharacteristicChanged(service, characteristic, value, received, wall)

    private fun read(status: Int, service: UUID, characteristic: UUID, value: ByteArray, received: Long, wall: Long) =
        RawGattCallbackEvent.CharacteristicRead(status, service, characteristic, value, received, wall)

    private companion object {
        const val WRITE_NO_RESPONSE = 0x04
        const val NOTIFY = 0x10
        const val INDICATE = 0x20
        val HR_SERVICE: UUID = uuid16("180d")
        val HR_MEASUREMENT: UUID = uuid16("2a37")
        val BATTERY_SERVICE: UUID = uuid16("180f")
        val BATTERY_LEVEL: UUID = uuid16("2a19")
        val DEVICE_INFO_SERVICE: UUID = uuid16("180a")
        val FIRMWARE_REVISION: UUID = uuid16("2a26")
        val CCCD: UUID = uuid16("2902")
        val PRIVATE_SERVICE: UUID = uuid16("190a")
        val PRIVATE_COMMAND: UUID = uuid16("0001")
        val PRIVATE_NOTIFY: UUID = uuid16("0002")
        fun uuid16(value: String): UUID = UUID.fromString("0000$value-0000-1000-8000-00805f9b34fb")
    }
}
