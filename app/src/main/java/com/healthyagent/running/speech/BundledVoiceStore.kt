package com.healthyagent.running.speech

import java.io.File

enum class VoiceAudioSource { BUNDLED, ONLINE }

interface BundledVoiceStore {
    suspend fun open(ruleId: String): File?
}

interface VoiceFilePlayer {
    suspend fun prepare(file: File, source: VoiceAudioSource): Result<Unit>
    suspend fun play(): Result<Unit>
    suspend fun play(file: File, source: VoiceAudioSource): Result<Unit> {
        val prepared = prepare(file, source)
        return if (prepared.isSuccess) play() else prepared
    }
    fun stop()
    fun shutdown()
}
