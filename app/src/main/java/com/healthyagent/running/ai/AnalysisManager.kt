package com.healthyagent.running.ai

import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.speech.SpeechBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * LLM 异步分析管理器 — 单飞队列 / 超时 / 降级
 *
 * 职责：
 * 1. 每公里触发一次 LLM 深度分析请求
 * 2. 单飞队列：同一时间只有一条请求在途，新请求替换排队中的旧请求
 * 3. 超时 15 秒自动降级（静默丢弃）
 * 4. 成功后将 AI 建议以 COACH 优先级送入 SpeechBus
 *
 * 纯 Kotlin + Coroutines，零 Android 依赖，可 JVM 单测。
 */
class AnalysisManager(
    private val client: LlmClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 待处理的请求（请求在途时到达的新请求会替换此项） */
    private var pendingRequest: LlmAnalyzeRequest? = null

    /** 是否有请求正在处理中 */
    @Volatile
    private var isProcessing = false

    /** SpeechBus 引用（由外部注入，避免构造循环依赖） */
    var speechBus: SpeechBus? = null

    /**
     * 提交一次 LLM 分析请求
     *
     * 如果当前有请求在处理中，新请求替换排队中的旧请求（最新分段优先）。
     * 调用时机：RunSessionManager 检测到新公里分段时。
     */
    fun analyze(request: LlmAnalyzeRequest) {
        if (isProcessing) {
            // 替换排队中的旧请求（始终取最新分段）
            pendingRequest = request
            Timber.d("LLM: queued request for split %d", request.split_index)
            return
        }
        processRequest(request)
    }

    private fun processRequest(request: LlmAnalyzeRequest) {
        isProcessing = true
        scope.launch {
            try {
                Timber.d("LLM: sending request for split %d", request.split_index)
                val result = withTimeout(TIMEOUT_MS) {
                    client.analyze(request)
                }

                if (result.isSuccess) {
                    val response = result.getOrThrow()
                    Timber.i("LLM: response received: %s", response.speak_text)
                    speechBus?.enqueue(
                        RuleTrigger(
                            ruleId = "llm_coach",
                            severity = Severity.INFO,
                            speakText = response.speak_text,
                            ttsPriority = SpeechPriority.COACH,
                        )
                    )
                } else {
                    Timber.w(result.exceptionOrNull(), "LLM: request failed")
                }
            } catch (e: TimeoutCancellationException) {
                Timber.w("LLM: request timed out after %dms", TIMEOUT_MS)
            } catch (e: Exception) {
                Timber.w(e, "LLM: unexpected error")
            } finally {
                isProcessing = false
                // 处理排队中的请求
                pendingRequest?.let { next ->
                    pendingRequest = null
                    processRequest(next)
                }
            }
        }
    }

    /** 取消所有待处理和进行中的请求 */
    fun cancel() {
        pendingRequest = null
        isProcessing = false
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L
    }
}
