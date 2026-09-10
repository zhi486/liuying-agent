package com.healthyagent.running.data.sensor.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import java.util.UUID

/** Pure callback ingress used by the Android adapter and JVM session tests. */
internal class KeepB5GattCallbackIngress(
    private val offer: (RawGattCallbackEvent) -> Boolean,
    private val monotonicMillis: () -> Long,
    private val wallClockMillis: () -> Long,
) {
    fun connectionStateChanged(status: Int, newState: Int): Boolean =
        offer(RawGattCallbackEvent.ConnectionStateChanged(status, newState))

    fun servicesDiscovered(status: Int, services: GattServiceSnapshot): Boolean =
        offer(
            RawGattCallbackEvent.ServicesDiscovered(
                status = status,
                services = services.defensiveCopy(),
            ),
        )

    fun descriptorWrite(
        status: Int,
        serviceUuid: UUID,
        descriptorUuid: UUID,
        characteristicUuid: UUID,
    ): Boolean = offer(
        RawGattCallbackEvent.DescriptorWrite(
            status = status,
            serviceUuid = serviceUuid,
            descriptorUuid = descriptorUuid,
            characteristicUuid = characteristicUuid,
        ),
    )

    fun characteristicChanged(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
    ): Boolean {
        val receivedAtMillis = monotonicMillis()
        val wallClockMillis = wallClockMillis()
        return offer(
            RawGattCallbackEvent.CharacteristicChanged(
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                value = value.copyOf(),
                receivedAtMillis = receivedAtMillis,
                wallClockMillis = wallClockMillis,
            ),
        )
    }

    fun characteristicRead(
        status: Int,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
    ): Boolean {
        val receivedAtMillis = monotonicMillis()
        val wallClockMillis = wallClockMillis()
        return offer(
            RawGattCallbackEvent.CharacteristicRead(
                status = status,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                value = value.copyOf(),
                receivedAtMillis = receivedAtMillis,
                wallClockMillis = wallClockMillis,
            ),
        )
    }

    private fun GattServiceSnapshot.defensiveCopy() = GattServiceSnapshot(
        characteristicsByService = characteristicsByService.entries.associate { (serviceUuid, characteristics) ->
            serviceUuid to characteristics.map { characteristic -> characteristic.copy() }
        },
    )
}

/** Thin Android callback adapter. Protocol decisions and I/O belong to the session actor. */
class KeepB5GattCallback internal constructor(
    private val ingress: KeepB5GattCallbackIngress,
) : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        ingress.connectionStateChanged(status, newState)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        val services = linkedMapOf<UUID, List<GattCharacteristicSnapshot>>()
        gatt.services.orEmpty().forEach { service ->
            services[service.uuid] = service.characteristics.orEmpty().map { characteristic ->
                GattCharacteristicSnapshot(characteristic.uuid, characteristic.properties)
            }
        }
        ingress.servicesDiscovered(status, GattServiceSnapshot(services))
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        val characteristic = descriptor.characteristic ?: return
        val serviceUuid = characteristic.service?.uuid ?: return
        ingress.descriptorWrite(status, serviceUuid, descriptor.uuid, characteristic.uuid)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        val serviceUuid = characteristic.service?.uuid ?: return
        val value = characteristic.value ?: return
        ingress.characteristicChanged(serviceUuid, characteristic.uuid, value)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        val serviceUuid = characteristic.service?.uuid ?: return
        ingress.characteristicChanged(serviceUuid, characteristic.uuid, value)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        val serviceUuid = characteristic.service?.uuid ?: return
        ingress.characteristicRead(
            status,
            serviceUuid,
            characteristic.uuid,
            characteristic.value ?: byteArrayOf(),
        )
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        val serviceUuid = characteristic.service?.uuid ?: return
        ingress.characteristicRead(status, serviceUuid, characteristic.uuid, value)
    }

}
