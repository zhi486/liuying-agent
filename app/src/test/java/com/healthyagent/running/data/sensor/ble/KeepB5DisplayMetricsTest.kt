package com.healthyagent.running.data.sensor.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KeepB5DisplayMetricsTest {
    @Test
    fun `距离取整数米且配速按手机显示取整秒`() {
        assertEquals(KeepB5DisplayMetrics(1_239, 360), KeepB5DisplayMetrics.fromMeasurements(1_239.99, 359.5))
        assertEquals(KeepB5DisplayMetrics(153, 816), KeepB5DisplayMetrics.fromMeasurements(153.1, 816.49))
        assertEquals(KeepB5DisplayMetrics(186, 0), KeepB5DisplayMetrics.fromMeasurements(186.0, null))
    }

    @Test
    fun `负值非有限值和越界值不形成有效显示指标`() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE).forEach {
            assertEquals(KeepB5DisplayMetrics(), KeepB5DisplayMetrics.fromMeasurements(it, it))
        }
        assertEquals(KeepB5DisplayMetrics(), KeepB5DisplayMetrics.fromMeasurements(0.0, 0.0))
    }

    @Test
    fun `最大整数不溢出且编码对象拒绝负数`() {
        assertEquals(
            KeepB5DisplayMetrics(Int.MAX_VALUE, Int.MAX_VALUE),
            KeepB5DisplayMetrics.fromMeasurements(Int.MAX_VALUE.toDouble(), Int.MAX_VALUE.toDouble()),
        )
        assertThrows(IllegalArgumentException::class.java) { KeepB5DisplayMetrics(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { KeepB5DisplayMetrics(0, -1) }
    }
}
