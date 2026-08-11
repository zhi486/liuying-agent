package com.healthyagent.running.core.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricsCalculatorTest {

    @Test
    fun `currentPace - 100米用30秒 → 配速5分00秒`() {
        val pace = MetricsCalculator.currentPace(100.0, 30.0)
        assertEquals(300.0, pace!!, 0.01) // 30 * (1000/100) = 300s/km
    }

    @Test
    fun `currentPace - delta 为 0 返回 null`() {
        assertNull(MetricsCalculator.currentPace(0.0, 10.0))
        assertNull(MetricsCalculator.currentPace(100.0, 0.0))
    }

    @Test
    fun `strideLengthFromDelta - 100步跑100米 → 步幅100cm`() {
        val stride = MetricsCalculator.strideLengthFromDelta(100.0, 100)
        assertEquals(100.0f, stride!!)
    }

    @Test
    fun `hrPaceRatio - 心率150配速300 → 比值0_5`() {
        val ratio = MetricsCalculator.hrPaceRatio(150, 300.0)
        assertEquals(0.5, ratio, 0.01)
    }

    @Test
    fun `movingAverage - 多值求平均`() {
        val avg = MetricsCalculator.movingAverage(listOf(140, 142, 144))
        assertEquals(142, avg!!)
    }

    @Test
    fun `movingAverage - 空列表返回 null`() {
        assertNull(MetricsCalculator.movingAverage(emptyList()))
    }

    @Test
    fun `formatPace - 300秒 → 5分00秒`() {
        assertEquals("5分00秒", MetricsCalculator.formatPace(300.0))
    }

    @Test
    fun `formatPace - 369秒 → 6分09秒`() {
        assertEquals("6分09秒", MetricsCalculator.formatPace(369.0))
    }

    @Test
    fun `hrChangeRate - 基本斜率计算`() {
        val frames = listOf(
            0L to 140,
            30_000L to 155  // 30s 内上升 15bpm
        )
        val rate = MetricsCalculator.hrChangeRate(frames, 30)
        assertEquals(0.5, rate!!, 0.01) // 15bpm / 30s
    }
}
