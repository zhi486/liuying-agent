package com.healthyagent.running.speech

import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.effectiveHrAlertThresholdBpm

/** 本地授权的公里建议；不是医学诊断，模型不得自行升级动作。 */
enum class KilometerGuidance(val text: String) {
    NONE(""),
    HIGH_HEART_RATE("心率持续超过你设置的警戒线，请先放慢，不要勉强。"),
    FAST_PACE("最近配速持续快于目标，先放慢一点，回到计划节奏。"),
    BELOW_TARGET("最近配速慢于目标，如果感觉轻松，可以逐步靠近目标配速，不必勉强。"),
    CAUTION("先控制节奏，不要勉强。"),
}

object KilometerGuidancePolicy {
    fun select(frames: List<MetricsFrame>, config: RunConfig, urgent: Boolean = false): KilometerGuidance {
        val threshold = config.effectiveHrAlertThresholdBpm()
        if (sustained(frames, 10) {
                it.isHeartRateFresh && (it.heartRateSmoothed ?: 0) >= threshold
            }) return KilometerGuidance.HIGH_HEART_RATE
        if (urgent) return KilometerGuidance.CAUTION
        val target = config.targetPaceSecPerKm?.takeIf { it.isFinite() && it > 0 } ?: return KilometerGuidance.NONE
        val reliable = { frame: MetricsFrame ->
            frame.isDataFresh && frame.hasDistanceSample && frame.isHeartRateFresh &&
                frame.heartRateSmoothed?.let { it > 0 && it < threshold } == true &&
                frame.currentPaceSecPerKm?.let { it.isFinite() && it > 0 } == true
        }
        if (sustained(frames, 30) { reliable(it) && it.currentPaceSecPerKm!! < target * 0.9 }) {
            return KilometerGuidance.FAST_PACE
        }
        // 无训练阶段数据时，至少不在开跑前五分钟催促；低于目标也只给条件性建议。
        if ((frames.lastOrNull()?.elapsedSeconds ?: 0) >= 300 && sustained(frames, 30) {
                reliable(it) && it.hrZone < HrZone.Z4 && it.currentPaceSecPerKm!! > target * 1.1
            }) return KilometerGuidance.BELOW_TARGET
        return KilometerGuidance.NONE
    }

    private fun sustained(frames: List<MetricsFrame>, seconds: Long, predicate: (MetricsFrame) -> Boolean): Boolean {
        val last = frames.lastOrNull() ?: return false
        var newer = last
        for (frame in frames.asReversed()) {
            if (!predicate(frame) || newer.elapsedSeconds - frame.elapsedSeconds !in 0L..2L) return false
            if (last.elapsedSeconds - frame.elapsedSeconds >= seconds) return true
            newer = frame
        }
        return false
    }
}
