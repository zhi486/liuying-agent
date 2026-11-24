package com.healthyagent.running.agent.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** 原生直录 16kHz、单声道、16bit 小端 PCM，不落盘、不依赖 ffmpeg。 */
class AndroidVoiceRecorder : VoiceRecorder, PcmStreamer {
    override suspend fun record(stop: AtomicBoolean): ByteArray {
        val output = ByteArray(960000)
        var size = 0
        try {
            record(stop) { chunk -> chunk.copyInto(output, size); size += chunk.size }
            return output.copyOf(size)
        } finally { output.fill(0) }
    }
    @SuppressLint("MissingPermission")
    override suspend fun record(stop: AtomicBoolean, chunk: suspend (ByteArray) -> Unit): Unit = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0)
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 8192))
        val buffer = ByteArray(4096)
        var length = 0
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            ensureActive(); recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            val started = SystemClock.elapsedRealtime()
            while (!stop.get() && length < 960000 && SystemClock.elapsedRealtime() - started < 30000) {
                ensureActive()
                val read = recorder.read(buffer, 0, minOf(4096, 960000 - length), AudioRecord.READ_NON_BLOCKING)
                check(read >= 0 && read % 2 == 0)
                length += read
                if (read > 0) {
                    val frame = buffer.copyOf(read)
                    try { chunk(frame) } finally { frame.fill(0) }
                }
                delay(100)
            }
            ensureActive()
        } finally {
            runCatching { recorder.stop() }; recorder.release(); buffer.fill(0)
        }
    }
}
