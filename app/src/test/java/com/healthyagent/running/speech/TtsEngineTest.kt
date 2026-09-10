package com.healthyagent.running.speech

import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsEngineTest {
    @Test
    fun `请求入口沿用规则文字和优先级并返回实际交付`() = runTest {
        val engine = RecordingTtsEngine()
        val request = SpeechRequest(trigger())

        val delivery = engine.speak(request).getOrThrow()

        assertEquals("跑步已暂停。", engine.receivedText)
        assertEquals(SpeechPriority.APP, engine.receivedPriority)
        assertEquals(SpeechDelivery("跑步已暂停。", played = true), delivery)
    }

    @Test
    fun `请求入口保留旧文本接口的失败`() = runTest {
        val expected = IllegalStateException("播放失败")
        val engine = RecordingTtsEngine(Result.failure(expected))

        val result = engine.speak(SpeechRequest(trigger()))

        assertTrue(result.isFailure)
        assertSame(expected, result.exceptionOrNull())
    }

    private fun trigger() = RuleTrigger(
        ruleId = "session_paused",
        severity = Severity.INFO,
        speakText = "跑步已暂停。",
        ttsPriority = SpeechPriority.APP,
    )

    private class RecordingTtsEngine(
        private val result: Result<Unit> = Result.success(Unit),
    ) : TtsEngine {
        var receivedText: String? = null
        var receivedPriority: SpeechPriority? = null

        override val isSpeaking = false

        override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> {
            receivedText = text
            receivedPriority = priority
            return result
        }

        override fun stop() = Unit

        override fun shutdown() = Unit
    }
}
