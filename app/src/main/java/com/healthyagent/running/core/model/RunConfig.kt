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
    /** 独立警戒心率，null 沿用训练分区的自动门槛 */
    val hrAlertThresholdBpm: Int? = null,
    /** 同次高心率提醒的最小重复间隔（秒） */
    val hrAlertRepeatIntervalSec: Int = 120,
    /** 是否启用语音播报 */
    val ttsEnabled: Boolean = true,
    /** 语音播报间隔（秒） */
    val ttsMinIntervalSec: Int = 4,
    /** 是否启用 LLM 深度分析 */
    val llmEnabled: Boolean = false,
    /** 教练表达风格 */
    val coachTone: CoachTone = CoachTone.BALANCED,
)

/** 自动门槛沿用 Z5 下限，不代表个体医学评估。 */
fun RunConfig.effectiveHrAlertThresholdBpm(): Int = hrAlertThresholdBpm ?: (maxHeartRate * 9 + 9) / 10
