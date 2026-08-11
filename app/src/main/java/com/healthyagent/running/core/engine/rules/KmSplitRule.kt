package com.healthyagent.running.core.engine.rules

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.math.MetricsCalculator
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

/**
 * 规则 10：每公里播报
 *
 * 触发条件：split 事件（跨过整公里边界）
 * 冷却由 RuleEngine.onSplit() 控制
 */
class KmSplitRule : Rule {
    override val id = "km_split"
    override val cooldownMs = 0L // 由 onSplit 事件管理冷却
    override val defaultPriority = SpeechPriority.SPLIT

    /** 上一次检测到的公里数 */
    private var lastKm = 0

    override fun reset() {
        lastKm = 0
    }

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        val frame = ctx.recentFrames.lastOrNull() ?: return null
        val currentKm = (frame.distanceMeters / 1000.0).toInt()

        if (currentKm <= lastKm) return null
        lastKm = currentKm

        // 计算该公里分段数据
        val splitDuration = calculateSplitDuration(ctx.recentFrames, currentKm)
        val splitPace = if (splitDuration > 0) splitDuration.toDouble() else null
        val paceStr = splitPace?.let { MetricsCalculator.formatPace(it) } ?: "--"

        return RuleTrigger(
            ruleId = id,
            severity = Severity.INFO,
            speakText = "第${currentKm}公里，用时${formatDuration(splitDuration)}，配速${paceStr}",
            ttsPriority = defaultPriority,
            frame = frame,
        )
    }

    private fun calculateSplitDuration(frames: List<MetricsFrame>, km: Int): Long {
        // 找到上一公里边界附近的帧
        val startDistance = (km - 1) * 1000.0
        val startFrame = frames.lastOrNull { it.distanceMeters <= startDistance + 50 }
        val endFrame = frames.lastOrNull { it.distanceMeters >= km * 1000.0 - 50 }
        if (startFrame == null || endFrame == null) return 0
        return (endFrame.timestampMillis - startFrame.timestampMillis) / 1000
    }

    private fun formatDuration(seconds: Long): String {
        val min = seconds / 60
        val sec = seconds % 60
        return "${min}分${sec.toString().padStart(2, '0')}秒"
    }
}
