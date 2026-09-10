package com.healthyagent.running.speech

import java.io.File

interface FishSynthesisClient {
    suspend fun synthesize(text: String): File
    fun cancel()
    fun shutdown()
}
