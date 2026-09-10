package com.healthyagent.running.core.model

/**
 * 教练消息 — SpeechBus 发布的播报结果与核心文字
 *
 * 实播时展示实际内容；离线公里跳过或播放失败时也可展示核心文字，不展示未播鼓励。
 * 被去重、取消或打断丢弃的消息不上屏。
 * 放 core/model 保持 SpeechBus 纯 Kotlin 可单测。
 */
data class CoachMessage(
    val id: String,
    val text: String,
    val priority: SpeechPriority,
    val timestampMillis: Long,
)
