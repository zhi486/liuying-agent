package com.healthyagent.running.speech

import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSpeechTriggerFactoryTest {
    @Test
    fun `STARTED 创建固定应用提示`() {
        assertTrigger(SessionSpeechEvent.STARTED, "session_started", "流萤已开始记录，出发吧。")
    }

    @Test
    fun `PAUSED 创建固定应用提示`() {
        assertTrigger(SessionSpeechEvent.PAUSED, "session_paused", "跑步已暂停。")
    }

    @Test
    fun `RESUMED 创建固定应用提示`() {
        assertTrigger(SessionSpeechEvent.RESUMED, "session_resumed", "跑步已恢复。")
    }

    @Test
    fun `FINISHED 创建固定应用提示`() {
        assertTrigger(SessionSpeechEvent.FINISHED, "session_finished", "跑步已结束。")
    }

    private fun assertTrigger(event: SessionSpeechEvent, ruleId: String, text: String) {
        val trigger = SessionSpeechTriggerFactory.create(event)
        assertEquals(ruleId, trigger.ruleId)
        assertEquals(text, trigger.speakText)
        assertEquals(Severity.INFO, trigger.severity)
        assertEquals(SpeechPriority.APP, trigger.ttsPriority)
    }
}
