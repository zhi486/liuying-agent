package com.healthyagent.running.speech

import com.healthyagent.running.ai.LlmAnalyzeRequest
import com.healthyagent.running.core.model.RuleTrigger

data class SpeechRequest(
    val trigger: RuleTrigger,
    val companionEnabled: Boolean = false,
    val analysis: LlmAnalyzeRequest? = null,
)

data class SpeechDelivery(
    val text: String,
    val played: Boolean,
)
