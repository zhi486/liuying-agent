package com.healthyagent.running.speech

import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticFailure
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.NoOpDiagnosticLogger
import com.healthyagent.running.diagnostics.SafeDiagnosticSummary
import com.healthyagent.running.diagnostics.TtsEventKind
import com.healthyagent.running.diagnostics.TtsProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** 优先使用云端音色，普通失败时由单 actor 降级到 Android 本地语音。 */
class CompositeTtsEngine private constructor(
    private val local: TtsEngine,
    private val cloud: TtsEngine?,
    private val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val actorDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val testHooks: TestHooks,
) : TtsEngine {
    private data class TestHooks(
        val beforeActivePublication: () -> Unit = {},
        val afterGenerationAdvanced: () -> Unit = {},
        val afterChildCancelledSent: () -> Unit = {},
    )

    constructor(
        local: TtsEngine,
        cloud: TtsEngine?,
        diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
        actorDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(
        local = local,
        cloud = cloud,
        diagnosticLogger = diagnosticLogger,
        actorDispatcher = actorDispatcher,
        testHooks = TestHooks(),
    )

    internal constructor(
        local: TtsEngine,
        cloud: TtsEngine?,
        diagnosticLogger: DiagnosticLogger,
        actorDispatcher: CoroutineDispatcher,
        beforeActivePublication: () -> Unit,
        afterGenerationAdvanced: () -> Unit,
        afterChildCancelledSent: () -> Unit,
    ) : this(
        local = local,
        cloud = cloud,
        diagnosticLogger = diagnosticLogger,
        actorDispatcher = actorDispatcher,
        testHooks = TestHooks(
            beforeActivePublication = beforeActivePublication,
            afterGenerationAdvanced = afterGenerationAdvanced,
            afterChildCancelledSent = afterChildCancelledSent,
        ),
    )

    private enum class Provider {
        CLOUD,
        LOCAL,
    }

    private sealed interface Outcome {
        data class Completed(val result: Result<Unit>) : Outcome
        data class Cancelled(val error: CancellationException) : Outcome
        data class Fatal(val error: Throwable) : Outcome
        data class Closed(val error: IllegalStateException) : Outcome
    }

    private class Operation(
        val operationId: Long,
        val generation: Long,
        val text: String,
        val priority: SpeechPriority,
        val invalidated: AtomicBoolean = AtomicBoolean(false),
        val completion: CompletableDeferred<Unit> = CompletableDeferred(),
        val outcome: AtomicReference<Outcome?> = AtomicReference(null),
    )

    private data class ActiveOperation(
        val operation: Operation,
        val provider: Provider,
        val child: Job,
    )

    private sealed interface Command {
        data class Start(val operation: Operation) : Command

        data class ChildTerminal(
            val operationId: Long,
            val generation: Long,
            val provider: Provider,
            val result: Result<Unit>,
            val invalidated: AtomicBoolean,
        ) : Command

        data class ChildCancelled(
            val operationId: Long,
            val generation: Long,
            val provider: Provider,
            val error: CancellationException,
            val invalidated: AtomicBoolean,
        ) : Command

        data class ChildFatal(
            val operationId: Long,
            val generation: Long,
            val provider: Provider,
            val error: Throwable,
            val invalidated: AtomicBoolean,
        ) : Command

        data class Cancel(
            val operationId: Long,
            val generation: Long,
            val error: CancellationException,
            val invalidated: AtomicBoolean,
        ) : Command

        data class HardStop(
            val generation: Long,
            val error: CancellationException,
        ) : Command

        data class Shutdown(
            val generation: Long,
            val error: CancellationException,
        ) : Command
    }

    private val generation = AtomicLong(0L)
    private val nextOperationId = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val activeOperation = AtomicReference<Operation?>(null)
    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    private val actorScope = CoroutineScope(SupervisorJob() + actorDispatcher)

    override val isSpeaking: Boolean
        get() = activeOperation.get()?.invalidated?.get() == false ||
            local.isSpeaking || cloud?.isSpeaking == true

    init {
        actorScope.launch {
            var active: ActiveOperation? = null
            for (command in commandChannel) {
                when (command) {
                    is Command.Start -> {
                        if (!isCurrent(command.operation)) {
                            completeCancelledOrClosed(command.operation)
                            continue
                        }
                        if (active != null) {
                            cancelActive(
                                active = active,
                                error = CancellationException("Composite TTS 请求已被替换"),
                            )
                            active = null
                        }
                        active = startPreferred(command.operation)
                    }

                    is Command.ChildTerminal -> {
                        val current = active ?: continue
                        if (!matches(current, command)) continue
                        val operation = current.operation
                        if (command.provider == Provider.CLOUD && command.result.isFailure) {
                            val error = command.result.exceptionOrNull()
                            if (error is CancellationException) {
                                completeCancelled(operation, error)
                                active = null
                                clearActive(operation)
                            } else if (isCurrent(operation)) {
                                safeRecord(DiagnosticEvent.TtsProviderEvent.localFallback())
                                active = startProvider(operation, Provider.LOCAL)
                            }
                        } else {
                            if (isCurrent(operation)) {
                                completeResult(operation, command.result)
                            } else {
                                completeCancelledOrClosed(operation)
                            }
                            active = null
                            clearActive(operation)
                        }
                    }

                    is Command.ChildCancelled -> {
                        val current = active ?: continue
                        if (!matches(current, command)) continue
                        completeCancelled(current.operation, command.error)
                        active = null
                        clearActive(current.operation)
                    }

                    is Command.ChildFatal -> {
                        val current = active ?: continue
                        if (!matches(current, command)) continue
                        completeFatal(current.operation, command.error)
                        active = null
                        clearActive(current.operation)
                    }

                    is Command.Cancel -> {
                        val current = active
                        if (
                            current?.operation?.operationId == command.operationId &&
                            current.operation.generation == command.generation &&
                            current.operation.invalidated === command.invalidated
                        ) {
                            cancelActive(current, command.error)
                            active = null
                        }
                    }

                    is Command.HardStop -> {
                        val current = active
                        if (current != null && current.operation.generation < command.generation) {
                            cancelActive(current, command.error)
                            active = null
                        }
                    }

                    is Command.Shutdown -> {
                        val current = active
                        if (current != null) {
                            cancelActive(current, command.error)
                            active = null
                        }
                        shutdownProvider(cloud)
                        shutdownProvider(local)
                        commandChannel.close()
                    }
                }
            }
        }
    }

    override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> {
        val operation = Operation(
            operationId = nextOperationId.incrementAndGet(),
            generation = generation.get(),
            text = text,
            priority = priority,
        )
        if (closed.get()) {
            operation.invalidated.set(true)
            completeClosed(operation)
            return awaitOperation(operation)
        }
        if (commandChannel.trySend(Command.Start(operation)).isFailure) {
            operation.invalidated.set(true)
            completeClosed(operation)
        }
        return awaitOperation(operation)
    }

    override fun stop() {
        val nextGeneration = generation.incrementAndGet()
        activeOperation.get()?.invalidated?.compareAndSet(false, true)
        testHooks.afterGenerationAdvanced()
        commandChannel.trySend(
            Command.HardStop(
                generation = nextGeneration,
                error = CancellationException("Composite TTS 引擎已停止"),
            ),
        )
    }

    override fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        val nextGeneration = generation.incrementAndGet()
        activeOperation.get()?.invalidated?.compareAndSet(false, true)
        testHooks.afterGenerationAdvanced()
        commandChannel.trySend(
            Command.Shutdown(
                generation = nextGeneration,
                error = CancellationException("Composite TTS 引擎已关闭"),
            ),
        )
    }

    private fun startPreferred(operation: Operation): ActiveOperation? {
        if (!isCurrent(operation)) {
            completeCancelledOrClosed(operation)
            return null
        }
        return if (cloud == null) {
            safeRecord(DiagnosticEvent.TtsProviderEvent.missingConfiguration())
            startProvider(operation, Provider.LOCAL)
        } else {
            startProvider(operation, Provider.CLOUD)
        }
    }

    private fun startProvider(operation: Operation, provider: Provider): ActiveOperation? {
        if (!isCurrent(operation)) {
            completeCancelledOrClosed(operation)
            clearActive(operation)
            return null
        }
        testHooks.beforeActivePublication()
        activeOperation.set(operation)
        val terminalReported = AtomicBoolean(false)
        val child = actorScope.launch {
            if (!isCurrent(operation)) {
                reportCancelled(
                    operation = operation,
                    provider = provider,
                    error = CancellationException("Composite TTS 请求已失效"),
                    reported = terminalReported,
                )
                return@launch
            }
            val engine = engine(provider)
            try {
                val result = engine.speak(operation.text, operation.priority)
                val cancellation = result.exceptionOrNull() as? CancellationException
                if (cancellation != null) {
                    reportCancelled(operation, provider, cancellation, terminalReported)
                } else {
                    reportTerminal(operation, provider, result, terminalReported)
                }
            } catch (cancelled: CancellationException) {
                reportCancelled(operation, provider, cancelled, terminalReported)
                throw cancelled
            } catch (error: Exception) {
                reportTerminal(operation, provider, Result.failure(error), terminalReported)
            } catch (fatal: Throwable) {
                reportFatal(operation, provider, fatal, terminalReported)
            }
        }
        return ActiveOperation(operation, provider, child)
    }

    private fun reportTerminal(
        operation: Operation,
        provider: Provider,
        result: Result<Unit>,
        reported: AtomicBoolean,
    ) {
        if (!reported.compareAndSet(false, true)) return
        commandChannel.trySend(
            Command.ChildTerminal(
                operationId = operation.operationId,
                generation = operation.generation,
                provider = provider,
                result = result,
                invalidated = operation.invalidated,
            ),
        )
    }

    private fun reportCancelled(
        operation: Operation,
        provider: Provider,
        error: CancellationException,
        reported: AtomicBoolean,
    ) {
        if (!reported.compareAndSet(false, true)) return
        commandChannel.trySend(
            Command.ChildCancelled(
                operationId = operation.operationId,
                generation = operation.generation,
                provider = provider,
                error = error,
                invalidated = operation.invalidated,
            ),
        )
        testHooks.afterChildCancelledSent()
    }

    private fun reportFatal(
        operation: Operation,
        provider: Provider,
        error: Throwable,
        reported: AtomicBoolean,
    ) {
        if (!reported.compareAndSet(false, true)) return
        commandChannel.trySend(
            Command.ChildFatal(
                operationId = operation.operationId,
                generation = operation.generation,
                provider = provider,
                error = error,
                invalidated = operation.invalidated,
            ),
        )
    }

    private fun matches(active: ActiveOperation?, command: Command.ChildTerminal): Boolean =
        active?.operation?.operationId == command.operationId &&
            active.operation.generation == command.generation &&
            active.provider == command.provider &&
            active.operation.invalidated === command.invalidated &&
            isCurrent(active.operation)

    private fun matches(active: ActiveOperation?, command: Command.ChildCancelled): Boolean =
        active?.operation?.operationId == command.operationId &&
            active.operation.generation == command.generation &&
            active.provider == command.provider &&
            active.operation.invalidated === command.invalidated &&
            isCurrent(active.operation)

    private fun matches(active: ActiveOperation?, command: Command.ChildFatal): Boolean =
        active?.operation?.operationId == command.operationId &&
            active.operation.generation == command.generation &&
            active.provider == command.provider &&
            active.operation.invalidated === command.invalidated &&
            isCurrent(active.operation)

    private fun isCurrent(operation: Operation): Boolean =
        !closed.get() &&
            generation.get() == operation.generation &&
            !operation.invalidated.get()

    private fun cancelActive(active: ActiveOperation, error: CancellationException) {
        active.operation.invalidated.compareAndSet(false, true)
        active.child.cancel(error)
        completeCancelled(active.operation, error)
        clearActive(active.operation)
        stopProvider(engine(active.provider))
    }

    private fun stopProvider(provider: TtsEngine) {
        try {
            provider.stop()
        } catch (_: CancellationException) {
            // 清理取消不是播放失败，actor 继续服务后续请求。
        } catch (error: Exception) {
            recordCleanupFailure(error)
        } catch (_: Throwable) {
            // JVM Error 不能伪装成普通播放失败；清理边界必须隔离它以保住唯一 actor。
        }
    }

    private fun shutdownProvider(provider: TtsEngine?) {
        if (provider == null) return
        try {
            provider.shutdown()
        } catch (_: CancellationException) {
            // shutdown 的取消不伪装成 FAILED，另一个 provider 仍需清理。
        } catch (error: Exception) {
            recordCleanupFailure(error)
        } catch (_: Throwable) {
            // JVM Error 不进入普通失败诊断；另一个 provider 与 channel 仍必须清理。
        }
    }

    private fun recordCleanupFailure(error: Throwable) {
        safeRecord(
            DiagnosticEvent.TtsProviderEvent(
                provider = TtsProvider.COMPOSITE,
                kind = TtsEventKind.FAILED,
                failure = DiagnosticFailure.from(
                    error,
                    SafeDiagnosticSummary.TTS_PLAYBACK_FAILED,
                ),
            ),
        )
    }

    private fun safeRecord(event: DiagnosticEvent) {
        try {
            diagnosticLogger.record(event)
        } catch (_: Throwable) {
            // 诊断失败不能破坏 actor 的请求终态与资源清理。
        }
    }

    private fun engine(provider: Provider): TtsEngine = when (provider) {
        Provider.CLOUD -> checkNotNull(cloud)
        Provider.LOCAL -> local
    }

    private fun clearActive(operation: Operation) {
        activeOperation.compareAndSet(operation, null)
    }

    private fun completeResult(operation: Operation, result: Result<Unit>) {
        complete(operation, Outcome.Completed(result))
    }

    private fun completeCancelled(operation: Operation, error: CancellationException) {
        complete(operation, Outcome.Cancelled(error))
    }

    private fun completeFatal(operation: Operation, error: Throwable) {
        complete(operation, Outcome.Fatal(error))
    }

    private fun completeClosed(operation: Operation) {
        complete(operation, Outcome.Closed(IllegalStateException("Composite TTS 引擎已关闭")))
    }

    private fun completeCancelledOrClosed(operation: Operation) {
        if (closed.get()) {
            completeClosed(operation)
        } else {
            completeCancelled(operation, CancellationException("Composite TTS 请求已失效"))
        }
    }

    private fun complete(operation: Operation, outcome: Outcome) {
        if (!operation.outcome.compareAndSet(null, outcome)) return
        operation.completion.complete(Unit)
    }

    private suspend fun awaitOperation(operation: Operation): Result<Unit> {
        suspendCancellableCoroutine<Unit> { continuation ->
            var completionHandle: DisposableHandle? = null
            completionHandle = operation.completion.invokeOnCompletion {
                continuation.resumeWith(Result.success(Unit))
            }
            continuation.invokeOnCancellation { cause ->
                completionHandle?.dispose()
                val cancellation = cause as? CancellationException
                    ?: CancellationException("Composite TTS caller 已取消").also { it.initCause(cause) }
                if (operation.invalidated.compareAndSet(false, true)) {
                    commandChannel.trySend(
                        Command.Cancel(
                            operationId = operation.operationId,
                            generation = operation.generation,
                            error = cancellation,
                            invalidated = operation.invalidated,
                        ),
                    )
                }
            }
        }
        return when (val outcome = checkNotNull(operation.outcome.get())) {
            is Outcome.Completed -> outcome.result
            is Outcome.Cancelled -> throw outcome.error
            is Outcome.Fatal -> throw outcome.error
            is Outcome.Closed -> throw outcome.error
        }
    }
}
