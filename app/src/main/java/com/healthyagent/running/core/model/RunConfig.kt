package com.healthyagent.running.core.model

/**
 * 跑步配置（用户可自定义目标）
 */
data class RunConfig(
    /** 目标配速（秒/公里），null 表示自由跑 */
    val targetPaceSecPerKm: Double? = null,
    /** 目标距离（米），null 表示不限距离 */
    val targetDistanceMeters: Double? = null,
    /** 目标时长（秒），null 表示不限时长 */
    val targetDurationSec: Long? = null,
    /** 最大心率（用于区间计算，默认 220 - 年龄） */
    val maxHeartRate: Int = 180,
    /** 是否启用语音播报 */
    val ttsEnabled: Boolean = true,
    /** 语音播报间隔（秒） */
    val ttsMinIntervalSec: Int = 4,
    /** 是否启用 LLM 深度分析 */
    val llmEnabled: Boolean = false,
    /** 教练表达风格 */
    val coachTone: CoachTone = CoachTone.BALANCED,
)
