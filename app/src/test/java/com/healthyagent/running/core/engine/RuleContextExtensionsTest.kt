package com.healthyagent.running.core.engine

import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RuleContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RuleContext 扩展函数单元测试
 */
class RuleContextExtensionsTest {

    private fun makeFrame(
        elapsedSec: Long,
        hr: Int,
        cadence: Int? = null,
        pace: Double? = null,
        hrZone: HrZone = HrZone.Z2,
        stride: Float? = null,
        hrPaceRatio: Double? = null,
        timestamp: Long = elapsedSec * 1000,
    ): MetricsFrame = MetricsFrame(
        timestampMillis = timestamp,
        elapsedSeconds = elapsedSec,
        heartRate = hr,
        heartRateSmoothed = hr,
        hrZone = hrZone,
        cadence = cadence,
        strideLengthCm = stride,
        currentPaceSecPerKm = pace,
        distanceMeters = elapsedSec * 2.5,
        splitDistanceMeters = elapsedSec * 2.5,
        totalSteps = elapsedSec * 3,
        isDataFresh = true,
        hrPaceRatio = hrPaceRatio,
    )

    @Test
    fun `consecutiveInZone — 连续 N 秒在指定区间返回 true`() {
        val frames = (1..10).map { makeFrame(it.toLong(), 162, hrZone = HrZone.Z5) }
        val ctx = RuleContext(recentFrames = frames)
        assertTrue(ctx.consecutiveInZone(HrZone.Z5, 10))
    }

    @Test
    fun `consecutiveCondition — 不连续时返回实际连续数`() {
        val frames = listOf(
            makeFrame(1, 150, cadence = 170),
            makeFrame(2, 155, cadence = 210), // 高于 200
            makeFrame(3, 160, cadence = 205), // 高于 200
            makeFrame(4, 158, cadence = 170), // 中断
        )
        val ctx = RuleContext(recentFrames = frames)
        val count = ctx.consecutiveCondition(10) { (it.cadence ?: 0) > 200 }
        assertEquals(0, count) // 最后帧是 170，不满足，所以连续计数为 0
    }

    @Test
    fun `consecutiveCondition — 连续满足计数正确`() {
        val frames = (1..5).map { makeFrame(it.toLong(), 150, cadence = 205) }
        val ctx = RuleContext(recentFrames = frames)
        val count = ctx.consecutiveCondition(10) { (it.cadence ?: 0) > 200 }
        assertEquals(5, count)
    }

    @Test
    fun `hrTrend — 上升趋势为正`() {
        val frames = listOf(
            makeFrame(1, 140, timestamp = 0),
            makeFrame(30, 155, timestamp = 30_000),
        )
        val ctx = RuleContext(recentFrames = frames)
        val trend = ctx.hrTrend(30)
        assertNotNull(trend)
        assertEquals(0.5, trend!!, 0.01) // (155-140) / 30 = 0.5 bpm/s
    }

    @Test
    fun `hrDelta — 30s 内上升 15bpm`() {
        val frames = listOf(
            makeFrame(1, 140, timestamp = 0),
            makeFrame(30, 155, timestamp = 30_000),
        )
        val ctx = RuleContext(recentFrames = frames)
        val delta = ctx.hrDelta(30)
        assertNotNull(delta)
        assertEquals(15.0, delta!!, 0.01)
    }

    @Test
    fun `cadenceChangePercent — 下降 10%`() {
        val frames = listOf(
            makeFrame(1, 140, cadence = 175, timestamp = 0),
            makeFrame(30, 145, cadence = 157, timestamp = 30_000),
        )
        val ctx = RuleContext(recentFrames = frames)
        val change = ctx.cadenceChangePercent(30)
        assertNotNull(change)
        assertEquals(-0.103, change!!, 0.01) // (157-175)/175 ≈ -10.3%
    }

    @Test
    fun `paceDeviationPercent — 慢 15%`() {
        val frames = listOf(makeFrame(1, 140, pace = 414.0))
        val ctx = RuleContext(recentFrames = frames)
        val dev = ctx.paceDeviationPercent(360.0)
        assertNotNull(dev)
        assertEquals(0.15, dev!!, 0.01)
    }

    @Test
    fun `paceDeviationPercent — 快 10%`() {
        val frames = listOf(makeFrame(1, 140, pace = 324.0))
        val ctx = RuleContext(recentFrames = frames)
        val dev = ctx.paceDeviationPercent(360.0)
        assertNotNull(dev)
        assertEquals(-0.10, dev!!, 0.01)
    }

    @Test
    fun `hrTrend — 数据不足返回 null`() {
        val ctx = RuleContext(recentFrames = listOf(makeFrame(1, 140)))
        assertNull(ctx.hrTrend(30))
    }

    @Test
    fun `z2LowerBound — 正确计算 Z2 下限`() {
        val ctx = RuleContext()
        val lower = ctx.z2LowerBound(180)
        assertEquals(108, lower)
    }
}
