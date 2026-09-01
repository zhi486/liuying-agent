package com.healthyagent.running.testing

import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.speech.TtsEngine
import kotlinx.coroutines.CompletableDeferred

class FakeTtsEngine : TtsEngine {
    val spokenTexts = mutableListOf<String>()
    var shouldFail = false
    var speakGate: CompletableDeferred<Unit>? = null
    var stopCount = 0
        private set
    var shutdownCount = 0
        private set

    @Volatile
    override var isSpeaking: Boolean = false

    fun markSpeaking() {
        isSpeaking = true
    }

    fun markDone() {
        isSpeaking = false
    }

    override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> = runCatching {
        isSpeaking = true
        speakGate?.await()
        if (shouldFail) error("模拟 TTS 失败")
        spokenTexts.add(text)
        Unit
    }.also {
        isSpeaking = false
    }

    override fun stop() {
        stopCount++
        speakGate?.cancel()
        isSpeaking = false
    }

    override fun shutdown() {
        shutdownCount++
        speakGate?.cancel()
        isSpeaking = false
    }
}
