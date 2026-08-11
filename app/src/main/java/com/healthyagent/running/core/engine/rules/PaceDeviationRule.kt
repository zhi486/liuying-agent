package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.engine.consecutiveCondition
import com.healthyagent.running.core.engine.paceDeviationPercent
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

/**
 * 规则 6：配速偏差
 *
 * 触发条件：有目标配速、当前配速慢于目标 > 10%、且持续 30 秒
 */
class PaceDeviationRule : Rule {
    override val id = "pace_deviation"
    override val cooldownMs = 60_000L
    override val defaultPriority = SpeechPriority.APP

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        val targetPace = config.targetPaceSecPerKm ?: return null
        val frame = ctx.recentFrames.lastOrNull() ?: return null

        // 当前配速偏离 > 10%（正值 = 慢于目标）
        val deviation = ctx.paceDeviationPercent(targetPace) ?: return null
        if (deviation <= 0.10) return null

        // 连续 30 秒慢于目标 10% 以上
        val consecutiveSlow = ctx.consecutiveCondition(30) { f ->
            val p = f.currentPaceSecPerKm ?: return@consecutiveCondition false
            (p - targetPace) / targetPace > 0.10
        }
        if (consecutiveSlow < 30) return null

        return RuleTrigger(
            ruleId = id,
            severity = Severity.NORMAL,
            speakText = "配速低于目标，请加快",
            ttsPriority = defaultPriority,
            frame = frame,
        )
    }
}
