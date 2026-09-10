package com.healthyagent.running.speech

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

class AndroidAudioPlayerPort internal constructor(
    private val platform: AndroidAudioPlayerPlatform,
    private val mainHandler: Handler? = null,
) : AudioPlayerPort {
    constructor() : this(AndroidAudioPlayerPlatformAdapter(), Handler(Looper.getMainLooper()))

    // 真实媒体只在主 Looper 创建和操作；切线程时不持实例锁或上层启动门。
    private val lock = Any()
    private var valid = true
    private var prepared = false
    private var started = false
    private var released = false
    private var preparation: CompletableDeferred<Unit>? = null

    override suspend fun prepare(
        file: File,
        profile: SpeechAudioProfile,
        listener: AudioPlayerPort.Listener,
    ): Result<Unit> {
        val ready = CompletableDeferred<Unit>()
        return try {
            onOwnerThread { synchronized(lock) {
                check(valid && preparation == null) { "媒体端口不可用" }
                preparation = ready
                configure(file, profile, listener) {
                    val accepted = synchronized(lock) {
                        if (!valid) false else { prepared = true; true }
                    }
                    if (accepted) ready.complete(Unit)
                }
                platform.prepareAsync()
            } }
            ready.await()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            stop()
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun startPrepared(startIfValid: (() -> Unit) -> Boolean): Result<Unit> = onOwnerThread { platformResult {
        synchronized(lock) {
            check(valid && prepared && !started) { "媒体未准备好或已取消" }
            if (!startIfValid {
                started = true
                platform.start()
            }) throw CancellationException("媒体启动已取消")
        }
    } }

    override fun start(
        file: File,
        profile: SpeechAudioProfile,
        listener: AudioPlayerPort.Listener,
    ): Result<Unit> = onOwnerThreadBlocking { platformResult {
        synchronized(lock) {
            check(valid) { "媒体端口已取消" }
            configure(file, profile, listener) {
                val result = synchronized(lock) {
                    if (!valid) return@configure
                    prepared = true
                    platformResult { started = true; platform.start() }
                }
                result.exceptionOrNull()?.let { listener.onError(-1, 0) }
            }
            platform.prepareAsync()
        }
    } }

    private fun configure(file: File, profile: SpeechAudioProfile, listener: AudioPlayerPort.Listener, onPrepared: () -> Unit) {
        when (profile) {
            SpeechAudioProfile.ASSISTANT_SPEECH -> platform.setAudioAttributes(
                contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
                usage = AudioAttributes.USAGE_ASSISTANT,
            )
        }
        platform.setDataSource(file.absolutePath)
        platform.setListener(object : AndroidAudioPlayerListener {
            override fun onPrepared() = onPrepared()
            override fun onCompletion() = listener.onCompletion()

            override fun onError(what: Int, extra: Int) {
                val pending = synchronized(lock) { preparation?.takeUnless { prepared } }
                if (pending != null) pending.completeExceptionally(IllegalStateException("媒体准备失败：$what/$extra"))
                else listener.onError(what, extra)
            }
        })
    }

    private inline fun platformResult(block: () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    override fun stop(): Unit = onOwnerThreadBlocking {
        val pending = synchronized(lock) {
            if (!valid) return@onOwnerThreadBlocking
            valid = false
            if (started) platform.stop()
            preparation
        }
        pending?.completeExceptionally(CancellationException("媒体准备已取消"))
        Unit
    }

    override fun release() = onOwnerThreadBlocking {
        synchronized(lock) {
            if (released) return@onOwnerThreadBlocking
            released = true
            valid = false
            try { platform.release() } finally {
                preparation?.completeExceptionally(CancellationException("媒体端口已释放"))
            }
        }
    }

    private suspend fun <T> onOwnerThread(block: () -> T): T =
        if (mainHandler == null) block() else withContext(Dispatchers.Main.immediate) { block() }

    // stop/release 返回即表示真实平台操作结束；主线程从不等待自己投递的任务。
    private fun <T> onOwnerThreadBlocking(block: () -> T): T {
        val handler = mainHandler
        if (handler == null || Looper.myLooper() === handler.looper) return block()
        val task = FutureTask(block)
        check(handler.post(task)) { "主线程媒体调度不可用" }
        try { return task.get() } catch (error: ExecutionException) { throw error.cause ?: error }
    }
}

internal interface AndroidAudioPlayerPlatform {
    fun setAudioAttributes(contentType: Int, usage: Int)
    fun setDataSource(path: String)
    fun setListener(listener: AndroidAudioPlayerListener)
    fun prepareAsync()
    fun start()
    fun stop()
    fun release()
}

internal interface AndroidAudioPlayerListener {
    fun onPrepared()
    fun onCompletion()
    fun onError(what: Int, extra: Int)
}

private class AndroidAudioPlayerPlatformAdapter : AndroidAudioPlayerPlatform {
    private val mediaPlayer by lazy { MediaPlayer() }
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
        mediaPlayer.setOnPreparedListener { listener.onPrepared() }
        mediaPlayer.setOnCompletionListener { listener.onCompletion() }
        mediaPlayer.setOnErrorListener { _, what, extra ->
            listener.onError(what, extra)
            true
        }
    }

    override fun prepareAsync() = mediaPlayer.prepareAsync()

    override fun start() = mediaPlayer.start()

    override fun stop() = mediaPlayer.stop()

    override fun release() = mediaPlayer.release()
}
