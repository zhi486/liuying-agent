package com.healthyagent.running.ai

import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.speech.SpeechBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class PendingAnalysis(
        val request: LlmAnalyzeRequest,
        val generation: Long,
        val speechBus: SpeechBus?,
        val speechSessionGeneration: Long?,
    )

    private val stateLock = Any()

    /** 待处理的请求（请求在途时到达的新请求会替换此项） */
    private var pendingAnalysis: PendingAnalysis? = null

    /** 是否有请求正在处理中 */
    @Volatile
    private var isProcessing = false
    private var generation = 0L
    private var processingJob: Job? = null

    /** SpeechBus 引用（由外部注入，避免构造循环依赖） */
    @Volatile
    var speechBus: SpeechBus? = null

    /**
     * 提交一次 LLM 分析请求
     *
     * 如果当前有请求在处理中，新请求替换排队中的旧请求（最新分段优先）。
     * 调用时机：RunSessionManager 检测到新公里分段时。
     */
    fun analyze(request: LlmAnalyzeRequest) {
        val next = synchronized(stateLock) {
            val activeSpeechBus = speechBus
            val analysis = PendingAnalysis(
                request = request,
                generation = generation,
                speechBus = activeSpeechBus,
                speechSessionGeneration = activeSpeechBus?.sessionGenerationToken(),
            )
            if (isProcessing) {
                // 替换排队中的旧请求（始终取最新分段）
                pendingAnalysis = analysis
                Timber.d("LLM: queued request for split %d", request.split_index)
                null
            } else {
                isProcessing = true
                analysis
            }
        }
        next?.let(::processRequest)
    }

    private fun processRequest(analysis: PendingAnalysis) {
        val request = analysis.request
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                Timber.d("LLM: sending request for split %d", request.split_index)
                val result = withTimeout(TIMEOUT_MS) {
                    client.analyze(request)
                }

                if (result.isSuccess) {
                    if (isCurrentGeneration(analysis.generation)) {
                        val response = result.getOrThrow()
                        Timber.i("LLM: response received: %s", response.speak_text)
                        val speechSessionGeneration = analysis.speechSessionGeneration
                        if (speechSessionGeneration != null) {
                            analysis.speechBus?.enqueue(
                                RuleTrigger(
                                    ruleId = "llm_coach",
                                    severity = Severity.INFO,
                                    speakText = response.speak_text,
                                    ttsPriority = SpeechPriority.COACH,
                                ),
                                speechSessionGeneration,
                            )
                        }
                    }
                } else {
                    Timber.w(result.exceptionOrNull(), "LLM: request failed")
                }
            } catch (e: TimeoutCancellationException) {
                Timber.w("LLM: request timed out after %dms", TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "LLM: unexpected error")
            } finally {
                completeAnalysis(analysis.generation)
            }
        }
        val shouldStart = synchronized(stateLock) {
            if (analysis.generation == generation && isProcessing) {
                processingJob = job
                true
            } else {
                false
            }
        }
        if (shouldStart) job.start() else job.cancel()
    }

    /** 取消所有待处理和进行中的请求 */
    fun cancel() {
        val job = synchronized(stateLock) {
            generation++
            pendingAnalysis = null
            isProcessing = false
            processingJob.also { processingJob = null }
        }
        job?.cancel()
    }

    private fun isCurrentGeneration(expected: Long): Boolean = synchronized(stateLock) {
        generation == expected
    }

    private fun completeAnalysis(completedGeneration: Long) {
        val next = synchronized(stateLock) {
            if (generation != completedGeneration) return
            processingJob = null
            isProcessing = false
            pendingAnalysis
                ?.takeIf { it.generation == completedGeneration }
                ?.also {
                    pendingAnalysis = null
                    isProcessing = true
                }
        }
        next?.let(::processRequest)
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L
    }
}
