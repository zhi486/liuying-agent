package com.healthyagent.running.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

data class DiagnosticLogPolicy(
    val maxBytes: Long = 256L * 1024L,
    val historyFiles: Int = 3,
)

internal object DiagnosticProcessIdentity {
    val startId: String = UUID.randomUUID().toString()
}

class FileDiagnosticLogger private constructor(
    private val directory: File,
    ioDispatcher: CoroutineDispatcher,
    private val now: () -> Instant = { Instant.now() },
    private val processStartId: String = DiagnosticProcessIdentity.startId,
    private val policy: DiagnosticLogPolicy,
    private val renameFile: (File, File) -> Boolean,
    ignored: Unit,
) : DiagnosticLogger {
    constructor(
        directory: File,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        now: () -> Instant = { Instant.now() },
        processStartId: String = DiagnosticProcessIdentity.startId,
        policy: DiagnosticLogPolicy = DiagnosticLogPolicy(),
    ) : this(
        directory,
        ioDispatcher,
        now,
        processStartId,
        policy,
        { source, destination -> source.renameTo(destination) },
        Unit,
    )

    internal constructor(
        directory: File,
        ioDispatcher: CoroutineDispatcher,
        now: () -> Instant,
        processStartId: String,
        policy: DiagnosticLogPolicy,
        renameFile: (File, File) -> Boolean,
    ) : this(directory, ioDispatcher, now, processStartId, policy, renameFile, Unit)

    private val commands = Channel<Command>(Channel.UNLIMITED)

    init {
        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            consume(commands)
        }
    }

    override fun record(event: DiagnosticEvent) {
        runCatching {
            val envelope = event.toEnvelope(now().toString(), processStartId)
            commands.trySend(Command.Write(Json.encodeToString(envelope) + "\n"))
        }.onFailure { Timber.w(it, "诊断事件入队失败") }
    }

    override suspend fun awaitIdle() = waitForWriter(sync = false)

    override suspend fun flush() = waitForWriter(sync = true)

    private suspend fun waitForWriter(sync: Boolean) {
        val completed = CompletableDeferred<Unit>()
        if (commands.trySend(Command.Barrier(sync, completed)).isSuccess) {
            completed.await()
        }
    }

    private suspend fun consume(source: Channel<Command>) {
        for (command in source) {
            when (command) {
                is Command.Write -> writeSafely(command.line)
                is Command.Barrier -> {
                    if (command.sync) syncSafely()
                    command.completed.complete(Unit)
                }
            }
        }
    }

    private fun writeSafely(line: String) {
        runCatching {
            val bytes = line.toByteArray(Charsets.UTF_8)
            ensureDirectory()
            val current = fileFor(0)
            if (current.exists() && current.length() > 0 && current.length() + bytes.size > policy.maxBytes) {
                rotate()
            }
            FileOutputStream(fileFor(0), true).use { it.write(bytes) }
        }.onFailure { Timber.w(it, "诊断日志写入失败") }
    }

    private fun syncSafely() {
        runCatching {
            val current = fileFor(0)
            if (current.exists()) FileOutputStream(current, true).use { it.fd.sync() }
        }.onFailure { Timber.w(it, "诊断日志同步失败") }
    }

    private fun ensureDirectory() {
        if (directory.exists()) {
            if (!directory.isDirectory) throw IOException("诊断日志目录不可用")
        } else if (!directory.mkdirs()) {
            throw IOException("无法创建诊断日志目录")
        }
    }

    private fun rotate() {
        for (index in policy.historyFiles downTo 1) {
            val destination = fileFor(index)
            if (index == policy.historyFiles) destination.delete()
            val source = fileFor(index - 1)
            if (source.exists() && !renameFile(source, destination)) throw IOException("无法轮转诊断日志")
        }
    }

    private fun fileFor(historyIndex: Int) = when (historyIndex) {
        0 -> File(directory, "diagnostics.jsonl")
        else -> File(directory, "diagnostics.$historyIndex.jsonl")
    }

    private sealed interface Command {
        data class Write(val line: String) : Command
        data class Barrier(val sync: Boolean, val completed: CompletableDeferred<Unit>) : Command
    }
}

@Serializable
private data class WireEnvelope(
    val timestamp: String,
    val processStartId: String,
    val module: String,
    val eventName: String,
    val payload: JsonObject,
)

