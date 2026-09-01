package com.healthyagent.running.core.model

/**
 * 教练消息 — SpeechBus 实际播报内容的 UI 镜像
 *
 * 只有真正被 TTS 播报的消息才会产生（被去重/打断丢弃的不上屏，音画一致）。
 * 放 core/model 保持 SpeechBus 纯 Kotlin 可单测。
 */
data class CoachMessage(
    val id: String,
    val text: String,
    val priority: SpeechPriority,
    val timestampMillis: Long,
)
