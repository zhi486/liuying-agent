package com.healthyagent.running.data.sensor.ble

import java.util.UUID

/** 解析 BLE Heart Rate Measurement 0x2A37 的 UINT8/UINT16 心率值。 */
internal fun parseBleHeartRate(data: ByteArray): Int? {
    if (data.size < 2) return null
    val flags = data[0].toInt() and 0xFF
    val isUint16 = flags and 0x01 != 0
    val heartRate = if (isUint16) {
        if (data.size < 3) return null
        (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
    } else {
        data[1].toInt() and 0xFF
    }
    return heartRate.takeIf { it in 1..300 }
}

/** 解析 BLE Battery Level 0x2A19。标准有效范围为 0..100。 */
internal fun parseBleBatteryLevel(data: ByteArray): Int? {
    if (data.isEmpty()) return null
    return (data[0].toInt() and 0xFF).takeIf { it in 0..100 }
}

/** 解析 BLE Device Information 中的 UTF-8 文本字段。 */
internal fun parseBleText(data: ByteArray): String? {
    return data.toString(Charsets.UTF_8)
        .trimEnd('\u0000')
        .trim()
        .takeIf { it.isNotEmpty() }
}

/**
 * 屏蔽某些 Android 蓝牙栈在 API 33 新旧回调间重复投递的同一条通知。
 * 窗口足够短，不会吞掉手环正常的 1 Hz 心率上报。
 */
internal class BleNotificationDeduplicator(
    private val duplicateWindowMillis: Long = 250L,
) {
    private var lastCharacteristicUuid: UUID? = null
    private var lastPayload: ByteArray? = null
    private var lastTimestampMillis: Long = Long.MIN_VALUE

    init {
        require(duplicateWindowMillis >= 0L)
    }

    @Synchronized
    fun shouldDeliver(
        characteristicUuid: UUID,
        payload: ByteArray,
        timestampMillis: Long,
    ): Boolean {
        val previousTimestamp = lastTimestampMillis
        val isDuplicate = lastCharacteristicUuid == characteristicUuid &&
            lastPayload?.contentEquals(payload) == true &&
            timestampMillis >= previousTimestamp &&
            timestampMillis - previousTimestamp <= duplicateWindowMillis

        if (!isDuplicate) {
            lastCharacteristicUuid = characteristicUuid
            lastPayload = payload.copyOf()
            lastTimestampMillis = timestampMillis
        }
        return !isDuplicate
    }

    @Synchronized
    fun reset() {
        lastCharacteristicUuid = null
        lastPayload = null
        lastTimestampMillis = Long.MIN_VALUE
    }
}