private fun DiagnosticEvent.toEnvelope(timestamp: String, processStartId: String): WireEnvelope = when (this) {
    is DiagnosticEvent.BleLinkLostNotice -> wire("ble", "ble_link_lost", payload("sessionGeneration" to sessionGeneration, "observationGeneration" to observationGeneration, "faultId" to faultId, "episodeId" to episodeId), timestamp, processStartId)
    is DiagnosticEvent.BleLifecycleChanged -> wire("ble", "ble_lifecycle_changed", payload("from" to from.wire(), "to" to to.wire(), "epoch" to epoch, "lifecycleRevision" to lifecycleRevision), timestamp, processStartId)
    is DiagnosticEvent.BleLinkChanged -> wire("ble", "ble_link_changed", payload("from" to from.wire(), "to" to to.wire(), "attemptToken" to attemptToken, "gattToken" to gattToken, "reason" to reason?.wire()), timestamp, processStartId)
    is DiagnosticEvent.BleReconnect -> wire("ble", "ble_reconnect", payload("stage" to stage.wire(), "epoch" to epoch, "attemptToken" to attemptToken, "reason" to reason?.wire()), timestamp, processStartId)
    is DiagnosticEvent.BleSyncWrite -> wire("ble", "ble_sync_write", payload("kind" to kind.wire(), "result" to result.wire(), "epoch" to epoch, "gattToken" to gattToken), timestamp, processStartId)
    is DiagnosticEvent.BleResumeHealth -> wire("ble", "ble_resume_health", payload("decision" to decision.wire(), "activityAgeMillis" to activityAgeMillis), timestamp, processStartId)
    is DiagnosticEvent.BleStaleEventIgnored -> wire("ble", "ble_stale_event_ignored", payload("kind" to kind.wire(), "epoch" to epoch, "attemptToken" to attemptToken, "gattToken" to gattToken), timestamp, processStartId)
    is DiagnosticEvent.BleStaleWriteIgnored -> wire("ble", "ble_stale_write_ignored", payload("kind" to kind.wire(), "lifecycleRevision" to lifecycleRevision), timestamp, processStartId)
    is DiagnosticEvent.BleOperationFailed -> wire("ble", "ble_operation_failed", payload("operation" to operation.wire(), "reason" to reason.wire(), "failure" to failure.wire()), timestamp, processStartId)
    is DiagnosticEvent.RuleTriggered -> wire("rule", "rule_triggered", payload("ruleId" to ruleId.takeCodePoints(80)), timestamp, processStartId)
    is DiagnosticEvent.RuleCooldownSkipped -> wire("rule", "rule_cooldown_skipped", payload("ruleId" to ruleId.takeCodePoints(80), "windowStartedAtMillis" to windowStartedAtMillis), timestamp, processStartId)
    is DiagnosticEvent.SpeechOutcome -> wire("speech", "speech_outcome", payload("ruleId" to ruleId.takeCodePoints(80), "reason" to reason.wire()), timestamp, processStartId)
    is DiagnosticEvent.TtsProviderEvent -> wire("tts", "tts_provider_event", payload("provider" to provider.wire(), "kind" to kind.wire(), "failure" to failure?.wire()), timestamp, processStartId)
    is DiagnosticEvent.AudioFocusEvent -> wire("audio", "audio_focus_event", payload("provider" to provider.wire(), "result" to result.wire()), timestamp, processStartId)
}

private fun wire(module: String, eventName: String, payload: JsonObject, timestamp: String, processStartId: String) = WireEnvelope(timestamp, processStartId, module, eventName, payload)

private fun payload(vararg values: Pair<String, Any?>) = buildJsonObject {
    values.forEach { (name, value) -> put(name, value.toJsonElement()) }
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is JsonObject -> this
    else -> error("诊断 payload 包含不受支持的值")
}

