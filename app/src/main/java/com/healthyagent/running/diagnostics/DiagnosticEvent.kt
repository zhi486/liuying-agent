package com.healthyagent.running.diagnostics

enum class SpeechOutcomeReason(val wireName: String) {
    POLICY_DISABLED("policy_disabled"),
    DUPLICATE_INTERVAL("duplicate_interval"),
    PRIORITY_BLOCKED("priority_blocked"),
    STALE_SESSION_GENERATION("stale_session_generation"),
    SESSION_STATUS_SCHEDULED("session_status_scheduled"),
    PENDING_SESSION_STATUS_REPLACED("pending_session_status_replaced"),
    TTS_SUCCESS("tts_success"),
    TTS_FAILURE("tts_failure"),
    PLAYBACK_TIMEOUT("playback_timeout"),
    CANCELLED("cancelled"),
}

enum class SafeDiagnosticSummary(val text: String) {
    BLE_OPERATION_FAILED("蓝牙操作失败"),
    TTS_INITIALIZATION_FAILED("本地语音初始化失败"),
    TTS_PLAYBACK_FAILED("语音播放失败"),
    TTS_PLAYBACK_TIMEOUT("语音播放超时"),
    FISH_SYNTHESIS_FAILED("云端语音合成失败"),
    DIAGNOSTIC_IO_FAILED("诊断日志写入失败"),
}

enum class TtsProvider { ANDROID, FISH, COMPOSITE }
enum class TtsEventKind { ATTEMPTED, SKIPPED_MISSING_CONFIG, INITIALIZATION_FAILED, COMPLETED, FAILED, LOCAL_FALLBACK, PLAYBACK_TIMEOUT, CANCELLED }
enum class AudioFocusResult { GRANTED, DENIED, RELEASED }
enum class DiagnosticBleLifecycle { STOPPED, RUNNING, PAUSED }
enum class DiagnosticBleLink { DISCONNECTED, CONNECTING, PREPARED_PRIVATE_B3, READY_STANDARD_HR, READY_PRIVATE_B3 }
enum class BleReconnectStage { SCHEDULED, STARTED, SUCCEEDED, FAILED }
enum class BleWriteResult { ACCEPTED, REJECTED }
enum class BleResumeDecision { HEALTHY, STALE, STANDARD_HR_SKIPPED }
enum class BleStaleEventKind { SCAN_RESULT, SCAN_TIMEOUT, RECONNECT, GATT_CALLBACK, CONTROL_ACTION, SYNC_WRITE }
enum class BleSyncKind { UPDATE_WORKOUT, HEART_BEAT }
enum class BleOperation { START_SCAN, STOP_SCAN, CONNECT_GATT, DISCONNECT_GATT, CLOSE_GATT, DISCOVER_SERVICES, ENABLE_NOTIFICATIONS, READ_BATTERY, READ_FIRMWARE, START_WRITE, UPDATE_WRITE, HEARTBEAT_WRITE }
enum class BleFailureReason { PERMISSION_MISSING, ADAPTER_DISABLED, SCAN_FAILED, SCAN_TIMEOUT, GATT_ERROR, REMOTE_DISCONNECTED, READY_TIMEOUT, START_WRITE_REJECTED, SYNC_WRITE_REJECTED }

@ConsistentCopyVisibility
data class DiagnosticFailure private constructor(
    val type: String,
    val summary: String,
) {
    companion object {
        fun from(error: Throwable, summary: SafeDiagnosticSummary) = DiagnosticFailure(
            error::class.java.simpleName.takeCodePoints(80),
            summary.text.takeCodePoints(120),
        )
    }
}

internal fun String.takeCodePoints(maxCount: Int): String {
    val endIndex = offsetByCodePoints(0, codePointCount(0, length).coerceAtMost(maxCount))
    return substring(0, endIndex)
}

sealed interface DiagnosticEvent {
    data class BleLifecycleChanged(val from: DiagnosticBleLifecycle, val to: DiagnosticBleLifecycle, val epoch: Long, val lifecycleRevision: Long) : DiagnosticEvent
    data class BleLinkChanged(val from: DiagnosticBleLink, val to: DiagnosticBleLink, val attemptToken: Long, val gattToken: Long?, val reason: BleFailureReason? = null) : DiagnosticEvent
    data class BleReconnect(val stage: BleReconnectStage, val epoch: Long, val attemptToken: Long, val reason: BleFailureReason? = null) : DiagnosticEvent
    data class BleSyncWrite(val kind: BleSyncKind, val result: BleWriteResult, val epoch: Long, val gattToken: Long) : DiagnosticEvent
    data class BleResumeHealth(val decision: BleResumeDecision, val activityAgeMillis: Long?) : DiagnosticEvent
    data class BleStaleEventIgnored(val kind: BleStaleEventKind, val epoch: Long, val attemptToken: Long, val gattToken: Long?) : DiagnosticEvent
    data class BleStaleWriteIgnored(val kind: BleSyncKind, val lifecycleRevision: Long) : DiagnosticEvent
    data class BleOperationFailed(val operation: BleOperation, val reason: BleFailureReason, val failure: DiagnosticFailure) : DiagnosticEvent
    data class RuleTriggered(val ruleId: String) : DiagnosticEvent
    data class RuleCooldownSkipped(val ruleId: String, val windowStartedAtMillis: Long) : DiagnosticEvent
    data class SpeechOutcome(val ruleId: String, val reason: SpeechOutcomeReason) : DiagnosticEvent
    data class TtsProviderEvent(val provider: TtsProvider, val kind: TtsEventKind, val failure: DiagnosticFailure? = null) : DiagnosticEvent {
        companion object {
            fun missingConfiguration() = TtsProviderEvent(TtsProvider.COMPOSITE, TtsEventKind.SKIPPED_MISSING_CONFIG)
            fun localFallback() = TtsProviderEvent(TtsProvider.COMPOSITE, TtsEventKind.LOCAL_FALLBACK)
        }
    }
    data class AudioFocusEvent(val provider: TtsProvider, val result: AudioFocusResult) : DiagnosticEvent
}
