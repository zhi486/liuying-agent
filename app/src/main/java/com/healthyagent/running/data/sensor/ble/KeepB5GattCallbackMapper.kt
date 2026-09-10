package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.diagnostics.BleFailureReason
import java.util.UUID

internal class KeepB5GattCallbackMapper(
    private val duplicateWindowMillis: Long = 250L,
) {
    private val notificationDeliveries = mutableMapOf<DeliveryKey, Long>()
    private val readDeliveries = mutableMapOf<DeliveryKey, Long>()

    init {
        require(duplicateWindowMillis >= 0L)
    }

    fun mapCallback(event: RawGattCallbackEvent): List<GattCallbackDecision> = when (event) {
        is RawGattCallbackEvent.ConnectionStateChanged -> mapConnection(event)
        is RawGattCallbackEvent.ServicesDiscovered -> mapServices(event)
        is RawGattCallbackEvent.DescriptorWrite -> mapDescriptor(event)
        is RawGattCallbackEvent.CharacteristicChanged -> mapNotification(event)
        is RawGattCallbackEvent.CharacteristicRead -> mapRead(event)
    }

    private fun mapConnection(event: RawGattCallbackEvent.ConnectionStateChanged) = when (event.newState) {
        STATE_CONNECTED -> if (event.status == GATT_SUCCESS) {
            listOf(
                GattCallbackDecision.Emit(RawGattSignal.Connected),
                GattCallbackDecision.DiscoverServices,
            )
        } else {
            errorDecision()
        }
        STATE_DISCONNECTED -> listOf(
            GattCallbackDecision.Emit(
                RawGattSignal.Disconnected(
                    reason = if (event.status == GATT_SUCCESS) null else BleFailureReason.GATT_ERROR,
                ),
            ),
        )
        else -> emptyList()
    }

    private fun mapServices(event: RawGattCallbackEvent.ServicesDiscovered): List<GattCallbackDecision> {
        if (event.status != GATT_SUCCESS) return errorDecision()
        val standard = event.services.hasCharacteristic(HR_SERVICE, HR_MEASUREMENT) {
            it and (PROPERTY_NOTIFY or PROPERTY_INDICATE) != 0
        }
        val private = event.services.hasCharacteristic(PRIVATE_SERVICE, PRIVATE_COMMAND) {
            it and PROPERTY_WRITE_NO_RESPONSE != 0
        } && event.services.hasCharacteristic(PRIVATE_SERVICE, PRIVATE_NOTIFY) {
            it and (PROPERTY_NOTIFY or PROPERTY_INDICATE) != 0
        }
        return when {
            standard -> listOf(
                GattCallbackDecision.EnableNotifications(
                    channel = KeepB5ChannelKind.STANDARD_HR,
                    fallbackOnFailure = KeepB5ChannelKind.PRIVATE_B3.takeIf { private },
                ),
            )
            private -> listOf(GattCallbackDecision.EnableNotifications(KeepB5ChannelKind.PRIVATE_B3))
            else -> errorDecision()
        }
    }

    private fun mapDescriptor(event: RawGattCallbackEvent.DescriptorWrite): List<GattCallbackDecision> {
        if (event.descriptorUuid != CLIENT_CHAR_CONFIG) return emptyList()
        val channel = when {
            event.serviceUuid == HR_SERVICE && event.characteristicUuid == HR_MEASUREMENT ->
                KeepB5ChannelKind.STANDARD_HR
            event.serviceUuid == PRIVATE_SERVICE && event.characteristicUuid == PRIVATE_NOTIFY ->
                KeepB5ChannelKind.PRIVATE_B3
            else -> return emptyList()
        }
        return listOf(
            GattCallbackDecision.NotificationDescriptorResult(
                channel = channel,
                accepted = event.status == GATT_SUCCESS,
            ),
        )
    }

    private fun mapNotification(event: RawGattCallbackEvent.CharacteristicChanged): List<GattCallbackDecision> {
        val signal = when {
            event.serviceUuid == PRIVATE_SERVICE && event.characteristicUuid == PRIVATE_NOTIFY ->
                RawGattSignal.PrivateNotification(
                    event.value.copyOf(),
                    event.receivedAtMillis,
                    event.wallClockMillis,
                )
            event.serviceUuid == HR_SERVICE && event.characteristicUuid == HR_MEASUREMENT ->
                RawGattSignal.StandardHeartRateNotification(
                    event.value.copyOf(),
                    event.receivedAtMillis,
                    event.wallClockMillis,
                )
            else -> return emptyList()
        }
        if (isDuplicate(notificationDeliveries, event.serviceUuid, event.characteristicUuid, event.value, event.receivedAtMillis)) {
            return emptyList()
        }
        return listOf(GattCallbackDecision.Emit(signal))
    }

    private fun mapRead(event: RawGattCallbackEvent.CharacteristicRead): List<GattCallbackDecision> {
        val recognized = (event.serviceUuid == BATTERY_SERVICE && event.characteristicUuid == BATTERY_LEVEL) ||
            (event.serviceUuid == DEVICE_INFO_SERVICE && event.characteristicUuid == FIRMWARE_REVISION)
        if (!recognized) return emptyList()
        if (isDuplicate(readDeliveries, event.serviceUuid, event.characteristicUuid, event.value, event.receivedAtMillis)) {
            return emptyList()
        }

        return when {
            event.serviceUuid == BATTERY_SERVICE -> buildList {
                if (event.status == GATT_SUCCESS) {
                    add(
                        GattCallbackDecision.UpdateBattery(
                            parseBleBatteryLevel(event.value),
                            event.receivedAtMillis,
                            event.wallClockMillis,
                        ),
                    )
                }
                add(GattCallbackDecision.ReadFirmware)
            }
            event.status == GATT_SUCCESS -> listOf(GattCallbackDecision.UpdateFirmware(parseBleText(event.value)))
            else -> emptyList()
        }
    }

    private fun isDuplicate(
        deliveries: MutableMap<DeliveryKey, Long>,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        payload: ByteArray,
        receivedAtMillis: Long,
    ): Boolean {
        val iterator = deliveries.iterator()
        while (iterator.hasNext()) {
            val timestamp = iterator.next().value
            if (receivedAtMillis >= timestamp && receivedAtMillis - timestamp > duplicateWindowMillis) {
                iterator.remove()
            }
        }
        val key = DeliveryKey(serviceUuid, characteristicUuid, payload.toList())
        val previous = deliveries[key]
        val duplicate = previous != null &&
            receivedAtMillis >= previous &&
            receivedAtMillis - previous <= duplicateWindowMillis
        if (!duplicate) deliveries[key] = receivedAtMillis
        return duplicate
    }

    private fun GattServiceSnapshot.hasCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        validProperties: (Int) -> Boolean,
    ): Boolean = characteristicsByService[serviceUuid]
        ?.any { it.uuid == characteristicUuid && validProperties(it.properties) } == true

    private fun errorDecision() = listOf(
        GattCallbackDecision.Emit(RawGattSignal.Error(BleFailureReason.GATT_ERROR)),
    )

    private data class DeliveryKey(
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val payload: List<Byte>,
    )

    private companion object {
        const val GATT_SUCCESS = 0
        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTED = 2
        const val PROPERTY_WRITE_NO_RESPONSE = 0x04
        const val PROPERTY_NOTIFY = 0x10
        const val PROPERTY_INDICATE = 0x20
        val HR_SERVICE: UUID = uuid16("180d")
        val HR_MEASUREMENT: UUID = uuid16("2a37")
        val BATTERY_SERVICE: UUID = uuid16("180f")
        val BATTERY_LEVEL: UUID = uuid16("2a19")
        val DEVICE_INFO_SERVICE: UUID = uuid16("180a")
        val FIRMWARE_REVISION: UUID = uuid16("2a26")
        val CLIENT_CHAR_CONFIG: UUID = uuid16("2902")
        val PRIVATE_SERVICE: UUID = uuid16("190a")
        val PRIVATE_COMMAND: UUID = uuid16("0001")
        val PRIVATE_NOTIFY: UUID = uuid16("0002")
        fun uuid16(value: String): UUID = UUID.fromString("0000$value-0000-1000-8000-00805f9b34fb")
    }
}