private fun DiagnosticFailure.wire() = payload("type" to type.takeCodePoints(80), "summary" to summary.takeCodePoints(120))
private fun SpeechOutcomeReason.wire() = when (this) { SpeechOutcomeReason.POLICY_DISABLED -> "policy_disabled"; SpeechOutcomeReason.DUPLICATE_INTERVAL -> "duplicate_interval"; SpeechOutcomeReason.PRIORITY_BLOCKED -> "priority_blocked"; SpeechOutcomeReason.STALE_SESSION_GENERATION -> "stale_session_generation"; SpeechOutcomeReason.SESSION_STATUS_SCHEDULED -> "session_status_scheduled"; SpeechOutcomeReason.PENDING_SESSION_STATUS_REPLACED -> "pending_session_status_replaced"; SpeechOutcomeReason.TTS_SUCCESS -> "tts_success"; SpeechOutcomeReason.NOT_PLAYED -> "not_played"; SpeechOutcomeReason.TTS_FAILURE -> "tts_failure"; SpeechOutcomeReason.PLAYBACK_TIMEOUT -> "playback_timeout"; SpeechOutcomeReason.CANCELLED -> "cancelled" }
private fun TtsProvider.wire() = when (this) { TtsProvider.ANDROID -> "android"; TtsProvider.FISH -> "fish"; TtsProvider.COMPOSITE -> "composite"; TtsProvider.BUNDLED -> "bundled" }
private fun TtsEventKind.wire() = when (this) { TtsEventKind.ATTEMPTED -> "attempted"; TtsEventKind.SKIPPED_MISSING_CONFIG -> "skipped_missing_config"; TtsEventKind.INITIALIZATION_FAILED -> "initialization_failed"; TtsEventKind.COMPLETED -> "completed"; TtsEventKind.FAILED -> "failed"; TtsEventKind.LOCAL_FALLBACK -> "local_fallback"; TtsEventKind.PLAYBACK_TIMEOUT -> "playback_timeout"; TtsEventKind.CANCELLED -> "cancelled"; TtsEventKind.PREPARATION_TIMEOUT -> "preparation_timeout"; TtsEventKind.SKIPPED_OFFLINE -> "skipped_offline" }
private fun AudioFocusResult.wire() = when (this) { AudioFocusResult.GRANTED -> "granted"; AudioFocusResult.DENIED -> "denied"; AudioFocusResult.RELEASED -> "released" }
private fun DiagnosticBleLifecycle.wire() = when (this) { DiagnosticBleLifecycle.STOPPED -> "stopped"; DiagnosticBleLifecycle.RUNNING -> "running"; DiagnosticBleLifecycle.PAUSED -> "paused" }
private fun DiagnosticBleLink.wire() = when (this) { DiagnosticBleLink.DISCONNECTED -> "disconnected"; DiagnosticBleLink.CONNECTING -> "connecting"; DiagnosticBleLink.PREPARED_PRIVATE_B3 -> "prepared_private_b3"; DiagnosticBleLink.READY_STANDARD_HR -> "ready_standard_hr"; DiagnosticBleLink.READY_PRIVATE_B3 -> "ready_private_b3" }
private fun BleReconnectStage.wire() = when (this) { BleReconnectStage.SCHEDULED -> "scheduled"; BleReconnectStage.STARTED -> "started"; BleReconnectStage.SUCCEEDED -> "succeeded"; BleReconnectStage.FAILED -> "failed" }
private fun BleWriteResult.wire() = when (this) { BleWriteResult.ACCEPTED -> "accepted"; BleWriteResult.REJECTED -> "rejected" }
private fun BleResumeDecision.wire() = when (this) { BleResumeDecision.HEALTHY -> "healthy"; BleResumeDecision.STALE -> "stale"; BleResumeDecision.STANDARD_HR_SKIPPED -> "standard_hr_skipped" }
private fun BleStaleEventKind.wire() = when (this) { BleStaleEventKind.SCAN_RESULT -> "scan_result"; BleStaleEventKind.SCAN_TIMEOUT -> "scan_timeout"; BleStaleEventKind.RECONNECT -> "reconnect"; BleStaleEventKind.GATT_CALLBACK -> "gatt_callback"; BleStaleEventKind.CONTROL_ACTION -> "control_action"; BleStaleEventKind.SYNC_WRITE -> "sync_write" }
private fun BleSyncKind.wire() = when (this) { BleSyncKind.UPDATE_WORKOUT -> "update_workout"; BleSyncKind.HEART_BEAT -> "heart_beat" }
private fun BleOperation.wire() = when (this) { BleOperation.START_SCAN -> "start_scan"; BleOperation.STOP_SCAN -> "stop_scan"; BleOperation.CONNECT_GATT -> "connect_gatt"; BleOperation.DISCONNECT_GATT -> "disconnect_gatt"; BleOperation.CLOSE_GATT -> "close_gatt"; BleOperation.DISCOVER_SERVICES -> "discover_services"; BleOperation.ENABLE_NOTIFICATIONS -> "enable_notifications"; BleOperation.READ_BATTERY -> "read_battery"; BleOperation.READ_FIRMWARE -> "read_firmware"; BleOperation.START_WRITE -> "start_write"; BleOperation.UPDATE_WRITE -> "update_write"; BleOperation.HEARTBEAT_WRITE -> "heartbeat_write" }
private fun BleFailureReason.wire() = when (this) { BleFailureReason.PERMISSION_MISSING -> "permission_missing"; BleFailureReason.ADAPTER_DISABLED -> "adapter_disabled"; BleFailureReason.SCAN_FAILED -> "scan_failed"; BleFailureReason.SCAN_TIMEOUT -> "scan_timeout"; BleFailureReason.GATT_ERROR -> "gatt_error"; BleFailureReason.REMOTE_DISCONNECTED -> "remote_disconnected"; BleFailureReason.READY_TIMEOUT -> "ready_timeout"; BleFailureReason.START_WRITE_REJECTED -> "start_write_rejected"; BleFailureReason.SYNC_WRITE_REJECTED -> "sync_write_rejected" }
