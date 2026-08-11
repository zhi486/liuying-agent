package com.healthyagent.running.ai

import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.core.model.Split

/**
 * 将跑步上下文构建为 LLM 分析请求
 *
 * 纯 Kotlin，零 Android 依赖，可 JVM 单测。
 */
object PromptBuilder {

    /**
     * 从当前会话状态 + 最新分段数据构建分析请求
     *
     * @param session 当前跑步会话
     * @param split 最新产生的分段
     * @param config 跑步配置
     * @param recentTriggers 最近触发的规则描述列表（最近 5 条）
     */
    fun build(
        session: RunSession,
        split: Split,
        config: RunConfig,
        recentTriggers: List<String> = emptyList(),
    ): LlmAnalyzeRequest {
        val distanceKm = session.totalDistanceMeters / 1000.0

        return LlmAnalyzeRequest(
            session_id = session.id,
            split_index = split.index,
            split_data = LlmSplitData(
                split_index = split.index,
                distance_km = split.distanceMeters / 1000.0,
                duration_sec = split.durationSec,
                avg_pace_sec_per_km = split.avgPaceSecPerKm,
                avg_heart_rate = split.avgHeartRate?.toInt(),
                avg_cadence = split.avgCadence?.toInt(),
            ),
            session_summary = LlmSessionSummary(
                total_distance_km = distanceKm,
                total_duration_sec = session.totalDurationSec,
                avg_pace_sec_per_km = session.avgPaceSecPerKm
                    ?: (session.totalDurationSec / (distanceKm.coerceAtLeast(0.01))),
                avg_heart_rate = session.avgHeartRate?.toDouble(),
                avg_cadence = session.avgCadence?.toDouble(),
            ),
            recent_triggers = recentTriggers.takeLast(5),
            run_config = LlmRunConfig(
                target_pace_sec_per_km = config.targetPaceSecPerKm,
                target_distance_km = config.targetDistanceMeters?.div(1000.0),
            ),
        )
    }
}
