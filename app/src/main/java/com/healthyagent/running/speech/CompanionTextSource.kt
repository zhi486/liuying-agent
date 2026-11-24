package com.healthyagent.running.speech

interface CompanionTextSource {
    /** 公里事件返回已校验的完整播报；其他事件返回核心之后的陪伴句；失败返回 null。 */
    suspend fun create(request: SpeechRequest): String?
}
