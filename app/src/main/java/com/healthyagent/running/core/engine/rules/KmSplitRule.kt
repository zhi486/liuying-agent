package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.math.MetricsCalculator
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

/**
 * 规则 10：每公里播报
 *
 * 触发条件：RuleContext 提供已完成的 split 事件。
 */
class KmSplitRule : Rule {
    override val id = "km_split"
    override val cooldownMs = 0L
    override val defaultPriority = SpeechPriority.SPLIT

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        val split = ctx.completedSplit ?: return null

        return RuleTrigger(
            ruleId = id,
            severity = Severity.INFO,
            speakText = "第${split.index}公里，用时${formatDuration(split.durationSec)}，配速${MetricsCalculator.formatPace(split.avgPaceSecPerKm)}",
            ttsPriority = defaultPriority,
            frame = ctx.recentFrames.lastOrNull(),
        )
    }

    private fun formatDuration(seconds: Long): String {
        val min = seconds / 60
        val sec = seconds % 60
        return "${min}分${sec.toString().padStart(2, '0')}秒"
    }
}
