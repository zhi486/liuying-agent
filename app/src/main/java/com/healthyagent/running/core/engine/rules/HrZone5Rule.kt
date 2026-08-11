package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.engine.consecutiveInZone
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

/**
 * 规则 1：心率 Z5 危险
 *
 * 触发条件：连续 10 秒在 Z5（极限）区间
 */
class HrZone5Rule : Rule {
    override val id = "hr_z5_danger"
    override val cooldownMs = 30_000L
    override val defaultPriority = SpeechPriority.URGENT

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        val frame = ctx.recentFrames.lastOrNull() ?: return null
        if (frame.hrZone != HrZone.Z5) return null
        if (!ctx.consecutiveInZone(HrZone.Z5, 10)) return null

        return RuleTrigger(
            ruleId = id,
            severity = Severity.URGENT,
            speakText = "心率过高，请立即放慢",
            ttsPriority = defaultPriority,
            frame = frame,
        )
    }
}
