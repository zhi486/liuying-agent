package com.healthyagent.running.ai

import kotlinx.serialization.Serializable

/**
 * 单公里分段数据 — 对应 Python SplitData
 */
@Serializable
data class LlmSplitData(
    val split_index: Int,
    val distance_km: Double,
    val duration_sec: Long,
    val avg_pace_sec_per_km: Double,
    val avg_heart_rate: Int? = null,
    val avg_cadence: Int? = null,
)

/**
 * 心率区间分布（各区间秒数）
 */
@Serializable
data class HrZoneDistribution(
    val Z1: Int = 0,
    val Z2: Int = 0,
    val Z3: Int = 0,
    val Z4: Int = 0,
    val Z5: Int = 0,
)

/**
 * 会话累计摘要 — 对应 Python SessionSummary
 */
@Serializable
data class LlmSessionSummary(
    val total_distance_km: Double,
    val total_duration_sec: Long,
    val avg_pace_sec_per_km: Double,
    val avg_heart_rate: Double? = null,
    val avg_cadence: Double? = null,
    val hr_zone_distribution: HrZoneDistribution = HrZoneDistribution(),
)

/**
 * 跑步配置子集 — 对应 Python RunConfigRequest
 */
@Serializable
data class LlmRunConfig(
    val target_pace_sec_per_km: Double? = null,
    val target_distance_km: Double? = null,
)

/**
 * LLM 分析请求 — 对应 Python AnalyzeRequest
 */
@Serializable
data class LlmAnalyzeRequest(
    val session_id: String,
    val split_index: Int,
    val split_data: LlmSplitData,
    val session_summary: LlmSessionSummary,
    val recent_triggers: List<String> = emptyList(),
    val run_config: LlmRunConfig? = null,
)

/**
 * LLM 分析响应 — 对应 Python AnalyzeResponse
 */
@Serializable
data class LlmAnalyzeResponse(
    val speak_text: String,
    val focus_area: String? = null,
)
