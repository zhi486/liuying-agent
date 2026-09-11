package com.healthyagent.running.speech

import com.healthyagent.running.ai.LlmAnalyzeRequest
import com.healthyagent.running.ai.DeepseekRequestConfig
import com.healthyagent.running.core.model.RuleTrigger
import kotlin.time.TimeMark
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class SpeechRequest(
    val trigger: RuleTrigger,
    val companionEnabled: Boolean = false,
    val analysis: LlmAnalyzeRequest? = null,
    /** 标记携带单调时钟源；排队和 pending 提升不得重置在线准备预算。 */
    val preparationAcceptedAt: TimeMark? = null,
    val noticeToken: SpeechNoticeToken? = null,
    val deepseekConfig: DeepseekRequestConfig? = null,
)

/** 单条通知的撤销凭证；随协程传至媒体启动门，不影响其他语音。 */
class SpeechNoticeToken(
    private val stillRelevant: () -> Boolean = { true },
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SpeechNoticeToken>
    private val invalidated = AtomicBoolean(false)
    private val playbackJob = AtomicReference<Job?>(null)
    val cancelled: Boolean get() = invalidated.get()
    internal val isValid: Boolean get() = !cancelled && stillRelevant()

    fun cancel() {
        invalidated.set(true)
        playbackJob.get()?.cancel(CancellationException("通知已撤销"))
    }

    internal fun bind(job: Job) {
        playbackJob.set(job)
        if (!isValid) job.cancel(CancellationException("通知已失效"))
    }

    internal fun ensureValid() {
        if (!isValid) throw CancellationException("通知已失效")
    }
}

data class SpeechDelivery(
    val text: String,
    val played: Boolean,
)
