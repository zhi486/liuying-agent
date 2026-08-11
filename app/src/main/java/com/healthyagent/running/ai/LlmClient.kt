package com.healthyagent.running.ai

/**
 * LLM 客户端接口 — 封装网络调用，返回 Result 以优雅降级
 */
interface LlmClient {
    suspend fun analyze(request: LlmAnalyzeRequest): Result<LlmAnalyzeResponse>
}
