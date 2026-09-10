package com.healthyagent.running.speech

import java.io.File

enum class VoiceAudioSource { BUNDLED, ONLINE }

interface BundledVoiceStore {
    suspend fun open(ruleId: String): File?
}

interface VoiceFilePlayer {
    suspend fun play(file: File, source: VoiceAudioSource): Result<Unit>
    fun stop()
    fun shutdown()
}
