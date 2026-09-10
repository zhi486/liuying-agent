package com.healthyagent.running.speech

import com.healthyagent.running.core.model.CoachMessage
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.NoOpDiagnosticLogger
import com.healthyagent.running.diagnostics.SpeechOutcomeReason
import java.util.UUID
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
import kotlinx.coroutines.withTimeoutOrNull

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
) {
    private data class FinishedTerminalKey(
        val sessionGeneration: Long,
        val revision: Long,
    )

    private data class ScheduledSpeech(
        val operationId: Long,
        val sessionGeneration: Long,
        val trigger: RuleTrigger,
        val acceptedAtMillis: Long,
        val sessionStatusRevision: Long? = null,
    ) {
        val finishedKey: FinishedTerminalKey?
            get() = sessionStatusRevision
                ?.takeIf { trigger.ruleId == SESSION_FINISHED_RULE_ID }
                ?.let { FinishedTerminalKey(sessionGeneration, it) }
    }

    private data class PendingSessionStatus(
        val revision: Long,
        val sessionGeneration: Long,
        val trigger: RuleTrigger,
    )

    private sealed interface Command {
        data class Start(val speech: ScheduledSpeech) : Command
        data class Replace(
            val speech: ScheduledSpeech,
            val replaced: ScheduledSpeech,
        ) : Command

        data class Terminal(
            val speech: ScheduledSpeech,
            val reason: SpeechOutcomeReason,
        ) : Command

        data class HardStop(
            val active: ScheduledSpeech?,
            val pending: PendingSessionStatus?,
            val shutdown: Boolean,
        ) : Command
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
                }
            }
        }
    }

    fun configure(policy: SpeechPolicy) {
        require(policy.minIntervalSec >= 0)
        this.policy = policy
    }

    fun enqueue(trigger: RuleTrigger) {
        enqueue(trigger, sessionGenerationToken())
    }

    internal fun enqueue(trigger: RuleTrigger, expectedSessionGeneration: Long) {
        val activePolicy = policy
        val now = nowMillis()
        synchronized(stateLock) {
            when {
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
                        trigger = trigger,
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
        val activePolicy = policy
        val now = nowMillis()
        var disabledSpeech: ScheduledSpeech? = null
        synchronized(stateLock) {
            val revision = ++sessionStatusRevision
            if (!activePolicy.enabled) {
                disabledSpeech = ScheduledSpeech(
                    operationId = activeOperationId,
                    sessionGeneration = sessionGeneration,
                    trigger = trigger,
                    acceptedAtMillis = now,
                    sessionStatusRevision = revision,
                )
                return@synchronized null
            }

            record(trigger, SpeechOutcomeReason.SESSION_STATUS_SCHEDULED)
            if (currentPriority == SpeechPriority.URGENT) {
                pendingSessionStatus?.let {
                    record(it.trigger, SpeechOutcomeReason.PENDING_SESSION_STATUS_REPLACED)
                }
                pendingSessionStatus = PendingSessionStatus(
                    revision = revision,
                    sessionGeneration = sessionGeneration,
                    trigger = trigger,
                )
                null
            } else {
                val replaced = activeSpeech
                val speech = ScheduledSpeech(
                    operationId = ++activeOperationId,
                    sessionGeneration = sessionGeneration,
                    trigger = trigger,
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
        startPlayback(command.speech)
    }

    private fun startPlayback(speech: ScheduledSpeech) {
        actorPlaybackOperationId = speech.operationId
        activePlaybackJob = actorScope.launch {
            val reason = try {
                val result = ttsEngine.speak(
                    speech.trigger.speakText,
                    speech.trigger.ttsPriority,
                )
                val error = result.exceptionOrNull()
                when (error) {
                    null -> SpeechOutcomeReason.TTS_SUCCESS
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
            commandChannel.trySend(Command.Terminal(speech, reason))
        }
    }

    private fun handleTerminal(command: Command.Terminal) {
        val promoted = synchronized(stateLock) {
            val current = activeSpeech
            if (
                current?.operationId != command.speech.operationId ||
                current.sessionGeneration != command.speech.sessionGeneration ||
                sessionGeneration != command.speech.sessionGeneration
            ) {
                return@synchronized null
            }

            activeSpeech = null
            currentPriority = null
            if (actorPlaybackOperationId == command.speech.operationId) {
                activePlaybackJob = null
                actorPlaybackOperationId = null
            }
            if (command.reason == SpeechOutcomeReason.TTS_SUCCESS) {
                speakHistory[command.speech.trigger.speakText] = command.speech.acceptedAtMillis
                _latestMessage.value = CoachMessage(
                    id = newMessageId(),
                    text = command.speech.trigger.speakText,
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
                        trigger = pending.trigger,
                        acceptedAtMillis = nowMillis(),
                        sessionStatusRevision = pending.revision,
                    ).also {
                        activeSpeech = it
                        currentPriority = it.trigger.ttsPriority
                    }
                }
        }

        recordTerminal(command.speech, command.reason)
        promoted?.let(::startPlayback)
    }

    private fun handleHardStop(command: Command.HardStop) {
        command.pending?.let { pending ->
            val speech = ScheduledSpeech(
                operationId = -pending.revision,
                sessionGeneration = pending.sessionGeneration,
                trigger = pending.trigger,
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
