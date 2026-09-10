package com.healthyagent.running.data.sensor.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleReconnectBackoffTest {
    @Test
    fun `超过五次后仍会以上限延迟持续重试`() {
        val backoff = BleReconnectBackoff(
            initialDelayMillis = 1_000L,
            maxDelayMillis = 30_000L,
        )

        val delays = List(10) { backoff.nextDelayMillis() }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L, 30_000L, 30_000L),
            delays,
        )
        assertEquals(10, backoff.attempt)
    }

    @Test
    fun `数据通道就绪后退避复位`() {
        val backoff = BleReconnectBackoff(1_000L, 30_000L)
        repeat(6) { backoff.nextDelayMillis() }

        backoff.reset()

        assertEquals(1_000L, backoff.nextDelayMillis())
        assertEquals(1, backoff.attempt)
    }
}
