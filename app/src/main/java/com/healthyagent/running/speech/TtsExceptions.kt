package com.healthyagent.running.speech

class TtsInitializationTimeoutException(
    message: String = "语音初始化超时",
) : Exception(message)

class PlaybackTimeoutException(
    message: String = "语音播放超时",
) : Exception(message)
