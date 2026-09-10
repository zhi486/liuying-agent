package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.speech.KilometerSpeechFormatter

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
            speakText = KilometerSpeechFormatter.format(
                split.index,
                split.durationSec,
                split.avgPaceSecPerKm,
            ),
            ttsPriority = defaultPriority,
            frame = ctx.recentFrames.lastOrNull(),
        )
    }
}
