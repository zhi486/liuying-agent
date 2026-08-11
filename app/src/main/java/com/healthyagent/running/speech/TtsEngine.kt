package com.healthyagent.running.speech

import com.healthyagent.running.core.model.SpeechPriority

/**
 * TTS 语音引擎接口
 *
 * 纯 Kotlin 接口，不依赖 Android SDK。SpeechBus 可对此接口做 JVM 单测。
 * AndroidTtsEngine 在 speech/ 包中实现此接口。
 */
interface TtsEngine {
    /** 当前播放状态 */
    val isSpeaking: Boolean

    /**
     * 播报文本
     *
     * @param text 要朗读的中文文本
     * @param priority 播报优先级（用于打断决策）
     * @return 是否成功开始播放
     */
    suspend fun speak(text: String, priority: SpeechPriority): Result<Unit>

    /** 停止当前播报 */
    fun stop()

    /** 释放 TTS 资源 */
    fun shutdown()
}
