package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.engine.consecutiveCondition
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

/**
 * 规则 5：步频过高
 *
 * 触发条件：步频 > 200 spm 连续 10 秒
 */
class CadenceTooHighRule : Rule {
    override val id = "cadence_too_high"
    override val cooldownMs = 60_000L
    override val defaultPriority = SpeechPriority.APP

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        val frame = ctx.recentFrames.lastOrNull() ?: return null
        val cadence = frame.cadence ?: return null
        if (cadence <= 200) return null

        val consecutiveHigh = ctx.consecutiveCondition(10) { (it.cadence ?: 0) > 200 }
        if (consecutiveHigh < 10) return null

        return RuleTrigger(
            ruleId = id,
            severity = Severity.NORMAL,
            speakText = "步频过高，试着增大步幅",
            ttsPriority = defaultPriority,
            frame = frame,
        )
    }
}
