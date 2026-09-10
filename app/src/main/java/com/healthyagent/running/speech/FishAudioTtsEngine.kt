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
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

class FishAudioTtsEngine(
    private val synthesisClient: FishSynthesisClient,
    private val audioPlayerFactory: () -> AudioPlayerPort,
    private val audioFocusController: AudioFocusController,
    private val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val playbackTimeoutMillis: Long = 60_000L,
) : TtsEngine {
    private sealed interface Terminal {
        data object Completed : Terminal
        data class Failed(
            val error: Throwable,
            val summary: SafeDiagnosticSummary,
        ) : Terminal
        data class TimedOut(val error: PlaybackTimeoutException) : Terminal
        data class Cancelled(val error: CancellationException) : Terminal
    }

    private data class Lease(
        val value: AudioFocusLease,
        val recordsRelease: Boolean,
    )

    private data class CleanupPlan(
        val player: AudioPlayerPort?,
        val lease: Lease?,
        val stopPlayer: Boolean,
    )

    private sealed interface SynthesisOutcome {
        data class Succeeded(val file: File) : SynthesisOutcome
        data class Failed(val error: Throwable) : SynthesisOutcome
    }

    private sealed interface SynthesisCancelState {
        data object Idle : SynthesisCancelState
        data class Running(
            val owner: Thread,
            val completed: CountDownLatch = CountDownLatch(1),
        ) : SynthesisCancelState
        data class Completed(val error: Error?) : SynthesisCancelState
    }

    private class Operation {
        val claimedTerminal = AtomicReference<Terminal?>(null)
        val terminal = CompletableDeferred<Terminal>()
        val synthesisFinished = AtomicBoolean(false)
        val synthesisCancelState =
            AtomicReference<SynthesisCancelState>(SynthesisCancelState.Idle)
        val resourceLock = Any()
        var player: AudioPlayerPort? = null
        var lease: Lease? = null
        var startInProgress = false
        var pendingStopPlayer = false
        var cleanupError: Error? = null
    }

    private val stateLock = Any()
    private val closed = AtomicBoolean(false)
    private var activeOperation: Operation? = null

    @Volatile
    override var isSpeaking: Boolean = false
        private set

    constructor(apiKey: String, referenceId: String, context: Context) : this(
        synthesisClient = OkHttpFishSynthesisClient(
            apiKey = apiKey,
            referenceId = referenceId,
            cacheDirectory = File(context.cacheDir, "tts_cache"),
        ),
        audioPlayerFactory = { AndroidAudioPlayerPort() },
        audioFocusController = AndroidAudioFocusController(context),
    )

    override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> {
        recordProvider(TtsEventKind.ATTEMPTED)
        val operation = Operation()
        val installed = synchronized(stateLock) {
            if (!closed.get() && activeOperation == null) {
                activeOperation = operation
                true
            } else {
                false
            }
        }
        if (!installed) {
            val error = IllegalStateException("Fish TTS 不可用或正在播放")
            recordFailure(error, SafeDiagnosticSummary.TTS_PLAYBACK_FAILED)
            return Result.failure(error)
        }

        var failureSummary = SafeDiagnosticSummary.FISH_SYNTHESIS_FAILED
        return try {
            val audioFile = when (val outcome = awaitSynthesis(operation, text)) {
                is SynthesisOutcome.Succeeded -> outcome.file
                is SynthesisOutcome.Failed -> throw outcome.error
            }
            failureSummary = SafeDiagnosticSummary.TTS_PLAYBACK_FAILED
            throwIfTerminalClaimed(operation)

            val lease = requestFocus()
            if (!attachLease(operation, lease)) {
                cleanupDetached(operation, CleanupPlan(null, lease, stopPlayer = false))
                return claimedResult(operation)
            }

            throwIfTerminalClaimed(operation)
            val player = audioPlayerFactory()
            if (!attachPlayer(operation, player)) {
                cleanupDetached(operation, CleanupPlan(player, null, stopPlayer = false))
                return claimedResult(operation)
            }

            if (!beginStart(operation)) return claimedResult(operation)
            val startResult = try {
                player.start(
                    file = audioFile,
                    profile = SpeechAudioProfile.ASSISTANT_SPEECH,
                    listener = listenerFor(operation),
                )
            } finally {
                endStart(operation)
            }

            startResult.exceptionOrNull()?.let { error ->
                when (error) {
                    is CancellationException -> {
                        if (finish(operation, Terminal.Cancelled(error), stopPlayer = true)) {
                            throw error
                        }
                    }
                    is Exception -> {
                        if (finish(
                                operation,
                                Terminal.Failed(error, SafeDiagnosticSummary.TTS_PLAYBACK_FAILED),
                            )
                        ) {
                            return Result.failure(error)
                        }
                    }
                    else -> {
                        if (finish(
                                operation,
                                Terminal.Failed(error, SafeDiagnosticSummary.TTS_PLAYBACK_FAILED),
                            )
                        ) {
                            throw error
                        }
                    }
                }
                return claimedResult(operation)
            }

            awaitPlayback(operation)
        } catch (cancelled: CancellationException) {
            if (finish(operation, Terminal.Cancelled(cancelled), stopPlayer = true)) {
                throw cancelled
            }
            claimedResult(operation)
        } catch (error: Exception) {
            if (finish(operation, Terminal.Failed(error, failureSummary))) {
                Result.failure(error)
            } else {
                claimedResult(operation)
            }
        } catch (error: Error) {
            cleanupErrorFor(operation, error)?.let { throw it }
            val won = try {
                finish(operation, Terminal.Failed(error, failureSummary))
            } catch (cleanupError: Error) {
                if (cleanupError !== error) error.addSuppressed(cleanupError)
                throw error
            }
            if (won) throw error
            claimedResult(operation)
        }
    }

    override fun stop() {
        val operation = synchronized(stateLock) { activeOperation }
        if (operation != null) {
            finish(
                operation,
                Terminal.Cancelled(CancellationException("Fish TTS 引擎已停止")),
                stopPlayer = true,
            )
        }
    }

    override fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        var firstError: Error? = null
        val operation = synchronized(stateLock) { activeOperation }
        if (operation != null) {
            try {
                finish(
                    operation,
                    Terminal.Cancelled(CancellationException("Fish TTS 引擎已关闭")),
                    stopPlayer = true,
                )
            } catch (error: Error) {
                firstError = mergeErrors(firstError, error)
            }
        }
        try {
            synthesisClient.shutdown()
        } catch (_: Exception) {
            // shutdown 是尽力清理；活跃请求已先进入终态。
        } catch (error: Error) {
            firstError = mergeErrors(firstError, error)
        }
        firstError?.let { throw it }
    }

    private suspend fun awaitSynthesis(
        operation: Operation,
        text: String,
    ): SynthesisOutcome = supervisorScope {
        val synthesis = async {
            try {
                SynthesisOutcome.Succeeded(synthesisClient.synthesize(text)).also {
                    operation.synthesisFinished.set(true)
                }
            } catch (error: Throwable) {
                if (error is CancellationException && !currentCoroutineContext().isActive) {
                    throw error
                }
                SynthesisOutcome.Failed(error)
            }
        }
        try {
            select<SynthesisOutcome> {
                synthesis.onAwait { it }
                operation.terminal.onAwait { it.raise() }
            }
        } finally {
            if (!synthesis.isCompleted) {
                cancelSynthesisOnce(operation)
                synthesis.cancel()
            }
        }
    }

    private suspend fun awaitPlayback(operation: Operation): Result<Unit> {
        val terminal = withTimeoutOrNull(playbackTimeoutMillis) {
            operation.terminal.await()
        }
        if (terminal != null) return terminal.toResult()

        val timeout = PlaybackTimeoutException()
        return if (finish(operation, Terminal.TimedOut(timeout), stopPlayer = true)) {
            Result.failure(timeout)
        } else {
            claimedResult(operation)
        }
    }

    private fun listenerFor(operation: Operation) = object : AudioPlayerPort.Listener {
        override fun onCompletion() {
            finish(operation, Terminal.Completed)
        }

        override fun onError(what: Int, extra: Int) {
            finish(
                operation,
                Terminal.Failed(
                    IOException("Fish TTS 播放失败，what=$what extra=$extra"),
                    SafeDiagnosticSummary.TTS_PLAYBACK_FAILED,
                ),
            )
        }
    }

    private fun requestFocus(): Lease = try {
        val lease = audioFocusController.request()
        recordFocus(if (lease.granted) AudioFocusResult.GRANTED else AudioFocusResult.DENIED)
        Lease(lease, recordsRelease = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        recordFocus(AudioFocusResult.DENIED)
        Lease(NoOpAudioFocusLease, recordsRelease = false)
    }

    private fun attachLease(operation: Operation, lease: Lease): Boolean =
        synchronized(operation.resourceLock) {
            if (operation.claimedTerminal.get() != null) {
                false
            } else {
                operation.lease = lease
                true
            }
        }

    private fun attachPlayer(operation: Operation, player: AudioPlayerPort): Boolean =
        synchronized(operation.resourceLock) {
            if (operation.claimedTerminal.get() != null) {
                false
            } else {
                operation.player = player
                true
            }
        }

    private fun beginStart(operation: Operation): Boolean = synchronized(operation.resourceLock) {
        if (operation.claimedTerminal.get() != null) {
            false
        } else {
            operation.startInProgress = true
            isSpeaking = true
            true
        }
    }

    private fun endStart(operation: Operation) {
        val cleanup = synchronized(operation.resourceLock) {
            operation.startInProgress = false
            if (operation.claimedTerminal.get() == null) {
                null
            } else {
                takeCleanupLocked(operation)
            }
        }
        if (cleanup != null) {
            val cleanupError = cleanupAndClear(operation, cleanup)
            if (cleanupError != null) throw rememberCleanupError(operation, cleanupError)
        }
    }

    private fun finish(
        operation: Operation,
        terminal: Terminal,
        stopPlayer: Boolean = false,
    ): Boolean {
        val cleanup = synchronized(operation.resourceLock) {
            if (!operation.claimedTerminal.compareAndSet(null, terminal)) return false
            if (stopPlayer) operation.pendingStopPlayer = true
            if (operation.startInProgress) null else takeCleanupLocked(operation)
        }
        var firstError = if (terminal is Terminal.Cancelled && !operation.synthesisFinished.get()) {
            cancelSynthesisOnce(operation)
        } else {
            null
        }

        val resourceError = if (cleanup == null) {
            synchronized(stateLock) {
                if (activeOperation === operation) isSpeaking = false
            }
            null
        } else {
            cleanupAndClear(operation, cleanup)
        }
        if (resourceError != null) firstError = mergeErrors(firstError, resourceError)
        recordTerminal(terminal)
        operation.terminal.complete(terminal)
        if (firstError != null) throw rememberCleanupError(operation, firstError)
        return true
    }

    private fun takeCleanupLocked(operation: Operation): CleanupPlan {
        val cleanup = CleanupPlan(
            player = operation.player,
            lease = operation.lease,
            stopPlayer = operation.pendingStopPlayer,
        )
        operation.player = null
        operation.lease = null
        operation.pendingStopPlayer = false
        return cleanup
    }

    private fun cleanupAndClear(operation: Operation, cleanup: CleanupPlan): Error? = try {
        cleanupResources(cleanup)
    } finally {
        clearActive(operation)
    }

    private fun cleanupDetached(operation: Operation, cleanup: CleanupPlan) {
        val error = cleanupResources(cleanup)
        if (error != null) throw rememberCleanupError(operation, error)
    }

    private fun cleanupResources(cleanup: CleanupPlan): Error? {
        var firstError: Error? = null
        if (cleanup.stopPlayer && cleanup.player != null) {
            firstError = captureCleanupError(firstError) { cleanup.player.stop() }
        }
        if (cleanup.player != null) {
            firstError = captureCleanupError(firstError) { cleanup.player.release() }
        }
        if (cleanup.lease != null) {
            firstError = captureCleanupError(firstError) { cleanup.lease.value.release() }
            if (cleanup.lease.recordsRelease) {
                firstError = captureCleanupError(firstError) {
                    recordFocus(AudioFocusResult.RELEASED)
                }
            }
        }
        return firstError
    }

    private fun clearActive(operation: Operation) {
        synchronized(stateLock) {
            if (activeOperation === operation) {
                activeOperation = null
                isSpeaking = false
            }
        }
    }

    private inline fun captureCleanupError(first: Error?, action: () -> Unit): Error? = try {
        action()
        first
    } catch (_: Exception) {
        first
    } catch (error: Error) {
        mergeErrors(first, error)
    }

    private fun mergeErrors(first: Error?, next: Error): Error {
        if (first == null) return next
        if (first !== next && first.suppressed.none { it === next }) first.addSuppressed(next)
        return first
    }

    private fun rememberCleanupError(operation: Operation, error: Error): Error =
        synchronized(operation.resourceLock) {
            mergeErrors(operation.cleanupError, error).also { operation.cleanupError = it }
        }

    private fun cleanupErrorFor(operation: Operation, error: Error): Error? =
        synchronized(operation.resourceLock) {
            operation.cleanupError?.takeIf { it === error }
        }

    private fun throwIfTerminalClaimed(operation: Operation) {
        operation.claimedTerminal.get()?.raise()
    }

    private fun claimedResult(operation: Operation): Result<Unit> =
        checkNotNull(operation.claimedTerminal.get()).toResult()

    private fun Terminal.toResult(): Result<Unit> = when (this) {
        Terminal.Completed -> Result.success(Unit)
        is Terminal.Failed -> Result.failure(error)
        is Terminal.TimedOut -> Result.failure(error)
        is Terminal.Cancelled -> throw error
    }

    private fun Terminal.raise(): Nothing = when (this) {
        Terminal.Completed -> error("合成阶段不应出现播放完成终态")
        is Terminal.Failed -> throw error
        is Terminal.TimedOut -> throw error
        is Terminal.Cancelled -> throw error
    }

    private fun cancelSynthesisOnce(operation: Operation): Error? {
        while (true) {
            when (val state = operation.synthesisCancelState.get()) {
                SynthesisCancelState.Idle -> {
                    val running = SynthesisCancelState.Running(Thread.currentThread())
                    if (!operation.synthesisCancelState.compareAndSet(state, running)) continue

                    var error: Error? = null
                    try {
                        error = captureCleanupError(null) { synthesisClient.cancel() }
                        return error
                    } finally {
                        operation.synthesisCancelState.set(SynthesisCancelState.Completed(error))
                        running.completed.countDown()
                    }
                }
                is SynthesisCancelState.Running -> {
                    if (state.owner === Thread.currentThread()) return null
                    awaitUninterruptibly(state.completed)
                }
                is SynthesisCancelState.Completed -> return state.error
            }
        }
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun recordTerminal(terminal: Terminal) {
        when (terminal) {
            Terminal.Completed -> recordProvider(TtsEventKind.COMPLETED)
            is Terminal.Failed -> recordFailure(terminal.error, terminal.summary)
            is Terminal.TimedOut -> diagnosticLogger.record(
                DiagnosticEvent.TtsProviderEvent(
                    provider = TtsProvider.FISH,
                    kind = TtsEventKind.PLAYBACK_TIMEOUT,
                    failure = DiagnosticFailure.from(
                        terminal.error,
                        SafeDiagnosticSummary.TTS_PLAYBACK_TIMEOUT,
                    ),
                ),
            )
            is Terminal.Cancelled -> recordProvider(TtsEventKind.CANCELLED)
        }
    }

    private fun recordProvider(kind: TtsEventKind) {
        diagnosticLogger.record(DiagnosticEvent.TtsProviderEvent(TtsProvider.FISH, kind))
    }

    private fun recordFailure(error: Throwable, summary: SafeDiagnosticSummary) {
        diagnosticLogger.record(
            DiagnosticEvent.TtsProviderEvent(
                provider = TtsProvider.FISH,
                kind = TtsEventKind.FAILED,
                failure = DiagnosticFailure.from(error, summary),
            ),
        )
    }

    private fun recordFocus(result: AudioFocusResult) {
        diagnosticLogger.record(DiagnosticEvent.AudioFocusEvent(TtsProvider.FISH, result))
    }

    private object NoOpAudioFocusLease : AudioFocusLease {
        override val granted: Boolean = false
        override fun release() = Unit
    }
}
