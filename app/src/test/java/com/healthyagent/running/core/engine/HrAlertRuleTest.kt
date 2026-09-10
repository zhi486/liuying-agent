package com.healthyagent.running.core.engine

import com.healthyagent.running.core.engine.rules.HrZone5Rule
import com.healthyagent.running.core.math.HrZoneCalculator
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.core.model.effectiveHrAlertThresholdBpm
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HrAlertRuleTest {
    private val clock = TestTimeSource()

    private fun frame(hr: Int? = 162, fresh: Boolean = true) = MetricsFrame(
        timestampMillis = 0L, elapsedSeconds = 0L, heartRate = hr,
        heartRateSmoothed = hr, hrZone = HrZone.Z2, cadence = null,
        strideLengthCm = null, currentPaceSecPerKm = null, distanceMeters = 0.0,
        splitDistanceMeters = 0.0, totalSteps = 0L, isDataFresh = false,
        hrPaceRatio = null, isHeartRateFresh = fresh,
    )

    @Test
    fun `自动阈值保持旧算法在全部合法最大心率下的最低Z5边界`() {
        for (maxHr in 80..240) {
            val threshold = RunConfig(maxHeartRate = maxHr).effectiveHrAlertThresholdBpm()
            assertEquals("最大心率 $maxHr 的门槛", HrZone.Z5, HrZoneCalculator.zone(threshold, maxHr))
            assertTrue("最大心率 $maxHr 的门槛前一值", HrZoneCalculator.zone(threshold - 1, maxHr) != HrZone.Z5)
        }
        assertEquals(162, RunConfig().effectiveHrAlertThresholdBpm())
    }

    @Test
    fun `手动警戒值不随最大心率或分区改变且GPS陈旧不阻止新鲜心率告警`() {
        for (maxHr in listOf(180, 220)) {
            val config = RunConfig(maxHeartRate = maxHr, hrAlertThresholdBpm = 150)
            val rule = HrZone5Rule(clock)
            val ctx = RuleContext(recentFrames = listOf(frame(150)))
            repeat(10) {
                assertNull(rule.evaluate(ctx, config))
                clock += 1.seconds
            }
            val trigger = rule.evaluate(ctx, config)
            assertNotNull(trigger)
            assertEquals(SpeechPriority.URGENT, trigger!!.ttsPriority)
        }
    }

    @Test
    fun `陈旧或空心率不能沿用旧Z5分区触发告警`() {
        for (current in listOf(frame(170, false), frame(null))) {
            val rule = HrZone5Rule(clock)
            repeat(15) {
                assertNull(rule.evaluate(RuleContext(recentFrames = List(10) { current.copy(hrZone = HrZone.Z5) }), RunConfig()))
                clock += 1.seconds
            }
        }
    }

    @Test
    fun `空上下文清除连续性而不是延续上次高心率`() {
        val rule = HrZone5Rule(clock)
        val ctx = RuleContext(recentFrames = listOf(frame()))
        repeat(9) {
            assertNull(rule.evaluate(ctx, RunConfig()))
            clock += 1.seconds
        }
        assertNull(rule.evaluate(RuleContext(), RunConfig()))
        repeat(10) {
            assertNull(rule.evaluate(ctx, RunConfig()))
            clock += 1.seconds
        }
        assertNotNull(rule.evaluate(ctx, RunConfig()))
    }

    @Test
    fun `规则引擎跨公里和墙钟前后调整不能绕过专用冷却`() {
        var wallMillis = 0L
        val engine = RuleEngine(listOf(HrZone5Rule(clock)), RunConfig(), nowMillis = { wallMillis })
        repeat(10) {
            assertTrue(engine.evaluate(frame(), RuleContext()).isEmpty())
            clock += 1.seconds
        }
        assertEquals(1, engine.evaluate(frame(), RuleContext()).size)
        engine.onSplit(1)
        repeat(119) {
            wallMillis = if (it % 2 == 0) 1_000_000L else -1_000_000L
            clock += 1.seconds
            assertTrue(engine.evaluate(frame(), RuleContext()).isEmpty())
        }
        clock += 1.seconds
        assertEquals(1, engine.evaluate(frame(), RuleContext()).size)
    }

    @Test
    fun `外层引擎不能在冷却期跳过低心率观察`() {
        val engine = RuleEngine(listOf(HrZone5Rule(clock)), RunConfig(hrAlertRepeatIntervalSec = 30))
        repeat(10) {
            engine.evaluate(frame(), RuleContext())
            clock += 1.seconds
        }
        assertEquals(1, engine.evaluate(frame(), RuleContext()).size)
        repeat(25) {
            clock += 1.seconds
            assertTrue(engine.evaluate(frame(), RuleContext()).isEmpty())
        }
        assertTrue(engine.evaluate(frame(150), RuleContext()).isEmpty())
        repeat(10) {
            clock += 1.seconds
            assertTrue(engine.evaluate(frame(), RuleContext()).isEmpty())
        }
        clock += 1.seconds
        assertEquals(1, engine.evaluate(frame(), RuleContext()).size)
    }

    @Test
    fun `引擎暂停只清连续性而新跑重置冷却`() {
        val engine = RuleEngine(listOf(HrZone5Rule(clock)), RunConfig())
        repeat(9) {
            assertTrue(engine.evaluate(frame(), RuleContext()).isEmpty())
            clock += 1.seconds
        }
        engine.onPause()
        repeat(10) {
            assertTrue(engine.evaluate(frame(), RuleContext()).isEmpty())
            clock += 1.seconds
        }
        assertEquals(1, engine.evaluate(frame(), RuleContext()).size)
        engine.onPause()
        repeat(20) {
            clock += 1.seconds
            assertTrue(engine.evaluate(frame(), RuleContext()).isEmpty())
        }
        engine.reset()
        repeat(10) {
            assertTrue(engine.evaluate(frame(), RuleContext()).isEmpty())
            clock += 1.seconds
        }
        assertEquals(1, engine.evaluate(frame(), RuleContext()).size)
    }
}
