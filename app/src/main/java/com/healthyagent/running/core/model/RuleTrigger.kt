package com.healthyagent.running.core.model

/**
 * 规则触发结果 — 本地规则引擎的输出
 */
data class RuleTrigger(
    val ruleId: String,
    val severity: Severity,
    /** 已格式化的中文播报文本，可直接传给 TTS */
    val speakText: String,
    val ttsPriority: SpeechPriority,
    /** 触发时的指标快照（用于 LLM 上下文聚合） */
    val frame: MetricsFrame? = null,
)

enum class Severity { URGENT, NORMAL, INFO }

/**
 * TTS 播报优先级 — 数字越大优先级越低
 * URGENT 可打断任何正在播放的内容
 */
enum class SpeechPriority(val level: Int) {
    URGENT(0),   // 即时打断（心率危险、步频骤降）
    SPLIT(1),    // 分段播报（每公里）
    COACH(2),    // LLM 教练建议
    APP(3),      // 应用提示（开始/暂停/结束）
}
