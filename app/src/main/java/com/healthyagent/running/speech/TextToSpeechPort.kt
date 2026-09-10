package com.healthyagent.running.speech

interface TextToSpeechPort {
    suspend fun awaitInitialization(): Result<Unit>
    fun setAudioProfile(profile: SpeechAudioProfile): Result<Unit>
    fun setListener(listener: Listener)
    fun speak(text: String, utteranceId: String): Boolean
    fun stop()
    fun shutdown()

    interface Listener {
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String, errorCode: Int?)
        fun onStop(utteranceId: String)
    }
}
