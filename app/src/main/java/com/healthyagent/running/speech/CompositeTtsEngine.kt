package com.healthyagent.running.speech

import com.healthyagent.running.core.model.SpeechPriority
import timber.log.Timber

/**
 * 复合 TTS 引擎 — 全部优先级走 Fish Audio 云端音色，Android TTS 做 fallback
 *
 * - 主引擎（cloud）：Fish Audio — 专用音色，所有指令统一声音
 * - 备用引擎（local）：Android TTS — 云端失败时自动降级
 *
 * SpeechBus 无感知，保持单一 TtsEngine 接口。
 */
class CompositeTtsEngine(
    private val local: TtsEngine,   // AndroidTtsEngine — fallback
    private val cloud: TtsEngine,   // FishAudioTtsEngine — 主引擎
) : TtsEngine {

    override val isSpeaking: Boolean
        get() = local.isSpeaking || cloud.isSpeaking

    override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> {
        // 先用 Fish Audio 云端音色
        val result = cloud.speak(text, priority)
        if (result.isSuccess) return result

        // 云端失败 → 降级到 Android 本地 TTS
        Timber.w(result.exceptionOrNull(), "Fish Audio 播报失败，降级到本地 TTS")
        return local.speak(text, priority)
    }

    override fun stop() {
        local.stop()
        cloud.stop()
    }

    override fun shutdown() {
        local.shutdown()
        cloud.shutdown()
    }
}
