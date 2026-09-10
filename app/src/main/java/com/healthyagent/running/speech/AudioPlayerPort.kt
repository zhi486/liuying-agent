package com.healthyagent.running.speech

import java.io.File

interface AudioPlayerPort {
    fun start(
        file: File,
        profile: SpeechAudioProfile,
        listener: Listener,
    ): Result<Unit>

    fun stop()
    fun release()

    interface Listener {
        fun onCompletion()
        fun onError(what: Int, extra: Int)
    }
}
