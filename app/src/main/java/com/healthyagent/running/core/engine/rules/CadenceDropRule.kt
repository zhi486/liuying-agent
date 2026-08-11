package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.engine.cadenceChangePercent
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

/**
 * 规则 4：步频骤降
 *
 * 触发条件：30 秒内步频下降 ≥ 10% 或 当前步频 < 160 spm
 */
class CadenceDropRule : Rule {
    override val id = "cadence_drop"
    override val cooldownMs = 30_000L
    override val defaultPriority = SpeechPriority.URGENT

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        val frame = ctx.recentFrames.lastOrNull() ?: return null
        val cadence = frame.cadence ?: return null

        // 条件 1：30s 步频下降 ≥ 10%
        val changePct = ctx.cadenceChangePercent(30)
        val significantDrop = changePct != null && changePct <= -0.10

        // 条件 2：当前步频 < 160
        val tooLow = cadence < 160

        if (!significantDrop && !tooLow) return null

        return RuleTrigger(
            ruleId = id,
            severity = Severity.URGENT,
            speakText = "步频下降了，保持节奏",
            ttsPriority = defaultPriority,
            frame = frame,
        )
    }
}
