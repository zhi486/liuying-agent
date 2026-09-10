package com.healthyagent.running.speech

import android.content.Context
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.diagnostics.AudioFocusResult
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticFailure
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.NoOpDiagnosticLogger
import com.healthyagent.running.diagnostics.SafeDiagnosticSummary
import com.healthyagent.running.diagnostics.TtsEventKind
import com.healthyagent.running.diagnostics.TtsProvider
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class AndroidTtsEngine(
    private val port: TextToSpeechPort,
    private val audioFocusController: AudioFocusController,
    private val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val initializationTimeoutMillis: Long = 3_000L,
    private val playbackTimeoutMillis: Long = 60_000L,
    private val newUtteranceId: () -> String = { UUID.randomUUID().toString() },
) : TtsEngine {
    private sealed interface Terminal {
        data object Completed : Terminal
        data class Failed(val error: Throwable) : Terminal
        data class TimedOut(val error: PlaybackTimeoutException) : Terminal
        data class Cancelled(val error: CancellationException) : Terminal
    }

    private data class Lease(
        val value: AudioFocusLease,
        val recordsRelease: Boolean,
    )

    private class Playback(
        val utteranceId: String,
        val lease: Lease,
    ) {
        val claimedTerminal = AtomicReference<Terminal?>(null)
        val terminal = CompletableDeferred<Terminal>()
    }

    private val stateLock = Any()
    private var activePlayback: Playback? = null

    @Volatile
    override var isSpeaking: Boolean = false
        private set

    init {
        port.setListener(object : TextToSpeechPort.Listener {
            override fun onStart(utteranceId: String) {
                synchronized(stateLock) {
                    if (activePlayback?.utteranceId == utteranceId) {
                        isSpeaking = true
                    }
                }
            }

            override fun onDone(utteranceId: String) {
                playbackFor(utteranceId)?.let { finish(it, Terminal.Completed) }
            }

            override fun onError(utteranceId: String, errorCode: Int?) {
                playbackFor(utteranceId)?.let {
                    finish(it, Terminal.Failed(IllegalStateException("TTS 播放失败，错误码：$errorCode")))
                }
            }

            override fun onStop(utteranceId: String) {
                playbackFor(utteranceId)?.let {
                    finish(it, Terminal.Cancelled(CancellationException("TTS 播放已停止")))
                }
            }
        })
    }

    constructor(context: Context) : this(
        port = AndroidTextToSpeechPort(context),
        audioFocusController = AndroidAudioFocusController(context),
    )

    override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> {
        val initialization = try {
            withTimeoutOrNull(initializationTimeoutMillis) {
                port.awaitInitialization()
            }
        } catch (cancelled: CancellationException) {
            recordCancellation()
            throw cancelled
        } catch (error: Exception) {
            return initializationFailure(error)
        }

        if (initialization == null) {
            return initializationFailure(TtsInitializationTimeoutException())
        }
        initialization.exceptionOrNull()?.let { error ->
            if (error is CancellationException) {
                recordCancellation()
                throw error
            }
            if (error !is Exception) throw error
            return initializationFailure(error)
        }

        val profile = try {
            port.setAudioProfile(SpeechAudioProfile.ASSISTANT_SPEECH)
        } catch (cancelled: CancellationException) {
            recordCancellation()
            throw cancelled
        } catch (error: Exception) {
            return playbackFailure(error)
        }
        profile.exceptionOrNull()?.let { error ->
            if (error is CancellationException) {
                recordCancellation()
                throw error
            }
            if (error !is Exception) throw error
            return playbackFailure(error)
        }

        val lease = try {
            val acquired = audioFocusController.request()
            recordFocus(if (acquired.granted) AudioFocusResult.GRANTED else AudioFocusResult.DENIED)
            Lease(acquired, recordsRelease = true)
        } catch (cancelled: CancellationException) {
            recordCancellation()
            throw cancelled
        } catch (_: Exception) {
            recordFocus(AudioFocusResult.DENIED)
            Lease(NoOpAudioFocusLease, recordsRelease = false)
        }

        val playback = Playback(newUtteranceId(), lease)
        val installed = synchronized(stateLock) {
            if (activePlayback == null) {
                activePlayback = playback
                isSpeaking = true
                true
            } else {
                false
            }
        }
        if (!installed) {
            val error = IllegalStateException("TTS 正在播放")
            finish(playback, Terminal.Failed(error))
            return Result.failure(error)
        }

        val accepted = try {
            port.speak(text, playback.utteranceId)
        } catch (cancelled: CancellationException) {
            finish(playback, Terminal.Cancelled(cancelled))
            throw cancelled
        } catch (error: Exception) {
            finish(playback, Terminal.Failed(error))
            return Result.failure(error)
        } catch (error: Error) {
            finish(playback, Terminal.Failed(error))
            throw error
        }
        if (!accepted) {
            val error = IllegalStateException("TTS 拒绝播放请求")
            finish(playback, Terminal.Failed(error))
            return Result.failure(error)
        }

        return try {
            val terminal = withTimeoutOrNull(playbackTimeoutMillis) {
                playback.terminal.await()
            }
            if (terminal == null) {
                val timeout = PlaybackTimeoutException()
                if (finish(playback, Terminal.TimedOut(timeout), stopPort = true)) {
                    Result.failure(timeout)
                } else {
                    checkNotNull(playback.claimedTerminal.get()).toResult()
                }
            } else {
                terminal.toResult()
            }
        } catch (cancelled: CancellationException) {
            finish(playback, Terminal.Cancelled(cancelled), stopPort = true)
            throw cancelled
        }
    }

    override fun stop() {
        val playback = synchronized(stateLock) { activePlayback }
        if (playback == null || !finish(
                playback,
                Terminal.Cancelled(CancellationException("TTS 引擎已停止")),
                stopPort = true,
            )
        ) {
            safeStop()
        }
    }

    override fun shutdown() {
        val playback = synchronized(stateLock) { activePlayback }
        if (playback == null || !finish(
                playback,
                Terminal.Cancelled(CancellationException("TTS 引擎已关闭")),
                stopPort = true,
            )
        ) {
            safeStop()
        }
        try {
            port.shutdown()
        } catch (_: Exception) {
            // shutdown is best-effort; an active request has already reached its terminal state.
        }
    }

    private fun playbackFor(utteranceId: String): Playback? = synchronized(stateLock) {
        activePlayback?.takeIf { it.utteranceId == utteranceId }
    }

    private fun finish(
        playback: Playback,
        terminal: Terminal,
        stopPort: Boolean = false,
    ): Boolean {
        if (!playback.claimedTerminal.compareAndSet(null, terminal)) return false
        if (stopPort) safeStop()
        synchronized(stateLock) {
            if (activePlayback === playback) {
                activePlayback = null
                isSpeaking = false
            }
        }
        try {
            playback.lease.value.release()
        } catch (_: Exception) {
            // The terminal result must not be stranded by platform focus cleanup.
        }
        if (playback.lease.recordsRelease) {
            recordFocus(AudioFocusResult.RELEASED)
        }
        recordTerminal(terminal)
        playback.terminal.complete(terminal)
        return true
    }

    private fun Terminal.toResult(): Result<Unit> = when (this) {
        Terminal.Completed -> Result.success(Unit)
        is Terminal.Failed -> Result.failure(error)
        is Terminal.TimedOut -> Result.failure(error)
        is Terminal.Cancelled -> throw error
    }

    private fun initializationFailure(error: Throwable): Result<Unit> {
        diagnosticLogger.record(
            DiagnosticEvent.TtsProviderEvent(
                provider = TtsProvider.ANDROID,
                kind = TtsEventKind.INITIALIZATION_FAILED,
                failure = DiagnosticFailure.from(error, SafeDiagnosticSummary.TTS_INITIALIZATION_FAILED),
            ),
        )
        return Result.failure(error)
    }

    private fun playbackFailure(error: Throwable): Result<Unit> {
        diagnosticLogger.record(
            DiagnosticEvent.TtsProviderEvent(
                provider = TtsProvider.ANDROID,
                kind = TtsEventKind.FAILED,
                failure = DiagnosticFailure.from(error, SafeDiagnosticSummary.TTS_PLAYBACK_FAILED),
            ),
        )
        return Result.failure(error)
    }

    private fun recordTerminal(terminal: Terminal) {
        val event = when (terminal) {
            Terminal.Completed -> DiagnosticEvent.TtsProviderEvent(
                provider = TtsProvider.ANDROID,
                kind = TtsEventKind.COMPLETED,
            )
            is Terminal.Failed -> DiagnosticEvent.TtsProviderEvent(
                provider = TtsProvider.ANDROID,
                kind = TtsEventKind.FAILED,
                failure = DiagnosticFailure.from(
                    terminal.error,
                    SafeDiagnosticSummary.TTS_PLAYBACK_FAILED,
                ),
            )
            is Terminal.TimedOut -> DiagnosticEvent.TtsProviderEvent(
                provider = TtsProvider.ANDROID,
                kind = TtsEventKind.PLAYBACK_TIMEOUT,
                failure = DiagnosticFailure.from(
                    terminal.error,
                    SafeDiagnosticSummary.TTS_PLAYBACK_TIMEOUT,
                ),
            )
            is Terminal.Cancelled -> DiagnosticEvent.TtsProviderEvent(
                provider = TtsProvider.ANDROID,
                kind = TtsEventKind.CANCELLED,
            )
        }
        diagnosticLogger.record(event)
    }

    private fun recordCancellation() {
        diagnosticLogger.record(
            DiagnosticEvent.TtsProviderEvent(
                provider = TtsProvider.ANDROID,
                kind = TtsEventKind.CANCELLED,
            ),
        )
    }

    private fun recordFocus(result: AudioFocusResult) {
        diagnosticLogger.record(DiagnosticEvent.AudioFocusEvent(TtsProvider.ANDROID, result))
    }

    private fun safeStop() {
        try {
            port.stop()
        } catch (_: Exception) {
            // stop is best-effort; the completion latch owns the observable terminal state.
        }
    }

    private object NoOpAudioFocusLease : AudioFocusLease {
        override val granted: Boolean = false
        override fun release() = Unit
    }
}
