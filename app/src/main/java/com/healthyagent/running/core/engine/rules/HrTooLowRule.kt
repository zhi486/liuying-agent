package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.engine.consecutiveCondition
import com.healthyagent.running.core.engine.z2LowerBound
import com.healthyagent.running.core.math.HrZoneCalculator
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

/**
 * 规则 3：心率偏低
 *
 * 触发条件：跑步 ≥ 10 分钟 && 连续 30 秒低于 Z2 下限
 */
class HrTooLowRule : Rule {
    override val id = "hr_too_low"
    override val cooldownMs = 60_000L
    override val defaultPriority = SpeechPriority.APP

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        // 跑步不足 10 分钟不触发
        if (ctx.elapsedSeconds < 600) return null

        val frame = ctx.recentFrames.lastOrNull() ?: return null
        val z2Lower = ctx.z2LowerBound(config.maxHeartRate)

        // 连续 30 秒平滑心率低于 Z2 下限
        val consecutiveLow = ctx.consecutiveCondition(30) { f ->
            val hr = f.heartRateSmoothed ?: return@consecutiveCondition false
            hr < z2Lower
        }
        if (consecutiveLow < 30) return null

        return RuleTrigger(
            ruleId = id,
            severity = Severity.NORMAL,
            speakText = "心率偏低，可以适当加快",
            ttsPriority = defaultPriority,
            frame = frame,
        )
    }
}
