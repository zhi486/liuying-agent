package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.engine.paceDeviationPercent
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

/**
 * 规则 7：起跑过快
 *
 * 触发条件：目标配速存在、首公里内、配速快于目标 > 8%
 * 仅触发一次（cooldownMs = Long.MAX_VALUE）
 */
class StartTooFastRule : Rule {
    override val id = "start_too_fast"
    override val cooldownMs = Long.MAX_VALUE // 单次触发
    override val defaultPriority = SpeechPriority.APP

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        val targetPace = config.targetPaceSecPerKm ?: return null
        val frame = ctx.recentFrames.lastOrNull() ?: return null

        // 仅在首公里内检测
        if (frame.distanceMeters >= 1000.0) return null

        // 配速快于目标 > 8%（负值 = 快于目标）
        val deviation = ctx.paceDeviationPercent(targetPace) ?: return null
        if (deviation > -0.08) return null // 例如 -0.10 < -0.08 → 快了10%，触发

        return RuleTrigger(
            ruleId = id,
            severity = Severity.NORMAL,
            speakText = "起跑太快了，保存体力",
            ttsPriority = defaultPriority,
            frame = frame,
        )
    }
}
