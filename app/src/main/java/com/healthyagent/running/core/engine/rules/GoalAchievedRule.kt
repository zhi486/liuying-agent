package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

/**
 * 规则 11：目标达成
 *
 * 触发条件：距离 ≥ 目标距离
 * 仅触发一次（cooldownMs = Long.MAX_VALUE）
 */
class GoalAchievedRule : Rule {
    override val id = "goal_achieved"
    override val cooldownMs = Long.MAX_VALUE // 单次触发
    override val defaultPriority = SpeechPriority.SPLIT

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        val targetDistance = config.targetDistanceMeters ?: return null
        val frame = ctx.recentFrames.lastOrNull() ?: return null

        if (frame.distanceMeters < targetDistance) return null

        val km = (frame.distanceMeters / 1000.0)
        return RuleTrigger(
            ruleId = id,
            severity = Severity.INFO,
            speakText = "已达成目标，干得漂亮！",
            ttsPriority = defaultPriority,
            frame = frame,
        )
    }
}
