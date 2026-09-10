package com.healthyagent.running.speech

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import java.io.File

class AndroidAudioPlayerPort internal constructor(
    private val platform: AndroidAudioPlayerPlatform,
) : AudioPlayerPort {
    constructor() : this(AndroidAudioPlayerPlatformAdapter(MediaPlayer()))

    override fun start(
        file: File,
        profile: SpeechAudioProfile,
        listener: AudioPlayerPort.Listener,
    ): Result<Unit> = try {
        when (profile) {
            SpeechAudioProfile.ASSISTANT_SPEECH -> platform.setAudioAttributes(
                contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
                usage = AudioAttributes.USAGE_ASSISTANT,
            )
        }
        platform.setDataSource(file.absolutePath)
        platform.setListener(object : AndroidAudioPlayerListener {
            override fun onCompletion() = listener.onCompletion()

            override fun onError(what: Int, extra: Int) = listener.onError(what, extra)
        })
        platform.prepare()
        platform.start()
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    override fun stop() = platform.stop()

    override fun release() = platform.release()
}

internal interface AndroidAudioPlayerPlatform {
    fun setAudioAttributes(contentType: Int, usage: Int)
    fun setDataSource(path: String)
    fun setListener(listener: AndroidAudioPlayerListener)
    fun prepare()
    fun start()
    fun stop()
    fun release()
}

internal interface AndroidAudioPlayerListener {
    fun onCompletion()
    fun onError(what: Int, extra: Int)
}

private class AndroidAudioPlayerPlatformAdapter(
    private val mediaPlayer: MediaPlayer,
) : AndroidAudioPlayerPlatform {
    override fun setAudioAttributes(contentType: Int, usage: Int) {
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(contentType)
                .setUsage(usage)
                .build(),
        )
    }

    override fun setDataSource(path: String) {
        mediaPlayer.setDataSource(path)
    }

    override fun setListener(listener: AndroidAudioPlayerListener) {
        mediaPlayer.setOnCompletionListener { listener.onCompletion() }
        mediaPlayer.setOnErrorListener { _, what, extra ->
            listener.onError(what, extra)
            true
        }
    }

    override fun prepare() = mediaPlayer.prepare()

    override fun start() = mediaPlayer.start()

    override fun stop() = mediaPlayer.stop()

    override fun release() = mediaPlayer.release()
}
