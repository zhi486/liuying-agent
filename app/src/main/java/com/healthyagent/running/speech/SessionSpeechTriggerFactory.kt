package com.healthyagent.running.speech

import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

enum class SessionSpeechEvent { STARTED, PAUSED, RESUMED, FINISHED }

object SessionSpeechTriggerFactory {
    fun create(event: SessionSpeechEvent): RuleTrigger = when (event) {
        SessionSpeechEvent.STARTED -> status("session_started", "流萤已开始记录，出发吧。")
        SessionSpeechEvent.PAUSED -> status("session_paused", "跑步已暂停。")
        SessionSpeechEvent.RESUMED -> status("session_resumed", "跑步已恢复。")
        SessionSpeechEvent.FINISHED -> status("session_finished", "跑步已结束。")
    }

    private fun status(id: String, text: String) = RuleTrigger(
        ruleId = id,
        severity = Severity.INFO,
        speakText = text,
        ttsPriority = SpeechPriority.APP,
    )
}
