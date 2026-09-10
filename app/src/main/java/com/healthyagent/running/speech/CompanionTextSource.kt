package com.healthyagent.running.speech

interface CompanionTextSource {
    suspend fun create(request: SpeechRequest): String?
}
