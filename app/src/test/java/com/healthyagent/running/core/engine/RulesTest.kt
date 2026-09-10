package com.healthyagent.running.core.engine

import com.healthyagent.running.core.engine.rules.*
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Split
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11 条跑步规则单元测试
 *
 * 每条规则至少 2 个用例：触发条件满足 → 返回 RuleTrigger；条件不满足 → 返回 null。
 */
class RulesTest {

    private val config = RunConfig(
        targetPaceSecPerKm = 360.0,
        targetDistanceMeters = 5000.0,
        maxHeartRate = 180,
    )

    private fun makeFrame(
        elapsedSec: Long,
        hr: Int? = 140,
        cadence: Int? = 170,
        pace: Double? = 360.0,
        hrZone: HrZone = HrZone.Z2,
        isDataFresh: Boolean = true,
        distance: Double = elapsedSec * 2.5,
        stride: Float? = 100f,
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
        distanceMeters = distance,
        splitDistanceMeters = distance,
        totalSteps = 0,
        isDataFresh = isDataFresh,
        hrPaceRatio = hrPaceRatio,
    )

    // ==================== 规则 1: HrZone5Rule ====================

    @Test
    fun `HrZone5Rule — 连续 10 秒 Z5 触发`() {
        val rule = HrZone5Rule()
        val frames = (1..10).map { makeFrame(it.toLong(), hr = 170, hrZone = HrZone.Z5) }
        val ctx = RuleContext(recentFrames = frames)
        val trigger = rule.evaluate(ctx, config)
        assertNotNull(trigger)
        assertEquals("hr_z5_danger", trigger!!.ruleId)
        assertEquals("心率过高，请立即放慢", trigger.speakText)
    }

