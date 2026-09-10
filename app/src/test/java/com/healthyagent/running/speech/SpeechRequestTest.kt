package com.healthyagent.running.speech

import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SpeechRequestTest {
    @Test
    fun `请求保留规则身份且默认不启用陪伴分析`() {
        val trigger = RuleTrigger(
            ruleId = "session_started",
            severity = Severity.INFO,
            speakText = "流萤已开始记录，出发吧。",
            ttsPriority = SpeechPriority.APP,
        )

        val request = SpeechRequest(trigger)

        assertSame(trigger, request.trigger)
        assertFalse(request.companionEnabled)
        assertNull(request.analysis)
    }

    @Test
    fun `请求可显式启用陪伴但不要求分析上下文`() {
        val trigger = RuleTrigger(
            ruleId = "pace_deviation",
            severity = Severity.NORMAL,
            speakText = "配速低于目标，请加快",
            ttsPriority = SpeechPriority.COACH,
        )

        val request = SpeechRequest(trigger, companionEnabled = true)

        assertEquals("pace_deviation", request.trigger.ruleId)
        assertEquals(true, request.companionEnabled)
        assertNull(request.analysis)
    }
}
