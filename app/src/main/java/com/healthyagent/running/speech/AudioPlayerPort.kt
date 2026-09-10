package com.healthyagent.running.speech

import java.io.File

interface AudioPlayerPort {
    /** 完成媒体准备但不启动；旧试听端口必须显式支持此合同才能进入正式播放链路。 */
    suspend fun prepare(file: File, profile: SpeechAudioProfile, listener: Listener): Result<Unit> =
        Result.failure(UnsupportedOperationException("端口不支持独立媒体准备"))

    /** 在端口所属线程内通过短认领门执行 start；门拒绝时不得调用平台。 */
    suspend fun startPrepared(startIfValid: (() -> Unit) -> Boolean = { start -> start(); true }): Result<Unit> =
        Result.failure(UnsupportedOperationException("端口不支持独立启动"))

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
