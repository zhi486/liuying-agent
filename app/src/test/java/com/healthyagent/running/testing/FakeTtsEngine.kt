package com.healthyagent.running.testing

import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.speech.TtsEngine
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

data class FakeTtsRequest(
    val text: String,
    val priority: SpeechPriority,
)

class FakeTtsEngine : TtsEngine {
    private sealed interface Terminal {
        data object Success : Terminal
        data class Failure(val error: Throwable) : Terminal
        data object Cancelled : Terminal
    }

    private data class ActiveRequest(
        val request: FakeTtsRequest,
        val terminal: CompletableDeferred<Terminal> = CompletableDeferred(),
    )

    private val lock = Any()
    private val storedRequests = mutableListOf<FakeTtsRequest>()
    private val storedCompletedTexts = mutableListOf<String>()
    private val activeRequests = ArrayDeque<ActiveRequest>()
    private val queuedTerminals = ArrayDeque<Terminal>()
    private var storedStopCalls = 0
    private var storedShutdownCalls = 0

    val requests: List<FakeTtsRequest>
        get() = synchronized(lock) { storedRequests.toList() }
    val completedTexts: List<String>
        get() = synchronized(lock) { storedCompletedTexts.toList() }
    val stopCalls: Int
        get() = synchronized(lock) { storedStopCalls }
    val shutdownCalls: Int
        get() = synchronized(lock) { storedShutdownCalls }

    override val isSpeaking: Boolean
        get() = synchronized(lock) { activeRequests.any { !it.terminal.isCompleted } }

    fun completeNextSuccessfully() = completeNext(Terminal.Success)

    fun completeNextWithFailure(
        failure: Throwable = IllegalStateException("模拟 TTS 失败"),
    ) = completeNext(Terminal.Failure(failure))

    fun cancelNext() = completeNext(Terminal.Cancelled)

    override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> {
        val active = synchronized(lock) {
            ActiveRequest(FakeTtsRequest(text, priority)).also { request ->
                storedRequests += request.request
                activeRequests += request
                queuedTerminals.pollFirst()?.let(request.terminal::complete)
            }
        }
        return try {
            when (val terminal = active.terminal.await()) {
                Terminal.Success -> {
                    synchronized(lock) { storedCompletedTexts += text }
                    Result.success(Unit)
                }
                is Terminal.Failure -> Result.failure(terminal.error)
                Terminal.Cancelled -> throw CancellationException("模拟 TTS 取消")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            synchronized(lock) { activeRequests.remove(active) }
        }
    }

    override fun stop() {
        synchronized(lock) {
            storedStopCalls += 1
            activeRequests.firstOrNull { !it.terminal.isCompleted }
                ?.terminal
                ?.complete(Terminal.Cancelled)
        }
    }

    override fun shutdown() {
        synchronized(lock) {
            storedShutdownCalls += 1
            activeRequests.forEach { it.terminal.complete(Terminal.Cancelled) }
        }
    }

    private fun completeNext(terminal: Terminal) {
        synchronized(lock) {
            val head = activeRequests.peekFirst()
            if (head == null) {
                queuedTerminals += terminal
            } else {
                head.terminal.complete(terminal)
            }
        }
    }
}
