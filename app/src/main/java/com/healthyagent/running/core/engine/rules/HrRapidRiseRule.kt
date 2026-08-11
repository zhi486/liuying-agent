package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.engine.hrDelta
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

/**
 * 规则 2：心率快速上升
 *
 * 触发条件：30 秒内心率上升 ≥ 15 bpm 且当前在 Z4 或以上
 */
class HrRapidRiseRule : Rule {
    override val id = "hr_rapid_rise"
    override val cooldownMs = 60_000L
    override val defaultPriority = SpeechPriority.URGENT

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        val frame = ctx.recentFrames.lastOrNull() ?: return null
        val hrDelta = ctx.hrDelta(30) ?: return null
        if (hrDelta < 15.0) return null
        if (frame.hrZone < HrZone.Z4) return null

        return RuleTrigger(
            ruleId = id,
            severity = Severity.URGENT,
            speakText = "心率上升很快，控制节奏",
            ttsPriority = defaultPriority,
            frame = frame,
        )
    }
}
