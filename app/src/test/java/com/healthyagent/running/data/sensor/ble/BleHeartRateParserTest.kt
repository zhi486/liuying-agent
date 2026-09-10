package com.healthyagent.running.data.sensor.ble

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleHeartRateParserTest {
    @Test
    fun `解析 UINT8 心率`() {
        assertEquals(145, parseBleHeartRate(byteArrayOf(0x00, 145.toByte())))
    }

    @Test
    fun `解析 UINT16 心率`() {
        assertEquals(260, parseBleHeartRate(byteArrayOf(0x01, 0x04, 0x01)))
    }

    @Test
    fun `短包和越界心率被拒绝`() {
        assertNull(parseBleHeartRate(byteArrayOf()))
        assertNull(parseBleHeartRate(byteArrayOf(0x00)))
        assertNull(parseBleHeartRate(byteArrayOf(0x01, 0x04)))
        assertNull(parseBleHeartRate(byteArrayOf(0x01, 0x90.toByte(), 0x01)))
    }

    @Test
    fun `电量只接受 0 到 100`() {
        assertEquals(0, parseBleBatteryLevel(byteArrayOf(0x00)))
        assertEquals(100, parseBleBatteryLevel(byteArrayOf(100)))
        assertNull(parseBleBatteryLevel(byteArrayOf()))
        assertNull(parseBleBatteryLevel(byteArrayOf(101)))
    }

    @Test
    fun `固件文本去除空字节和空白`() {
        assertEquals("1.2.3", parseBleText(" 1.2.3\u0000".encodeToByteArray()))
        assertNull(parseBleText(byteArrayOf(0x00)))
    }

    @Test
    fun `短窗口内同特征同载荷只投递一次`() {
        val uuid = UUID.randomUUID()
        val deduplicator = BleNotificationDeduplicator(duplicateWindowMillis = 250L)

        assertTrue(deduplicator.shouldDeliver(uuid, byteArrayOf(1, 2), 1_000L))
        assertFalse(deduplicator.shouldDeliver(uuid, byteArrayOf(1, 2), 1_100L))
        assertTrue(deduplicator.shouldDeliver(uuid, byteArrayOf(1, 2), 1_251L))
        assertTrue(deduplicator.shouldDeliver(uuid, byteArrayOf(1, 3), 1_252L))
    }
}
