package com.healthyagent.running.core.engine

import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RuleEngine 单元测试 — 验证冷却管理、多规则并行、边界条件
 */
class RuleEngineTest {

    private val config = RunConfig(maxHeartRate = 180)

    // 一条始终触发的测试规则
    private val alwaysTrigger = object : Rule {
        override val id = "always"
        override val cooldownMs = 100L
        override val defaultPriority = SpeechPriority.APP
        override fun evaluate(ctx: RuleContext, config: RunConfig) = RuleTrigger(
            ruleId = id,
            severity = Severity.NORMAL,
            speakText = "测试播报",
            ttsPriority = defaultPriority,
        )
    }

    // 一条永不触发的测试规则
    private val neverTrigger = object : Rule {
        override val id = "never"
        override val cooldownMs = 0L
        override val defaultPriority = SpeechPriority.APP
        override fun evaluate(ctx: RuleContext, config: RunConfig) = null
    }

    private fun makeFrame(elapsedSec: Long = 1): MetricsFrame = MetricsFrame(
        timestampMillis = System.currentTimeMillis(),
        elapsedSeconds = elapsedSec,
        heartRate = 140,
        heartRateSmoothed = 140,
        hrZone = HrZone.Z2,
        cadence = 170,
        strideLengthCm = null,
        currentPaceSecPerKm = null,
        distanceMeters = 0.0,
        splitDistanceMeters = 0.0,
        totalSteps = 0,
        isDataFresh = true,
        hrPaceRatio = null,
    )

    @Test
    fun `单规则触发 — 返回 RuleTrigger`() {
        val engine = RuleEngine(listOf(alwaysTrigger), config)
        val context = RuleContext()
        val triggers = engine.evaluate(makeFrame(), context)
        assertEquals(1, triggers.size)
        assertEquals("always", triggers[0].ruleId)
    }

    @Test
    fun `冷却 — 同一规则在冷却期内不重复触发`() {
        val engine = RuleEngine(listOf(alwaysTrigger), config)
        val context = RuleContext()

        val t1 = engine.evaluate(makeFrame(1), context)
        assertEquals(1, t1.size)

        val t2 = engine.evaluate(makeFrame(2), context.copy(recentFrames = listOf(makeFrame(1))))
        assertEquals(0, t2.size)
    }

    @Test
    fun `无冷却规则 — 每次都能触发`() {
        val noCooldown = object : Rule {
            override val id = "no_cooldown"
            override val cooldownMs = 0L
            override val defaultPriority = SpeechPriority.APP
            override fun evaluate(ctx: RuleContext, config: RunConfig) = RuleTrigger(
                ruleId = id,
                severity = Severity.NORMAL,
                speakText = "无冷却",
                ttsPriority = defaultPriority,
            )
        }
        val engine = RuleEngine(listOf(noCooldown), config)
        val context = RuleContext()

        assertEquals(1, engine.evaluate(makeFrame(1), context).size)
        assertEquals(1, engine.evaluate(makeFrame(2), context).size)
    }

    @Test
    fun `永不触发规则 — 返回空列表`() {
        val engine = RuleEngine(listOf(neverTrigger), config)
        val triggers = engine.evaluate(makeFrame(), RuleContext())
        assertTrue(triggers.isEmpty())
    }

    @Test
    fun `多规则 — 可以同时触发多条`() {
        val rule2 = object : Rule {
            override val id = "rule2"
            override val cooldownMs = 0L
            override val defaultPriority = SpeechPriority.APP
            override fun evaluate(ctx: RuleContext, config: RunConfig) = RuleTrigger(
                ruleId = id,
                severity = Severity.NORMAL,
                speakText = "第二条",
                ttsPriority = defaultPriority,
            )
        }
        val engine = RuleEngine(listOf(alwaysTrigger, rule2), config)
        val triggers = engine.evaluate(makeFrame(), RuleContext())
        assertEquals(2, triggers.size)
    }

    @Test
    fun `空规则列表 — 不崩溃，返回空`() {
        val engine = RuleEngine(emptyList(), config)
        val triggers = engine.evaluate(makeFrame(), RuleContext())
        assertTrue(triggers.isEmpty())
    }

    @Test
    fun `空 context — 不崩溃`() {
        val engine = RuleEngine(listOf(alwaysTrigger), config)
        val triggers = engine.evaluate(makeFrame(), RuleContext())
        assertEquals(1, triggers.size)
    }

    @Test
    fun `onSplit — 重置效率异常和每公里播报的冷却`() {
        val splitRule = object : Rule {
            override val id = "km_split"
            override val cooldownMs = 60_000L
            override val defaultPriority = SpeechPriority.SPLIT
            override fun evaluate(ctx: RuleContext, config: RunConfig) = RuleTrigger(
                ruleId = id,
                severity = Severity.INFO,
                speakText = "分段播报",
                ttsPriority = defaultPriority,
            )
        }
        val engine = RuleEngine(listOf(splitRule), config)
        val context = RuleContext()

        engine.evaluate(makeFrame(1), context)
        engine.onSplit(1)

        val triggers = engine.evaluate(makeFrame(2), context)
        assertEquals(1, triggers.size)
    }

    @Test
    fun `单次触发规则 — MAX_VALUE 冷却只能触发一次`() {
        val onceRule = object : Rule {
            override val id = "once"
            override val cooldownMs = Long.MAX_VALUE
            override val defaultPriority = SpeechPriority.APP
            override fun evaluate(ctx: RuleContext, config: RunConfig) = RuleTrigger(
                ruleId = id,
                severity = Severity.NORMAL,
                speakText = "仅一次",
                ttsPriority = defaultPriority,
            )
        }
        val engine = RuleEngine(listOf(onceRule), config)
        val context = RuleContext()

        assertEquals(1, engine.evaluate(makeFrame(1), context).size)
        assertEquals(0, engine.evaluate(makeFrame(2), context).size)
    }
}
