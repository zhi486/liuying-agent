package com.healthyagent.running.speech

import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.AudioFocusResult
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticFailure
import com.healthyagent.running.diagnostics.SafeDiagnosticSummary
import com.healthyagent.running.diagnostics.TtsEventKind
import com.healthyagent.running.diagnostics.TtsProvider
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PreparedVoicePlayer(
    private val audioPlayerFactory: () -> AudioPlayerPort,
    private val audioFocusController: AudioFocusController,
    private val diagnosticLogger: DiagnosticLogger,
    private val playbackTimeoutMillis: Long = 60_000,
) : VoiceFilePlayer {
    private val lock = Any()
    private var active: Operation? = null
    private var closed = false

    override suspend fun prepare(file: File, source: VoiceAudioSource): Result<Unit> {
        currentCoroutineContext().ensureActive()
        val operation = Operation(if (source == VoiceAudioSource.BUNDLED) TtsProvider.BUNDLED else TtsProvider.FISH,
            Job(currentCoroutineContext()[Job]))
        synchronized(lock) {
            if (closed || active != null) {
                operation.preparationJob.complete()
                return Result.failure(IllegalStateException("播放器不可用"))
            }
            active = operation
        }
        record(DiagnosticEvent.TtsProviderEvent(operation.provider, TtsEventKind.ATTEMPTED))
        try {
            val player = audioPlayerFactory()
            synchronized(lock) { operation.player = player }
            currentCoroutineContext().ensureActive()
            operation.terminal.get()?.let { it.result.getOrThrow(); return it.result }
            val prepareMedia: suspend () -> Result<Unit> = { player.prepare(file, SpeechAudioProfile.ASSISTANT_SPEECH, object : AudioPlayerPort.Listener {
                override fun onCompletion() = finish(operation, Outcome(Result.success(Unit), TtsEventKind.COMPLETED))
                override fun onError(what: Int, extra: Int) = finish(operation,
                    Outcome(Result.failure(IllegalStateException("音频播放失败：$what/$extra")), TtsEventKind.FAILED))
            }) }
            val prepared = withContext(operation.preparationJob) {
                // 本地解码复用原有 60 秒故障保护；在线另受 Policy 的 8/15 秒总期限约束。
                if (source == VoiceAudioSource.BUNDLED) withTimeoutOrNull(playbackTimeoutMillis) { prepareMedia() }
                else prepareMedia()
            }
            currentCoroutineContext().ensureActive()
            if (prepared == null) {
                finish(operation, Outcome(Result.failure(TtsInitializationTimeoutException("媒体准备超时")),
                    TtsEventKind.PREPARATION_TIMEOUT))
                return operation.terminal.get()!!.result
            }
            prepared.exceptionOrNull()?.let { throw it }
            synchronized(lock) { operation.prepared = true }
            return operation.terminal.get()?.result ?: Result.success(Unit)
        } catch (error: CancellationException) {
            finish(operation, Outcome(Result.failure(error), TtsEventKind.CANCELLED))
            throw error
        } catch (error: Throwable) {
            finish(operation, Outcome(Result.failure(error), TtsEventKind.FAILED))
            if (error is Error) throw error
            return operation.terminal.get()!!.result
        } finally {
            operation.preparationJob.complete()
            synchronized(lock) { operation.settingUp = false }
            cleanup(operation)
            if (operation.terminal.get() != null) withContext(NonCancellable) { operation.cleanupCompleted.await() }
        }
    }

    override suspend fun play(): Result<Unit> {
        val context = currentCoroutineContext()
        context.ensureActive()
        val operation = synchronized(lock) {
            val candidate = active
            if (closed || candidate == null || !candidate.prepared || candidate.settingUp) {
                return Result.failure(IllegalStateException("音频未准备好"))
            }
            candidate.also { it.settingUp = true }
        }
        try {
            try {
                context[SpeechNoticeToken]?.ensureValid()
                val lease = audioFocusController.request()
                synchronized(lock) { operation.lease = lease }
                record(DiagnosticEvent.AudioFocusEvent(operation.provider,
                    if (lease.granted) AudioFocusResult.GRANTED else AudioFocusResult.DENIED))
                context.ensureActive()
                // 仅实际启动与终态认领共用短门；焦点、prepare 等待和资源释放不占全局状态锁。
                operation.player!!.startPrepared { start ->
                    synchronized(operation.startGate) {
                        context.ensureActive()
                        context[SpeechNoticeToken]?.ensureValid()
                        if (operation.terminal.get() == null) {
                            operation.started = true
                            start()
                            true
                        } else false
                    }
                }.exceptionOrNull()?.let { throw it }
            } finally {
                synchronized(lock) { operation.settingUp = false }
                cleanup(operation)
            }
            val outcome = withTimeoutOrNull(playbackTimeoutMillis) { operation.completion.await() }
            if (outcome == null) finish(operation,
                Outcome(Result.failure(PlaybackTimeoutException()), TtsEventKind.PLAYBACK_TIMEOUT))
            return operation.terminal.get()!!.result.also {
                val error = it.exceptionOrNull()
                if (error is CancellationException) throw error
            }
        } catch (error: CancellationException) {
            finish(operation, Outcome(Result.failure(error), TtsEventKind.CANCELLED))
            throw error
        } catch (error: Throwable) {
            finish(operation, Outcome(Result.failure(error), TtsEventKind.FAILED))
            if (error is Error) throw error
            return operation.terminal.get()!!.result
        } finally {
            cleanup(operation)
            withContext(NonCancellable) { operation.cleanupCompleted.await() }
        }
    }

    override fun stop() {
        val operation = synchronized(lock) { active } ?: return
        finish(operation, Outcome(Result.failure(CancellationException("播放已停止")), TtsEventKind.CANCELLED))
    }

    override fun shutdown() {
        val operation = synchronized(lock) { closed = true; active }
        operation?.let {
            finish(it, Outcome(Result.failure(CancellationException("播放器已关闭")), TtsEventKind.CANCELLED))
        }
    }

    private fun finish(operation: Operation, outcome: Outcome) {
        val claimed = synchronized(operation.startGate) { operation.terminal.compareAndSet(null, outcome) }
        if (claimed) {
            if (outcome.kind == TtsEventKind.CANCELLED) operation.preparationJob.cancel()
            operation.completion.complete(outcome)
            record(DiagnosticEvent.TtsProviderEvent(operation.provider, outcome.kind,
                outcome.result.exceptionOrNull()?.takeUnless { it is CancellationException }?.let {
                    DiagnosticFailure.from(it, when (outcome.kind) {
                        TtsEventKind.PLAYBACK_TIMEOUT -> SafeDiagnosticSummary.TTS_PLAYBACK_TIMEOUT
                        TtsEventKind.PREPARATION_TIMEOUT -> SafeDiagnosticSummary.TTS_INITIALIZATION_FAILED
                        else -> SafeDiagnosticSummary.TTS_PLAYBACK_FAILED
                    })
                }))
            cleanup(operation)
        }
    }

    private fun cleanup(operation: Operation) {
        val resources = synchronized(lock) {
            val outcome = operation.terminal.get() ?: return
            if (operation.settingUp || operation.cleanupClaimed) return
            operation.cleanupClaimed = true
            Triple(operation.player, operation.lease,
                operation.started && outcome.kind in setOf(TtsEventKind.CANCELLED, TtsEventKind.PLAYBACK_TIMEOUT))
        }
        // start 可能同步回调或重入 stop，必须等它返回后在锁外释放。
        try {
            try {
                if (resources.third) resources.first?.let { runCatching { it.stop() } }
            } finally {
                try { resources.first?.let { runCatching { it.release() } } } finally {
                    resources.second?.let {
                        runCatching { it.release() }
                        record(DiagnosticEvent.AudioFocusEvent(operation.provider, AudioFocusResult.RELEASED))
                    }
                }
            }
        } finally {
            synchronized(lock) { if (active === operation) active = null }
            operation.cleanupCompleted.complete(Unit)
        }
    }

    private fun record(event: DiagnosticEvent) { runCatching { diagnosticLogger.record(event) } }

    private data class Outcome(val result: Result<Unit>, val kind: TtsEventKind)
    private class Operation(val provider: TtsProvider, val preparationJob: kotlinx.coroutines.CompletableJob) {
        val startGate = Any()
        val terminal = AtomicReference<Outcome?>(null)
        val completion = CompletableDeferred<Outcome>()
        val cleanupCompleted = CompletableDeferred<Unit>()
        var player: AudioPlayerPort? = null
        var lease: AudioFocusLease? = null
        var settingUp = true
        var prepared = false
        var started = false
        var cleanupClaimed = false
    }
}
