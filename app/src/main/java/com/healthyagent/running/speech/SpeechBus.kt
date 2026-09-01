package com.healthyagent.running.speech

import com.healthyagent.running.core.model.CoachMessage
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.SpeechPriority
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SpeechPolicy(
    val enabled: Boolean,
    val minIntervalSec: Int,
)

/** 规则触发与 TTS 之间的去重、优先级和消息发布总线。 */
class SpeechBus(
    private val ttsEngine: TtsEngine,
    initialPolicy: SpeechPolicy = SpeechPolicy(enabled = true, minIntervalSec = 4),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newMessageId: () -> String = { UUID.randomUUID().toString() },
) {
    private val speakHistory = mutableMapOf<String, Long>()
    private val _latestMessage = MutableStateFlow<CoachMessage?>(null)
    private var currentPriority: SpeechPriority? = null
    private var policy = initialPolicy

    val latestMessage: StateFlow<CoachMessage?> = _latestMessage.asStateFlow()

    fun configure(policy: SpeechPolicy) {
        require(policy.minIntervalSec >= 0)
        this.policy = policy
    }

    fun resetSession() {
        speakHistory.clear()
        _latestMessage.value = null
    }

    suspend fun enqueue(trigger: RuleTrigger) {
        val activePolicy = policy
        if (!activePolicy.enabled) return

        val now = nowMillis()
        val lastSpoken = speakHistory[trigger.speakText]
        if (lastSpoken != null && now - lastSpoken < activePolicy.minIntervalSec * 1000L) {
            return
        }

        val activePriority = currentPriority
        if (activePriority != null && trigger.ttsPriority.level >= activePriority.level) {
            return
        }

        if (trigger.ttsPriority == SpeechPriority.URGENT && ttsEngine.isSpeaking) {
            ttsEngine.stop()
        }

        currentPriority = trigger.ttsPriority
        try {
            val result = runCatching {
                ttsEngine.speak(trigger.speakText, trigger.ttsPriority).getOrThrow()
            }
            if (result.isSuccess) {
                speakHistory[trigger.speakText] = now
                _latestMessage.value = CoachMessage(
                    id = newMessageId(),
                    text = trigger.speakText,
                    priority = trigger.ttsPriority,
                    timestampMillis = now,
                )
            }
        } finally {
            currentPriority = null
        }
    }

    fun stop() {
        ttsEngine.stop()
        currentPriority = null
    }

    fun shutdown() {
        ttsEngine.shutdown()
        currentPriority = null
        speakHistory.clear()
    }
}
