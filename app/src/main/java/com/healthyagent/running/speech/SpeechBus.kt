package com.healthyagent.running.speech

import com.healthyagent.running.core.model.CoachMessage
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.NoOpDiagnosticLogger
import com.healthyagent.running.diagnostics.SpeechOutcomeReason
import java.util.UUID
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.EmptyCoroutineContext

data class SpeechPolicy(
    val enabled: Boolean,
    val minIntervalSec: Int,
)

/** 规则触发与 TTS 之间的去重、优先级和消息发布总线。 */
class SpeechBus(
    private val ttsEngine: TtsEngine,
    initialPolicy: SpeechPolicy = SpeechPolicy(enabled = true, minIntervalSec = 4),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newMessageId: () -> String = { UUID.randomUUID().toString() },
    private val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private data class FinishedTerminalKey(
        val sessionGeneration: Long,
        val revision: Long,
    )

    private data class ScheduledSpeech(
        val operationId: Long,
        val sessionGeneration: Long,
        val request: SpeechRequest,
        val acceptedAtMillis: Long,
        val sessionStatusRevision: Long? = null,
    ) {
        val trigger: RuleTrigger get() = request.trigger
        val finishedKey: FinishedTerminalKey?
            get() = sessionStatusRevision
                ?.takeIf { trigger.ruleId == SESSION_FINISHED_RULE_ID }
                ?.let { FinishedTerminalKey(sessionGeneration, it) }
    }

    private data class PendingSessionStatus(
        val revision: Long,
        val sessionGeneration: Long,
        val request: SpeechRequest,
    ) {
        val trigger: RuleTrigger get() = request.trigger
    }

    private sealed interface Command {
        data class Start(val speech: ScheduledSpeech) : Command
        data class Replace(
            val speech: ScheduledSpeech,
            val replaced: ScheduledSpeech,
        ) : Command

        data class Terminal(
            val speech: ScheduledSpeech,
            val reason: SpeechOutcomeReason,
            val delivery: SpeechDelivery? = null,
        ) : Command

        data class HardStop(
            val active: ScheduledSpeech?,
            val pending: PendingSessionStatus?,
            val shutdown: Boolean,
        ) : Command
        data class CancelNotice(val speech: ScheduledSpeech) : Command
    }

    private val stateLock = Any()
    private val speakHistory = mutableMapOf<String, Long>()
    private val finishedTerminals = mutableSetOf<FinishedTerminalKey>()
    private val _latestMessage = MutableStateFlow<CoachMessage?>(null)
    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    private val actorScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val flushScope = CoroutineScope(SupervisorJob() + dispatcher)

    private var sessionGeneration = 0L
    private var activeOperationId = 0L
    private var activePlaybackJob: Job? = null
    private var actorPlaybackOperationId: Long? = null
    private var currentPriority: SpeechPriority? = null
    private var activeSpeech: ScheduledSpeech? = null
    private var sessionStatusRevision = 0L
    private var pendingSessionStatus: PendingSessionStatus? = null

    @Volatile
    private var policy = initialPolicy

    val latestMessage: StateFlow<CoachMessage?> = _latestMessage.asStateFlow()

    init {
        require(initialPolicy.minIntervalSec >= 0)
        actorScope.launch {
            for (command in commandChannel) {
                when (command) {
                    is Command.Start -> handleStart(command.speech)
                    is Command.Replace -> handleReplace(command)
                    is Command.Terminal -> handleTerminal(command)
                    is Command.HardStop -> handleHardStop(command)
                    is Command.CancelNotice -> handleCancelNotice(command.speech)
                }
            }
        }
    }

    fun configure(policy: SpeechPolicy) {
        require(policy.minIntervalSec >= 0)
        val disabled = this.policy.enabled && !policy.enabled
        this.policy = policy
        if (disabled) stop()
    }

    fun enqueue(trigger: RuleTrigger) {
        enqueue(trigger, sessionGenerationToken())
    }

    internal fun enqueue(trigger: RuleTrigger, expectedSessionGeneration: Long) {
        enqueue(SpeechRequest(trigger), expectedSessionGeneration)
    }

    fun enqueue(request: SpeechRequest) = enqueue(request, sessionGenerationToken())

    internal fun enqueue(request: SpeechRequest, expectedSessionGeneration: Long) {
        val trigger = request.trigger
        val activePolicy = policy
        val now = nowMillis()
        synchronized(stateLock) {
            when {
                request.noticeToken?.isValid == false -> record(trigger, SpeechOutcomeReason.CANCELLED)
                !activePolicy.enabled -> record(trigger, SpeechOutcomeReason.POLICY_DISABLED)
                sessionGeneration != expectedSessionGeneration -> record(
                    trigger,
                    SpeechOutcomeReason.STALE_SESSION_GENERATION,
                )
                speakHistory[trigger.speakText]?.let {
                    now - it < activePolicy.minIntervalSec * 1_000L
                } == true -> record(trigger, SpeechOutcomeReason.DUPLICATE_INTERVAL)
                currentPriority?.let { trigger.ttsPriority.level >= it.level } == true ->
                    record(trigger, SpeechOutcomeReason.PRIORITY_BLOCKED)
                else -> {
                    val replaced = activeSpeech
                    val speech = ScheduledSpeech(
                        operationId = ++activeOperationId,
                        sessionGeneration = sessionGeneration,
                        request = request.copy(preparationAcceptedAt = timeSource.markNow()),
                        acceptedAtMillis = now,
                    )
                    activeSpeech = speech
                    currentPriority = trigger.ttsPriority
                    publishAcceptance(Acceptance(speech, replaced))
                }
            }
        }
    }

    fun enqueueSessionStatus(trigger: RuleTrigger) {
        enqueueSessionStatus(SpeechRequest(trigger))
    }

    fun enqueueSessionStatus(request: SpeechRequest) {
        val trigger = request.trigger
        val activePolicy = policy
        val now = nowMillis()
        var disabledSpeech: ScheduledSpeech? = null
        synchronized(stateLock) {
            val revision = ++sessionStatusRevision
            if (!activePolicy.enabled) {
                disabledSpeech = ScheduledSpeech(
                    operationId = activeOperationId,
                    sessionGeneration = sessionGeneration,
                    request = request,
                    acceptedAtMillis = now,
                    sessionStatusRevision = revision,
                )
                return@synchronized null
            }

            val acceptedRequest = request.copy(preparationAcceptedAt = timeSource.markNow())
            record(trigger, SpeechOutcomeReason.SESSION_STATUS_SCHEDULED)
            if (currentPriority == SpeechPriority.URGENT) {
                pendingSessionStatus?.let {
                    record(it.trigger, SpeechOutcomeReason.PENDING_SESSION_STATUS_REPLACED)
                }
                pendingSessionStatus = PendingSessionStatus(
                    revision = revision,
                    sessionGeneration = sessionGeneration,
                    request = acceptedRequest,
                )
                null
            } else {
                val replaced = activeSpeech
                val speech = ScheduledSpeech(
                    operationId = ++activeOperationId,
                    sessionGeneration = sessionGeneration,
                    request = acceptedRequest,
                    acceptedAtMillis = now,
                    sessionStatusRevision = revision,
                )
                activeSpeech = speech
                currentPriority = trigger.ttsPriority
                publishAcceptance(Acceptance(speech, replaced))
                null
            }
        }

        disabledSpeech?.let {
            recordTerminal(it, SpeechOutcomeReason.POLICY_DISABLED)
        }
    }

    internal fun sessionGenerationToken(): Long = synchronized(stateLock) { sessionGeneration }

    fun resetSession() {
        hardStop(clearHistory = true, clearMessage = true, shutdown = false)
    }

    fun cancelNotice(token: SpeechNoticeToken) {
        // 取消媒体准备不必等待 actor，且不能持有总线锁调用外部取消处理器。
        token.cancel()
        synchronized(stateLock) {
            val speech = activeSpeech?.takeIf { it.request.noticeToken === token } ?: return
            activeOperationId++
            activeSpeech = null
            currentPriority = null
            check(commandChannel.trySend(Command.CancelNotice(speech)).isSuccess)
        }
    }

    fun stop() {
        hardStop(clearHistory = false, clearMessage = false, shutdown = false)
    }

    fun shutdown() {
        hardStop(clearHistory = true, clearMessage = false, shutdown = true)
    }

    private data class Acceptance(
        val speech: ScheduledSpeech,
        val replaced: ScheduledSpeech?,
    )

    private fun publishAcceptance(acceptance: Acceptance) {
        val command = acceptance.replaced?.let {
            Command.Replace(acceptance.speech, it)
        } ?: Command.Start(acceptance.speech)
        check(commandChannel.trySend(command).isSuccess)
    }

    private fun hardStop(clearHistory: Boolean, clearMessage: Boolean, shutdown: Boolean) {
        synchronized(stateLock) {
            val active = activeSpeech
            val pending = pendingSessionStatus
            sessionGeneration += 1
            activeOperationId += 1
            activeSpeech = null
            pendingSessionStatus = null
            currentPriority = null
            if (clearHistory) speakHistory.clear()
            if (clearMessage) _latestMessage.value = null
            val command = Command.HardStop(active, pending, shutdown)
            check(commandChannel.trySend(command).isSuccess)
        }
    }

    private fun handleStart(speech: ScheduledSpeech) {
        if (!isCurrent(speech)) return
        startPlayback(speech)
    }

    private fun handleReplace(command: Command.Replace) {
        if (!isCurrent(command.speech)) {
            recordTerminal(command.replaced, SpeechOutcomeReason.CANCELLED)
            return
        }
        recordTerminal(command.replaced, SpeechOutcomeReason.CANCELLED)
        activePlaybackJob?.cancel()
        if (actorPlaybackOperationId != null) {
            ttsEngine.stop()
        }
        actorPlaybackOperationId = null
        startPlayback(command.speech)
    }

    private fun startPlayback(speech: ScheduledSpeech) {
        if (!isCurrent(speech)) return
        // 被撤销的 Replace 可能尚未执行；新的 Start 也必须释放残留的旧播放。
        if (actorPlaybackOperationId != null && actorPlaybackOperationId != speech.operationId) {
            activePlaybackJob?.cancel()
            ttsEngine.stop()
        }
        actorPlaybackOperationId = speech.operationId
        activePlaybackJob = actorScope.launch(speech.request.noticeToken ?: EmptyCoroutineContext) {
            var delivery: SpeechDelivery? = null
            val reason = try {
                if (!isCurrent(speech)) throw CancellationException("语音操作已失效")
                speech.request.noticeToken?.let {
                    it.bind(checkNotNull(currentCoroutineContext()[Job]))
                    it.ensureValid()
                }
                val result = ttsEngine.speak(speech.request)
                delivery = result.getOrNull()
                val error = result.exceptionOrNull()
                when (error) {
                    null -> if (delivery?.played == true) SpeechOutcomeReason.TTS_SUCCESS
                        else SpeechOutcomeReason.NOT_PLAYED
                    is PlaybackTimeoutException -> SpeechOutcomeReason.PLAYBACK_TIMEOUT
                    is CancellationException -> SpeechOutcomeReason.CANCELLED
                    else -> SpeechOutcomeReason.TTS_FAILURE
                }
            } catch (cancelled: CancellationException) {
                commandChannel.trySend(Command.Terminal(speech, SpeechOutcomeReason.CANCELLED))
                throw cancelled
            } catch (timeout: PlaybackTimeoutException) {
                SpeechOutcomeReason.PLAYBACK_TIMEOUT
            } catch (_: Throwable) {
                SpeechOutcomeReason.TTS_FAILURE
            }
            commandChannel.trySend(Command.Terminal(speech, reason, delivery))
        }
    }

    private fun handleTerminal(command: Command.Terminal) {
        var reason = command.reason
        val promoted = synchronized(stateLock) {
            val current = activeSpeech
            if (
                current?.operationId != command.speech.operationId ||
                current.sessionGeneration != command.speech.sessionGeneration ||
                sessionGeneration != command.speech.sessionGeneration
            ) {
                return@synchronized null
            }

            if (command.speech.request.noticeToken?.isValid == false) reason = SpeechOutcomeReason.CANCELLED

            activeSpeech = null
            currentPriority = null
            if (actorPlaybackOperationId == command.speech.operationId) {
                activePlaybackJob = null
                actorPlaybackOperationId = null
            }
            if (reason == SpeechOutcomeReason.TTS_SUCCESS) {
                speakHistory[command.speech.trigger.speakText] = command.speech.acceptedAtMillis
            }
            if (reason != SpeechOutcomeReason.CANCELLED) {
                _latestMessage.value = CoachMessage(
                    id = newMessageId(),
                    text = command.delivery?.takeIf { it.played }?.text
                        ?: BundledVoiceCatalog.find(command.speech.trigger.ruleId)?.text
                        ?: command.speech.trigger.speakText,
                    priority = command.speech.trigger.ttsPriority,
                    timestampMillis = command.speech.acceptedAtMillis,
                )
            }

            pendingSessionStatus
                ?.takeIf { it.sessionGeneration == sessionGeneration }
                ?.also { pendingSessionStatus = null }
                ?.let { pending ->
                    ScheduledSpeech(
                        operationId = ++activeOperationId,
                        sessionGeneration = pending.sessionGeneration,
                        request = pending.request,
                        acceptedAtMillis = nowMillis(),
                        sessionStatusRevision = pending.revision,
                    ).also {
                        activeSpeech = it
                        currentPriority = it.trigger.ttsPriority
                    }
                }
        }

        recordTerminal(command.speech, reason)
        promoted?.let(::startPlayback)
    }

    private fun handleHardStop(command: Command.HardStop) {
        command.pending?.let { pending ->
            val speech = ScheduledSpeech(
                operationId = -pending.revision,
                sessionGeneration = pending.sessionGeneration,
                request = pending.request,
                acceptedAtMillis = nowMillis(),
                sessionStatusRevision = pending.revision,
            )
            recordTerminal(speech, SpeechOutcomeReason.CANCELLED)
        }
        command.active?.let { recordTerminal(it, SpeechOutcomeReason.CANCELLED) }
        activePlaybackJob?.cancel()
        activePlaybackJob = null
        actorPlaybackOperationId = null
        ttsEngine.stop()
        if (command.shutdown) ttsEngine.shutdown()
    }

    private fun handleCancelNotice(speech: ScheduledSpeech) {
        if (actorPlaybackOperationId == speech.operationId) {
            activePlaybackJob?.cancel()
            activePlaybackJob = null
            actorPlaybackOperationId = null
            ttsEngine.stop()
        }
        recordTerminal(speech, SpeechOutcomeReason.CANCELLED)
    }

    private fun isCurrent(speech: ScheduledSpeech): Boolean = synchronized(stateLock) {
        sessionGeneration == speech.sessionGeneration &&
            activeSpeech?.operationId == speech.operationId &&
            activeSpeech?.sessionGeneration == speech.sessionGeneration
    }

    private fun recordTerminal(speech: ScheduledSpeech, reason: SpeechOutcomeReason) {
        val key = speech.finishedKey
        if (key != null) {
            val firstTerminal = synchronized(stateLock) { finishedTerminals.add(key) }
            if (!firstTerminal) return
        }
        record(speech.trigger, reason)
        if (key != null) {
            flushScope.launch {
                withTimeoutOrNull(FINISHED_FLUSH_TIMEOUT_MILLIS) {
                    diagnosticLogger.flush()
                }
            }
        }
    }

    private fun record(trigger: RuleTrigger, reason: SpeechOutcomeReason) {
        diagnosticLogger.record(DiagnosticEvent.SpeechOutcome(trigger.ruleId, reason))
    }

    private companion object {
        const val SESSION_FINISHED_RULE_ID = "session_finished"
        const val FINISHED_FLUSH_TIMEOUT_MILLIS = 2_000L
    }
}
