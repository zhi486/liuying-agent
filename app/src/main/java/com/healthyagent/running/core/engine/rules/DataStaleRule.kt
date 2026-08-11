package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

/**
 * 规则 9：数据中断检测
 *
 * 触发条件：最新帧的 isDataFresh 为 false（Pipeline 已检测 5s 无新数据）
 */
class DataStaleRule : Rule {
    override val id = "data_stale"
    override val cooldownMs = 30_000L
    override val defaultPriority = SpeechPriority.APP

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        val frame = ctx.recentFrames.lastOrNull() ?: return null
        if (frame.isDataFresh) return null

        return RuleTrigger(
            ruleId = id,
            severity = Severity.NORMAL,
            speakText = "手环数据中断，请检查",
            ttsPriority = defaultPriority,
            frame = frame,
        )
    }
}
