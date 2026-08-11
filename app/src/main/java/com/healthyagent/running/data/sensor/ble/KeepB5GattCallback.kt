package com.healthyagent.running.data.sensor.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.util.UUID

/**
 * Keep B5 GATT 回调 — 服务发现 + 数据通知解析
 *
 * 自动识别标准 BLE 服务（心率/电池），同时记录所有发现的服务/特征值
 * 用于后续私有协议逆向（Protobuf 解析扩展点）。
 */
class KeepB5GattCallback : BluetoothGattCallback() {

    // ── BLE 标准 UUID ──
    companion object {
        val HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val DEVICE_INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val FIRMWARE_REVISION = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHAR_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Keep B5 可能的设备名前缀 */
        val KNOWN_DEVICE_PREFIXES = listOf("Keep B5", "KeepB5", "Keep_B5", "KEEP")
    }

    /** 解析后的传感器样本 */
    private val _samples = MutableSharedFlow<SensorSample>(replay = 0, extraBufferCapacity = 16)
    val samples: SharedFlow<SensorSample> = _samples.asSharedFlow()

    /** 连接状态变化事件 */
    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 4)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    /** 发现的设备固件版本 */
    var firmwareVersion: String? = null
        private set

    /** 发现的设备电池电量 */
    var batteryPercent: Int? = null
        private set

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Timber.i("BLE: GATT 已连接，开始服务发现...")
                    _connectionEvents.tryEmit(ConnectionEvent.Connected)
                    gatt.discoverServices()
                } else {
                    Timber.w("BLE: 连接失败 status=%d", status)
                    _connectionEvents.tryEmit(ConnectionEvent.Error("连接失败 (status=$status)"))
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                Timber.i("BLE: 已断开")
                _connectionEvents.tryEmit(ConnectionEvent.Disconnected)
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Timber.w("BLE: 服务发现失败 status=%d", status)
            _connectionEvents.tryEmit(ConnectionEvent.Error("服务发现失败"))
            return
        }

        Timber.i("BLE: 服务发现完成，遍历所有服务...")
        var hrEnabled = false

        for (service in gatt.services) {
            Timber.d("BLE: 服务 ${service.uuid}")

            for (characteristic in service.characteristics) {
                Timber.d("BLE:   特征 ${characteristic.uuid} (props=${characteristic.properties})")

                when (characteristic.uuid) {
                    HR_MEASUREMENT -> {
                        if (enableNotification(gatt, characteristic)) {
                            hrEnabled = true
                            Timber.i("BLE: ✓ 已订阅心率通知")
                        }
                    }
                    BATTERY_LEVEL -> {
                        gatt.readCharacteristic(characteristic)
                        Timber.d("BLE: 请求电池电量...")
                    }
                    FIRMWARE_REVISION -> {
                        gatt.readCharacteristic(characteristic)
                        Timber.d("BLE: 请求固件版本...")
                    }
                }
            }
        }

        if (hrEnabled) {
            _connectionEvents.tryEmit(ConnectionEvent.Ready)
        } else {
            Timber.w("BLE: 未发现标准心率服务 — 可能需要私有协议解析")
            _connectionEvents.tryEmit(ConnectionEvent.Ready) // 仍然标记就绪，允许后续扩展
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        when (characteristic.uuid) {
            HR_MEASUREMENT -> parseHeartRate(characteristic)
            else -> {
                Timber.d("BLE: 收到未知特征通知 ${characteristic.uuid}")
            }
        }
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) return

        when (characteristic.uuid) {
            BATTERY_LEVEL -> {
                batteryPercent = characteristic.getIntValue(
                    BluetoothGattCharacteristic.FORMAT_UINT8, 0
                )
                batteryPercent?.let { pct ->
                    Timber.d("BLE: 电量 = %d%%", pct)
                    _samples.tryEmit(
                        SensorSample(
                            type = SampleType.BATTERY,
                            value = pct.toDouble(),
                            timestampMillis = System.currentTimeMillis(),
                            source = "keep_b5"
                        )
                    )
                }
            }
            FIRMWARE_REVISION -> {
                firmwareVersion = characteristic.getStringValue(0)
                Timber.d("BLE: 固件 = $firmwareVersion")
            }
        }
    }

    // ── 心率数据解析 ──

    /**
     * 解析心率测量值（BLE 标准格式）
     *
     * Flag 字节结构：
     *   bit 0: HR Value Format (0=UINT8, 1=UINT16)
     *   bit 1-2: Sensor Contact Status
     */
    private fun parseHeartRate(characteristic: BluetoothGattCharacteristic) {
        val data = characteristic.value ?: return
        if (data.isEmpty()) return

        val flags = data[0].toInt() and 0xFF
        val isUint16 = (flags and 0x01) != 0

        val hr = if (isUint16) {
            ((data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8))
        } else {
            data[1].toInt() and 0xFF
        }

        // RR-Interval（可选，蓝牙规范 bit 4 指示存在）
        // 每两个字节一个 RR-Interval 值（1/1024 秒）

        _samples.tryEmit(
            SensorSample(
                type = SampleType.HEART_RATE,
                value = hr.toDouble(),
                timestampMillis = System.currentTimeMillis(),
                source = "keep_b5"
            )
        )
    }

    // ── 辅助方法 ──

    private fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        val success = gatt.setCharacteristicNotification(characteristic, true)
        if (!success) return false

        val descriptor = characteristic.getDescriptor(CLIENT_CHAR_CONFIG)
        if (descriptor == null) {
            Timber.w("BLE: 特征 ${characteristic.uuid} 无 CCCD 描述符")
            return false
        }

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return gatt.writeDescriptor(descriptor)
    }
}

/** BLE 连接事件 */
sealed class ConnectionEvent {
    data object Connected : ConnectionEvent()
    data object Ready : ConnectionEvent()
    data object Disconnected : ConnectionEvent()
    data class Error(val message: String) : ConnectionEvent()
}
