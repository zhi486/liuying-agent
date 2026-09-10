package com.healthyagent.running.speech

import com.healthyagent.running.ai.LlmAnalyzeRequest
import com.healthyagent.running.core.model.RuleTrigger
import kotlin.time.TimeMark

data class SpeechRequest(
    val trigger: RuleTrigger,
    val companionEnabled: Boolean = false,
    val analysis: LlmAnalyzeRequest? = null,
    /** 标记携带单调时钟源；排队和 pending 提升不得重置在线准备预算。 */
    val preparationAcceptedAt: TimeMark? = null,
)

data class SpeechDelivery(
    val text: String,
    val played: Boolean,
)
