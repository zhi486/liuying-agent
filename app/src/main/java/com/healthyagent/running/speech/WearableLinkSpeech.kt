package com.healthyagent.running.speech

import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority

/** 仅由已确认的运动链路故障调用，不由定位或心率新鲜度触发。 */
object WearableLinkSpeech {
    fun create() = RuleTrigger(
        ruleId = "data_stale",
        severity = Severity.NORMAL,
        speakText = "手环数据中断，请检查",
        ttsPriority = SpeechPriority.APP,
    )
}
