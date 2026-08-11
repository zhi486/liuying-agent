package com.healthyagent.running.ai

/**
 * LLM 运行时配置 — 直连 Anthropic API，支持设置页动态切换 API Key
 */
object LlmConfig {
    /** Anthropic API Key — 设置页配置 */
    @Volatile
    var apiKey: String = ""

    /** Anthropic 模型 ID */
    @Volatile
    var anthropicModel: String = "claude-sonnet-5"
}
