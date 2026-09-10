package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.core.model.effectiveHrAlertThresholdBpm
import kotlin.time.TimeSource

/**
 * 规则 1：心率持续达到警戒值
 *
 * 自动门槛沿用 Z5 下限；持续时间与专用冷却由单调时间门控管理。
 */
class HrZone5Rule(timeSource: TimeSource = TimeSource.Monotonic) : Rule {
    private val gate = HrAlertGate(timeSource)
    override val id = "hr_z5_danger"
    // 每帧都需观察连续性，不能被引擎的固定冷却跳过。
    override val cooldownMs = 0L
    override val defaultPriority = SpeechPriority.URGENT

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        val frame = ctx.recentFrames.lastOrNull()
        if (!gate.shouldAlert(
                heartRate = frame?.heartRateSmoothed,
                isFresh = frame?.isHeartRateFresh == true,
                thresholdBpm = config.effectiveHrAlertThresholdBpm(),
                repeatIntervalSec = config.hrAlertRepeatIntervalSec,
            )
        ) return null

        return RuleTrigger(
            ruleId = id,
            severity = Severity.URGENT,
            speakText = "心率过高，请立即放慢",
            ttsPriority = defaultPriority,
            frame = frame,
        )
    }

    override fun onPause() = gate.onPause()

    override fun reset() = gate.reset()
}
