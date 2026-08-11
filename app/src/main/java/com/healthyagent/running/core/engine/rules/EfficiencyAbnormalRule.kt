package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.engine.currentHrPaceRatio
import com.healthyagent.running.core.engine.currentStrideLengthCm
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

/**
 * 规则 8：效率异常
 *
 * 触发条件：心效比恶化 > 15% 且步幅下降
 * 冷却由 RuleEngine.onSplit() 控制（每公里重置）
 */
class EfficiencyAbnormalRule : Rule {
    override val id = "efficiency_abnormal"
    override val cooldownMs = 0L // 由 onSplit 事件管理冷却
    override val defaultPriority = SpeechPriority.APP

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        val frames = ctx.recentFrames
        if (frames.size < 31) return null

        val currentFrame = frames.last()
        val currentRatio = currentFrame.hrPaceRatio ?: return null
        val currentStride = currentFrame.strideLengthCm ?: return null

        // 对比 30 秒前的心效比
        val pastFrame = frames[frames.size - 31]
        val pastRatio = pastFrame.hrPaceRatio ?: return null
        val pastStride = pastFrame.strideLengthCm ?: return null

        if (pastRatio <= 0) return null

        // 心效比恶化 > 15%（值越大效率越低）
        val worsening = (currentRatio - pastRatio) / pastRatio
        if (worsening <= 0.15) return null

        // 步幅下降
        if (currentStride >= pastStride) return null

        return RuleTrigger(
            ruleId = id,
            severity = Severity.NORMAL,
            speakText = "这一公里效率下降，试着提高步频",
            ttsPriority = defaultPriority,
            frame = currentFrame,
        )
    }
}
