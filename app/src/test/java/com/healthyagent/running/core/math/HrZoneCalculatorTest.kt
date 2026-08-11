package com.healthyagent.running.core.math

import com.healthyagent.running.core.model.HrZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HrZoneCalculatorTest {

    private val maxHr = 180

    @Test
    fun `zone - 心率 90 应返回 Z1 热身区`() {
        assertEquals(HrZone.Z1, HrZoneCalculator.zone(90, maxHr))
    }

    @Test
    fun `zone - 心率 108 应返回 Z2 燃脂区`() {
        assertEquals(HrZone.Z2, HrZoneCalculator.zone(108, maxHr))
    }

    @Test
    fun `zone - 心率 108 应返回 Z3 有氧区`() {
        assertEquals(HrZone.Z3, HrZoneCalculator.zone(126, maxHr))
    }

    @Test
    fun `zone - 心率 144 应返回 Z4 阈值区`() {
        assertEquals(HrZone.Z4, HrZoneCalculator.zone(144, maxHr))
    }

    @Test
    fun `zone - 心率 162 应返回 Z5 极限区`() {
        assertEquals(HrZone.Z5, HrZoneCalculator.zone(162, maxHr))
    }

    @Test
    fun `zone - 心率超过最大值也返回 Z5`() {
        assertEquals(HrZone.Z5, HrZoneCalculator.zone(200, maxHr))
    }

    @Test
    fun `zoneRange - Z2 燃脂区范围应为 108 到 126`() {
        val range = HrZoneCalculator.zoneRange(HrZone.Z2, maxHr)
        assertEquals(108, range.first)
        assertEquals(126, range.last)
    }

    @Test
    fun `isInZone - 心率在区间内返回 true`() {
        assertTrue(HrZoneCalculator.isInZone(120, HrZone.Z2, maxHr))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zone - 最大心率为 0 应抛出异常`() {
        HrZoneCalculator.zone(100, 0)
    }
}
