package com.healthyagent.running.core.model

/**
 * 规则引擎的评估上下文 — 提供历史窗口数据
 */
data class RuleContext(
    /** 最近 30 秒的 MetricsFrame 序列 */
    val recentFrames: List<MetricsFrame> = emptyList(),
    /** 跑步已进行时间（秒） */
    val elapsedSeconds: Long = 0,
    /** 当前分段索引 */
    val currentSplitIndex: Int = 0,
    /** 当前帧完成的公里分段；仅在该帧对应 split 事件时提供。 */
    val completedSplit: Split? = null,
    /** 上一次触发时间记录（按 ruleId 记录时间戳） */
    val lastTriggerTimes: Map<String, Long> = emptyMap(),
)
