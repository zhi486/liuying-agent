package com.healthyagent.running.speech

interface AudioFocusController {
    fun request(): AudioFocusLease
}

interface AudioFocusLease {
    val granted: Boolean
    fun release()
}
