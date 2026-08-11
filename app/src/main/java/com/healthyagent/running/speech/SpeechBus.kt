package com.healthyagent.running.speech

import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.SpeechPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 语音播报仲裁总线
 *
 * 负责规则触发 → TTS 播报之间的排队、去重、冷却和优先级打断。
 * 纯 Kotlin + Coroutines，不依赖 Android SDK，可 JVM 单测。
 */
class SpeechBus(
    private val ttsEngine: TtsEngine,
    private val config: RunConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 文本去重：text → 上次播报时间戳 */
    private val speakHistory = mutableMapOf<String, Long>()

    /** 当前正在播放的优先级 */
    private var currentPriority: SpeechPriority? = null

    /**
     * 提交一条规则触发结果到播报队列
     *
     * @param trigger 规则触发结果
     */
    suspend fun enqueue(trigger: RuleTrigger) {
        if (!config.ttsEnabled) return

        val now = System.currentTimeMillis()
        val lastSpoken = speakHistory[trigger.speakText]

        // 去重：同一文本在最小间隔内不重复播报
        if (lastSpoken != null && (now - lastSpoken) < config.ttsMinIntervalSec * 1000L) {
            return
        }

        // 优先级打断：同优先级或更低优先级不打断当前播报
        val current = currentPriority
        if (current != null && trigger.ttsPriority.level >= current.level) {
            return
        }

        // URGENT 打断当前播报
        if (trigger.ttsPriority == SpeechPriority.URGENT && ttsEngine.isSpeaking) {
            ttsEngine.stop()
        }

        currentPriority = trigger.ttsPriority
        val result = ttsEngine.speak(trigger.speakText, trigger.ttsPriority)

        result.onSuccess {
            speakHistory[trigger.speakText] = now
        }

        currentPriority = null
    }

    /** 停止所有播报并清空状态 */
    fun stop() {
        ttsEngine.stop()
        currentPriority = null
    }

    /** 释放资源 */
    fun shutdown() {
        ttsEngine.shutdown()
        currentPriority = null
        speakHistory.clear()
    }
}