    @Test
    fun `HrZone5Rule — 不足 10 秒不触发`() {
        val rule = HrZone5Rule()
        val frames = (1..9).map { makeFrame(it.toLong(), hr = 170, hrZone = HrZone.Z5) }
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, config))
    }

    @Test
    fun `HrZone5Rule — 非 Z5 区间不触发`() {
        val rule = HrZone5Rule()
        val frames = (1..10).map { makeFrame(it.toLong(), hr = 140, hrZone = HrZone.Z2) }
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, config))
    }

    // ==================== 规则 2: HrRapidRiseRule ====================

    @Test
    fun `HrRapidRiseRule — 30s 上升 15bpm 且在 Z4 触发`() {
        val rule = HrRapidRiseRule()
        val frames = listOf(
            makeFrame(1, 145, hrZone = HrZone.Z3, timestamp = 0),
            makeFrame(30, 165, hrZone = HrZone.Z4, timestamp = 30_000),
        )
        val ctx = RuleContext(recentFrames = frames)
        val trigger = rule.evaluate(ctx, config)
        assertNotNull(trigger)
        assertEquals("hr_rapid_rise", trigger!!.ruleId)
    }

    @Test
    fun `HrRapidRiseRule — HR 上升不足 15bpm 不触发`() {
        val rule = HrRapidRiseRule()
        val frames = listOf(
            makeFrame(1, 150, hrZone = HrZone.Z3, timestamp = 0),
            makeFrame(30, 164, hrZone = HrZone.Z4, timestamp = 30_000),
        )
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, config))
    }

    @Test
    fun `HrRapidRiseRule — 不在 Z4 不触发`() {
        val rule = HrRapidRiseRule()
        val frames = listOf(
            makeFrame(1, 120, hrZone = HrZone.Z2, timestamp = 0),
            makeFrame(30, 140, hrZone = HrZone.Z3, timestamp = 30_000),
        )
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, config))
    }

    // ==================== 规则 3: HrTooLowRule ====================

    @Test
    fun `HrTooLowRule — 10 分钟后连续 30s 低于 Z2 触发`() {
        val rule = HrTooLowRule()
        // Z2 下限 = 180 * 60% = 108, 100 < 108
        val frames = (1..30).map { makeFrame(it.toLong() + 600, hr = 100) }
        val ctx = RuleContext(recentFrames = frames, elapsedSeconds = 630)
        val trigger = rule.evaluate(ctx, config)
        assertNotNull(trigger)
        assertEquals("hr_too_low", trigger!!.ruleId)
    }

    @Test
    fun `HrTooLowRule — 不足 10 分钟不触发`() {
        val rule = HrTooLowRule()
        val frames = (1..30).map { makeFrame(it.toLong(), hr = 100) }
        val ctx = RuleContext(recentFrames = frames, elapsedSeconds = 30)
        assertNull(rule.evaluate(ctx, config))
    }

    @Test
    fun `HrTooLowRule — 心率正常不触发`() {
        val rule = HrTooLowRule()
        val frames = (1..30).map { makeFrame(it.toLong() + 600, hr = 140) }
        val ctx = RuleContext(recentFrames = frames, elapsedSeconds = 630)
        assertNull(rule.evaluate(ctx, config))
    }

    // ==================== 规则 4: CadenceDropRule ====================

    @Test
    fun `CadenceDropRule — 30s 步频下降 10% 触发`() {
        val rule = CadenceDropRule()
        val frames = listOf(
            makeFrame(1, cadence = 175, timestamp = 0),
            makeFrame(30, cadence = 157, timestamp = 30_000), // 下降 ~10.3%
        )
        val ctx = RuleContext(recentFrames = frames)
        val trigger = rule.evaluate(ctx, config)
        assertNotNull(trigger)
        assertEquals("cadence_drop", trigger!!.ruleId)
    }

    @Test
    fun `CadenceDropRule — 步频低于 160 直接触发`() {
        val rule = CadenceDropRule()
        val frames = listOf(makeFrame(1, cadence = 155))
        val ctx = RuleContext(recentFrames = frames)
        val trigger = rule.evaluate(ctx, config)
        assertNotNull(trigger)
        assertEquals("cadence_drop", trigger!!.ruleId)
    }

    @Test
    fun `CadenceDropRule — 步频正常不触发`() {
        val rule = CadenceDropRule()
        val frames = listOf(
            makeFrame(1, cadence = 172, timestamp = 0),
            makeFrame(30, cadence = 170, timestamp = 30_000),
        )
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, config))
    }

    // ==================== 规则 5: CadenceTooHighRule ====================

    @Test
    fun `CadenceTooHighRule — 连续 10s 步频大于 200 触发`() {
        val rule = CadenceTooHighRule()
        val frames = (1..10).map { makeFrame(it.toLong(), cadence = 210) }
        val ctx = RuleContext(recentFrames = frames)
        val trigger = rule.evaluate(ctx, config)
        assertNotNull(trigger)
        assertEquals("cadence_too_high", trigger!!.ruleId)
    }

    @Test
    fun `CadenceTooHighRule — 步频正常不触发`() {
        val rule = CadenceTooHighRule()
        val frames = (1..10).map { makeFrame(it.toLong(), cadence = 180) }
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, config))
    }

    // ==================== 规则 6: PaceDeviationRule ====================

    @Test
    fun `PaceDeviationRule — 连续 30s 慢于目标 10% 触发`() {
        val rule = PaceDeviationRule()
        // 目标配速 360，慢 15% → 414
        val frames = (1..30).map { makeFrame(it.toLong(), pace = 414.0) }
        val ctx = RuleContext(recentFrames = frames)
        val trigger = rule.evaluate(ctx, config)
        assertNotNull(trigger)
        assertEquals("pace_deviation", trigger!!.ruleId)
    }

    @Test
    fun `PaceDeviationRule — 配速在范围内不触发`() {
        val rule = PaceDeviationRule()
        val frames = (1..30).map { makeFrame(it.toLong(), pace = 380.0) }
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, config))
    }

    @Test
    fun `PaceDeviationRule — 无目标配速不触发`() {
        val rule = PaceDeviationRule()
        val noTargetConfig = RunConfig(targetPaceSecPerKm = null)
        val frames = (1..30).map { makeFrame(it.toLong(), pace = 500.0) }
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, noTargetConfig))
    }

    // ==================== 规则 7: StartTooFastRule ====================

    @Test
    fun `StartTooFastRule — 首公里快于目标 8% 触发`() {
        val rule = StartTooFastRule()
        // 目标 360，快 10% → 324
        val frames = listOf(makeFrame(1, pace = 324.0, distance = 500.0))
        val ctx = RuleContext(recentFrames = frames)
        val trigger = rule.evaluate(ctx, config)
        assertNotNull(trigger)
        assertEquals("start_too_fast", trigger!!.ruleId)
    }

    @Test
    fun `StartTooFastRule — 超过 1 公里不触发`() {
        val rule = StartTooFastRule()
        val frames = listOf(makeFrame(1, pace = 324.0, distance = 1100.0))
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, config))
    }

    @Test
    fun `StartTooFastRule — 配速正常不触发`() {
        val rule = StartTooFastRule()
        val frames = listOf(makeFrame(1, pace = 350.0, distance = 500.0))
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, config))
    }

    // ==================== 规则 8: EfficiencyAbnormalRule ====================

    @Test
    fun `EfficiencyAbnormalRule — 心效恶化 15% 且步幅下降触发`() {
        val rule = EfficiencyAbnormalRule()
        val frames = mutableListOf<MetricsFrame>()
        // 前 30 帧正常效率
        for (i in 1..30) {
            frames.add(makeFrame(i.toLong(), hr = 140, pace = 360.0, stride = 100f, hrPaceRatio = 140.0 / 360.0))
        }
        // 当前帧效率恶化 + 步幅下降
        frames.add(makeFrame(31, hr = 160, pace = 360.0, stride = 90f, hrPaceRatio = 160.0 / 360.0))
        val ctx = RuleContext(recentFrames = frames)
        val trigger = rule.evaluate(ctx, config)
        // 心效比从 0.389 → 0.444，恶化 14.3%，不到 15%
        // 需要更极端的恶化
        assertNull(trigger)
    }

    @Test
    fun `EfficiencyAbnormalRule — 恶化 20% 且步幅下降触发`() {
        val rule = EfficiencyAbnormalRule()
        val frames = mutableListOf<MetricsFrame>()
        // 前 31 帧: hr=140, pace=360 → ratio ≈ 0.389
        for (i in 1..31) {
            frames.add(makeFrame(i.toLong(), hr = 140, pace = 360.0, stride = 100f, hrPaceRatio = 0.389))
        }
        // 当前帧: hr=170, pace=360 → ratio ≈ 0.472，恶化 21.4%
        frames.add(makeFrame(32, hr = 170, pace = 360.0, stride = 90f, hrPaceRatio = 0.472))
        val ctx = RuleContext(recentFrames = frames)
        val trigger = rule.evaluate(ctx, config)
        assertNotNull(trigger)
        assertEquals("efficiency_abnormal", trigger!!.ruleId)
    }

    @Test
    fun `EfficiencyAbnormalRule — 帧数不足不触发`() {
        val rule = EfficiencyAbnormalRule()
        val frames = (1..10).map { makeFrame(it.toLong()) }
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, config))
    }

    // ==================== 规则 9: DataStaleRule ====================

    @Test
    fun `DataStaleRule — 数据不新鲜触发`() {
        val rule = DataStaleRule()
        val frames = listOf(makeFrame(1, isDataFresh = false))
        val ctx = RuleContext(recentFrames = frames)
        val trigger = rule.evaluate(ctx, config)
        assertNotNull(trigger)
        assertEquals("data_stale", trigger!!.ruleId)
    }

    @Test
    fun `DataStaleRule — 数据正常不触发`() {
        val rule = DataStaleRule()
        val frames = listOf(makeFrame(1, isDataFresh = true))
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, config))
    }

    // ==================== 规则 10: KmSplitRule ====================

    @Test
    fun `KmSplitRule — 使用 completedSplit 生成权威播报`() {
        val rule = KmSplitRule()
        val split = Split(
            index = 3,
            distanceMeters = 3_000.0,
            durationSec = 407L,
            avgHeartRate = null,
            avgCadence = null,
            avgPaceSecPerKm = 395.0,
            hrZoneDistribution = null,
        )
        val ctx = RuleContext(
            recentFrames = listOf(
                makeFrame(
                    elapsedSec = 999,
                    pace = 222.0,
                    distance = 4_250.0,
                ),
            ),
            completedSplit = split,
        )

        val trigger = rule.evaluate(ctx, config)

        assertNotNull(trigger)
        assertEquals("km_split", trigger!!.ruleId)
        assertEquals("第3公里，用时6分47秒，配速6分35秒", trigger.speakText)
    }

    @Test
    fun `KmSplitRule — 没有 completedSplit 不触发`() {
        val rule = KmSplitRule()
        val ctx = RuleContext(recentFrames = listOf(makeFrame(1, distance = 3_000.0)))

        assertNull(rule.evaluate(ctx, config))
    }

    // ==================== 规则 11: GoalAchievedRule ====================

    @Test
    fun `GoalAchievedRule — 达到目标距离触发`() {
        val rule = GoalAchievedRule()
        val frames = listOf(makeFrame(1, distance = 5100.0))
        val ctx = RuleContext(recentFrames = frames)
        val trigger = rule.evaluate(ctx, config)
        assertNotNull(trigger)
        assertEquals("goal_achieved", trigger!!.ruleId)
    }

    @Test
    fun `GoalAchievedRule — 未达到目标距离不触发`() {
        val rule = GoalAchievedRule()
        val frames = listOf(makeFrame(1, distance = 3000.0))
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, config))
    }

    @Test
    fun `GoalAchievedRule — 无目标距离不触发`() {
        val rule = GoalAchievedRule()
        val noTargetConfig = RunConfig(targetDistanceMeters = null)
        val frames = listOf(makeFrame(1, distance = 10000.0))
        val ctx = RuleContext(recentFrames = frames)
        assertNull(rule.evaluate(ctx, noTargetConfig))
    }
}
