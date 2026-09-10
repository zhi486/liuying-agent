package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.core.model.SensorSample
import com.healthyagent.running.diagnostics.BleFailureReason
import com.healthyagent.running.diagnostics.BleReconnectStage
import com.healthyagent.running.diagnostics.BleResumeDecision
import com.healthyagent.running.diagnostics.BleStaleEventKind
import com.healthyagent.running.diagnostics.BleSyncKind as DiagnosticSyncKind
import com.healthyagent.running.diagnostics.BleWriteResult
import com.healthyagent.running.diagnostics.DiagnosticBleLifecycle
import com.healthyagent.running.diagnostics.DiagnosticBleLink
import com.healthyagent.running.diagnostics.DiagnosticEvent

internal enum class KeepB5Lifecycle { STOPPED, RUNNING, PAUSED }

internal enum class KeepB5ChannelKind { STANDARD_HR, PRIVATE_B3 }

internal enum class KeepB5ConnectionPhase {
    SCANNING,
    GATT_CONNECTING,
    DISCOVERING_SERVICES,
    ENABLING_NOTIFICATIONS,
    START_PENDING,
    AWAITING_PRIVATE_ACTIVITY,
}

internal sealed interface KeepB5LinkState {
    data object Disconnected : KeepB5LinkState
    data object PreparedPrivate : KeepB5LinkState
    data class Connecting(val phase: KeepB5ConnectionPhase) : KeepB5LinkState
    data class Ready(val channel: KeepB5ChannelKind) : KeepB5LinkState
}

internal data class KeepB5WriteGuard(
    val epoch: Long,
    val connectionAttemptToken: Long,
    val gattToken: Long,
    val lifecycleRevision: Long,
)

internal data class KeepB5SubmittedWrite(
    val operationId: Long,
    val gattGuard: KeepB5GattGuard,
    val lifecycleRevisionAtSubmission: Long,
    val kind: KeepB5SyncKind,
)

internal data class KeepB5AttemptGuard(
    val epoch: Long,
    val connectionAttemptToken: Long,
)

internal data class KeepB5GattGuard(
    val epoch: Long,
    val connectionAttemptToken: Long,
    val gattToken: Long,
)

internal data class PrivateWorkoutActivity(
    val guard: KeepB5GattGuard,
    val receivedAtMillis: Long,
)

internal data class TokenizedSensorSample(
    val guard: KeepB5GattGuard,
    val sample: SensorSample,
    val receivedAtMillis: Long,
)

internal sealed interface KeepB5SessionEvent {
    data object PrepareConnection : KeepB5SessionEvent
    data object ExplicitConnect : KeepB5SessionEvent
    data object ExplicitDisconnect : KeepB5SessionEvent
    data class RunStarted(val elapsedSeconds: Long) : KeepB5SessionEvent
    data class RunPaused(val elapsedSeconds: Long) : KeepB5SessionEvent
    data class RunResumed(val elapsedSeconds: Long) : KeepB5SessionEvent
    data class MetricsUpdated(val metrics: KeepB5DisplayMetrics) : KeepB5SessionEvent
    data object RunStopped : KeepB5SessionEvent
    data class ScanMatched(val guard: KeepB5AttemptGuard) : KeepB5SessionEvent
    data class ScanFailed(val guard: KeepB5AttemptGuard, val reason: BleFailureReason) : KeepB5SessionEvent
    data class ConnectFailed(val guard: KeepB5AttemptGuard, val reason: BleFailureReason) : KeepB5SessionEvent
    data class GattCreated(val guard: KeepB5GattGuard) : KeepB5SessionEvent
    data class GattConnected(val guard: KeepB5GattGuard) : KeepB5SessionEvent
    data class ServicesDiscovered(val guard: KeepB5GattGuard, val channel: KeepB5ChannelKind) : KeepB5SessionEvent
    data class NotificationsEnabled(val guard: KeepB5GattGuard, val channel: KeepB5ChannelKind) : KeepB5SessionEvent
    data class StartWriteCompleted(val guard: KeepB5GattGuard, val accepted: Boolean) : KeepB5SessionEvent
    data class SyncWriteCompleted(val submission: KeepB5SubmittedWrite, val accepted: Boolean) : KeepB5SessionEvent
    data class PrivateActivity(val activity: PrivateWorkoutActivity) : KeepB5SessionEvent
    data class SensorSampleReceived(val sample: TokenizedSensorSample) : KeepB5SessionEvent
    data class DeviceInfoReceived(val guard: KeepB5GattGuard, val deviceInfo: DeviceInfo) : KeepB5SessionEvent
    data class ReadyTimeout(val guard: KeepB5GattGuard) : KeepB5SessionEvent
    data class LinkFailed(val guard: KeepB5GattGuard, val reason: BleFailureReason) : KeepB5SessionEvent
    data class ReconnectDue(val guard: KeepB5AttemptGuard) : KeepB5SessionEvent
    data class SyncDue(val pollGuard: KeepB5WriteGuard, val due: List<KeepB5SyncDue>) : KeepB5SessionEvent
}

internal sealed interface KeepB5SessionAction {
    data class BeginScan(val guard: KeepB5AttemptGuard) : KeepB5SessionAction
    data class StopScan(
        val guard: KeepB5AttemptGuard,
        val preserveMatchedTarget: Boolean = false,
    ) : KeepB5SessionAction
    data class ConnectRemembered(val guard: KeepB5AttemptGuard) : KeepB5SessionAction
    data class CancelPendingConnect(val guard: KeepB5AttemptGuard) : KeepB5SessionAction
    data class ActivateGattData(val guard: KeepB5GattGuard) : KeepB5SessionAction
    data class SendStart(val guard: KeepB5GattGuard) : KeepB5SessionAction
    data class StartSync(val guard: KeepB5GattGuard) : KeepB5SessionAction
    data class ResetSyncUpdateAccounting(val guard: KeepB5GattGuard) : KeepB5SessionAction
    data class StopSync(val gattToken: Long?) : KeepB5SessionAction
    data class SendWorkoutUpdate(val guard: KeepB5WriteGuard, val due: KeepB5SyncDue) : KeepB5SessionAction
    data class SendHeartbeat(val guard: KeepB5WriteGuard) : KeepB5SessionAction
    data class ArmReadyTimeout(val guard: KeepB5GattGuard, val delayMillis: Long = 20_000L) : KeepB5SessionAction
    data class CancelReadyTimeout(val guard: KeepB5GattGuard) : KeepB5SessionAction
    data class ScheduleReconnect(val guard: KeepB5AttemptGuard, val reason: BleFailureReason) : KeepB5SessionAction
    data class CancelReconnect(val epoch: Long, val connectionAttemptToken: Long?) : KeepB5SessionAction
    data class CloseGatt(val guard: KeepB5GattGuard, val disconnectFirst: Boolean) : KeepB5SessionAction
    data class PublishConnectionState(val state: ConnectionState) : KeepB5SessionAction
    data class PublishSample(val sample: TokenizedSensorSample) : KeepB5SessionAction
    data class PublishDeviceInfo(val guard: KeepB5GattGuard, val deviceInfo: DeviceInfo) : KeepB5SessionAction
    data class RecordDiagnostic(val event: DiagnosticEvent) : KeepB5SessionAction
}

internal sealed interface KeepB5ActionResolution {
    data object Allowed : KeepB5ActionResolution
    data class Rejected(val diagnostic: DiagnosticEvent) : KeepB5ActionResolution
}

internal sealed interface KeepB5WriteResolution {
    data class Allowed(
        val submission: KeepB5SubmittedWrite,
        val elapsedSeconds: Long?,
        val displayMetrics: KeepB5DisplayMetrics = KeepB5DisplayMetrics(),
    ) : KeepB5WriteResolution
    data class Rejected(val diagnostic: DiagnosticEvent) : KeepB5WriteResolution
}

internal class KeepB5SessionSupervisor(
    private val privateActivityStaleAfterMillis: Long = 12_000L,
) {
    private var lifecycle = KeepB5Lifecycle.STOPPED
    private var linkState: KeepB5LinkState = KeepB5LinkState.Disconnected
    private var maintainConnection = false
    private var prepareOnly = false
    private var epoch = 0L
    private var connectionAttemptToken = 0L
    private var lifecycleRevision = 0L
    private var currentAttempt: KeepB5AttemptGuard? = null
    private var currentGatt: KeepB5GattGuard? = null
    private var syncGatt: KeepB5GattGuard? = null
    private var pendingChannel: KeepB5ChannelKind? = null
    private var privateFallbackUsed = false
    private var frozenElapsedSeconds = 0L
    private var displayMetrics = KeepB5DisplayMetrics()
    private var lastPrivateActivityAtMillis: Long? = null
    private var pendingPrivateActivity: PrivateWorkoutActivity? = null
    private val inFlightWrites = mutableMapOf<Long, KeepB5SubmittedWrite>()
    private val consecutiveSyncRejects = mutableMapOf<KeepB5SyncKind, Int>()
    private var nextWriteOperationId = 1L

    private companion object {
        const val MAX_CONSECUTIVE_SYNC_REJECTS = 3
    }

    fun handle(event: KeepB5SessionEvent, nowMillis: Long): List<KeepB5SessionAction> = when (event) {
        KeepB5SessionEvent.PrepareConnection -> handleExplicitConnect(preparing = true)
        KeepB5SessionEvent.ExplicitConnect -> handleExplicitConnect()
        KeepB5SessionEvent.ExplicitDisconnect -> handleTerminalDisconnect(changeLifecycle = false)
        is KeepB5SessionEvent.RunStarted -> {
            displayMetrics = KeepB5DisplayMetrics()
            handleLifecycleChange(
                KeepB5Lifecycle.RUNNING,
                event.elapsedSeconds,
                nowMillis,
                checkResumeHealth = false,
            )
        }
        is KeepB5SessionEvent.RunPaused -> handleLifecycleChange(
            KeepB5Lifecycle.PAUSED,
            event.elapsedSeconds,
            nowMillis,
            checkResumeHealth = false,
        )
        is KeepB5SessionEvent.RunResumed -> handleLifecycleChange(
            KeepB5Lifecycle.RUNNING,
            event.elapsedSeconds,
            nowMillis,
            checkResumeHealth = true,
        )
        is KeepB5SessionEvent.MetricsUpdated -> {
            if (lifecycle == KeepB5Lifecycle.RUNNING) displayMetrics = event.metrics
            emptyList()
        }
        KeepB5SessionEvent.RunStopped -> {
            displayMetrics = KeepB5DisplayMetrics()
            handleTerminalDisconnect(changeLifecycle = true)
        }
        is KeepB5SessionEvent.ScanMatched -> handleScanMatched(event.guard)
        is KeepB5SessionEvent.ScanFailed -> handleScanFailed(event.guard, event.reason)
        is KeepB5SessionEvent.ConnectFailed -> handleConnectFailed(event.guard, event.reason)
        is KeepB5SessionEvent.GattCreated -> handleGattCreated(event.guard)
        is KeepB5SessionEvent.GattConnected -> handleGattConnected(event.guard)
        is KeepB5SessionEvent.ServicesDiscovered -> handleServicesDiscovered(event.guard, event.channel)
        is KeepB5SessionEvent.NotificationsEnabled -> handleNotificationsEnabled(event.guard, event.channel)
        is KeepB5SessionEvent.StartWriteCompleted -> handleStartWriteCompleted(event.guard, event.accepted)
        is KeepB5SessionEvent.SyncWriteCompleted -> handleSyncWriteCompleted(event.submission, event.accepted)
        is KeepB5SessionEvent.PrivateActivity -> handlePrivateActivity(event.activity)
        is KeepB5SessionEvent.SensorSampleReceived -> handleSensorSample(event.sample)
        is KeepB5SessionEvent.DeviceInfoReceived -> handleDeviceInfo(event.guard, event.deviceInfo)
        is KeepB5SessionEvent.ReadyTimeout -> handleReadyTimeout(event.guard)
        is KeepB5SessionEvent.LinkFailed -> handleLinkFailed(event.guard, event.reason)
        is KeepB5SessionEvent.ReconnectDue -> handleReconnectDue(event.guard)
        is KeepB5SessionEvent.SyncDue -> handleSyncDue(event.pollGuard, event.due)
    }

    fun captureSyncPollGuard(guard: KeepB5GattGuard): KeepB5WriteGuard? {
        if (!maintainConnection || currentGatt != guard || syncGatt != guard) return null
        return KeepB5WriteGuard(
            guard.epoch,
            guard.connectionAttemptToken,
            guard.gattToken,
            lifecycleRevision,
        )
    }

    fun resolveSyncWrite(guard: KeepB5WriteGuard, due: KeepB5SyncDue): KeepB5WriteResolution {
        val gattGuard = guard.toGattGuard()
        if (!maintainConnection || currentGatt != gattGuard || syncGatt != gattGuard) {
            return KeepB5WriteResolution.Rejected(stale(BleStaleEventKind.SYNC_WRITE, gattGuard))
        }
        if (guard.lifecycleRevision != lifecycleRevision) {
            return KeepB5WriteResolution.Rejected(
                DiagnosticEvent.BleStaleWriteIgnored(due.kind.toDiagnostic(), guard.lifecycleRevision),
            )
        }

        val elapsedSeconds = when (due.kind) {
            KeepB5SyncKind.UPDATE_WORKOUT -> {
                if (lifecycle == KeepB5Lifecycle.RUNNING) {
                    frozenElapsedSeconds += due.elapsedIntervals.coerceAtLeast(0L)
                }
                frozenElapsedSeconds
            }
            KeepB5SyncKind.HEART_BEAT -> null
        }
        val submission = KeepB5SubmittedWrite(
            operationId = nextWriteOperationId++,
            gattGuard = gattGuard,
            lifecycleRevisionAtSubmission = guard.lifecycleRevision,
            kind = due.kind,
        )
        inFlightWrites[submission.operationId] = submission
        return KeepB5WriteResolution.Allowed(submission, elapsedSeconds, displayMetrics)
    }

    fun resolveGuardedAction(action: KeepB5SessionAction): KeepB5ActionResolution {
        val allowed = when (action) {
            is KeepB5SessionAction.BeginScan -> maintainConnection &&
                currentAttempt == action.guard &&
                linkState == KeepB5LinkState.Connecting(KeepB5ConnectionPhase.SCANNING)
            is KeepB5SessionAction.ConnectRemembered -> maintainConnection &&
                currentAttempt == action.guard &&
                currentGatt == null &&
                linkState == KeepB5LinkState.Connecting(KeepB5ConnectionPhase.GATT_CONNECTING)
            is KeepB5SessionAction.SendStart -> maintainConnection &&
                currentGatt == action.guard &&
                pendingChannel == KeepB5ChannelKind.PRIVATE_B3 &&
                linkState == KeepB5LinkState.Connecting(KeepB5ConnectionPhase.START_PENDING)
            is KeepB5SessionAction.StartSync -> maintainConnection &&
                currentGatt == action.guard &&
                syncGatt == action.guard &&
                isPrivateSyncPhase()
            is KeepB5SessionAction.ArmReadyTimeout -> maintainConnection &&
                currentGatt == action.guard &&
                linkState != KeepB5LinkState.PreparedPrivate &&
                linkState !is KeepB5LinkState.Ready
            is KeepB5SessionAction.ScheduleReconnect -> maintainConnection &&
                currentAttempt == action.guard &&
                linkState == KeepB5LinkState.Disconnected
            is KeepB5SessionAction.PublishSample -> publicationAllowed(action.sample.guard)
            is KeepB5SessionAction.PublishDeviceInfo -> publicationAllowed(action.guard)
            is KeepB5SessionAction.SendWorkoutUpdate -> writeActionAllowed(action.guard)
            is KeepB5SessionAction.SendHeartbeat -> writeActionAllowed(action.guard)
            else -> true
        }
        if (allowed) return KeepB5ActionResolution.Allowed

        val publication = action is KeepB5SessionAction.PublishSample ||
            action is KeepB5SessionAction.PublishDeviceInfo
        return KeepB5ActionResolution.Rejected(
            action.staleDiagnostic(
                if (publication) BleStaleEventKind.GATT_CALLBACK else BleStaleEventKind.CONTROL_ACTION,
            ),
        )
    }

    private fun handleExplicitConnect(preparing: Boolean = false): List<KeepB5SessionAction> {
        prepareOnly = preparing && lifecycle == KeepB5Lifecycle.STOPPED
        val gatt = currentGatt
        if (!prepareOnly && gatt != null && linkState == KeepB5LinkState.PreparedPrivate) {
            return startPreparedWorkout(gatt)
        }
        if (preparing && maintainConnection && currentGatt == null && linkState == KeepB5LinkState.Disconnected) {
            return startNewAttempt()
        }
        if (maintainConnection) return emptyList()
        maintainConnection = true
        return if (currentGatt == null && linkState == KeepB5LinkState.Disconnected) {
            startNewAttempt()
        } else {
            emptyList()
        }
    }

    private fun handleLifecycleChange(
        next: KeepB5Lifecycle,
        elapsedSeconds: Long,
        nowMillis: Long,
        checkResumeHealth: Boolean,
    ): List<KeepB5SessionAction> {
        val previous = lifecycle
        lifecycle = next
        prepareOnly = false
        lifecycleRevision++
        frozenElapsedSeconds = elapsedSeconds.coerceAtLeast(0L)
        // 暂停和恢复均先清空旧配速；累计距离跨暂停及链路重连保留。
        displayMetrics = displayMetrics.copy(paceSecPerKm = 0)
        maintainConnection = true

        val actions = mutableListOf<KeepB5SessionAction>(
            KeepB5SessionAction.RecordDiagnostic(
                DiagnosticEvent.BleLifecycleChanged(
                    previous.toDiagnostic(),
                    next.toDiagnostic(),
                    epoch,
                    lifecycleRevision,
                ),
            ),
        )
        val gatt = currentGatt
        if (gatt != null && linkState == KeepB5LinkState.PreparedPrivate) {
            return actions + startPreparedWorkout(gatt)
        }
        val ready = linkState as? KeepB5LinkState.Ready
        if (checkResumeHealth && ready?.channel == KeepB5ChannelKind.PRIVATE_B3 && gatt != null) {
            val activityAge = lastPrivateActivityAtMillis?.let { (nowMillis - it).coerceAtLeast(0L) }
            if (activityAge == null || activityAge > privateActivityStaleAfterMillis) {
                actions += KeepB5SessionAction.RecordDiagnostic(
                    DiagnosticEvent.BleResumeHealth(BleResumeDecision.STALE, activityAge),
                )
                actions += failCurrentGatt(BleFailureReason.GATT_ERROR)
                return actions
            }
            actions += KeepB5SessionAction.RecordDiagnostic(
                DiagnosticEvent.BleResumeHealth(BleResumeDecision.HEALTHY, activityAge),
            )
        } else if (checkResumeHealth && ready?.channel == KeepB5ChannelKind.STANDARD_HR) {
            actions += KeepB5SessionAction.RecordDiagnostic(
                DiagnosticEvent.BleResumeHealth(BleResumeDecision.STANDARD_HR_SKIPPED, null),
            )
        }
        if (gatt != null && syncGatt == gatt) {
            actions += KeepB5SessionAction.ResetSyncUpdateAccounting(gatt)
        }
        if (currentGatt == null && linkState == KeepB5LinkState.Disconnected && currentAttempt == null) {
            actions += startNewAttempt()
        }
        return actions
    }

    private fun handleTerminalDisconnect(changeLifecycle: Boolean): List<KeepB5SessionAction> {
        prepareOnly = false
        val previousLifecycle = lifecycle
        if (changeLifecycle && lifecycle != KeepB5Lifecycle.STOPPED) {
            // 手动断开后再结束跑步，也必须先关闭指标接收，不能被下面的无资源分支跳过。
            lifecycle = KeepB5Lifecycle.STOPPED
            lifecycleRevision++
        }
        if (!maintainConnection && currentGatt == null && currentAttempt == null) return emptyList()

        val oldEpoch = epoch
        val oldAttempt = currentAttempt
        val oldGatt = currentGatt
        maintainConnection = false
        epoch++
        clearGattState()
        currentAttempt = null
        linkState = KeepB5LinkState.Disconnected

        return buildList {
            if (changeLifecycle) {
                add(
                    KeepB5SessionAction.RecordDiagnostic(
                        DiagnosticEvent.BleLifecycleChanged(
                            previousLifecycle.toDiagnostic(),
                            DiagnosticBleLifecycle.STOPPED,
                            epoch,
                            lifecycleRevision,
                        ),
                    ),
                )
            }
            add(KeepB5SessionAction.CancelReconnect(oldEpoch, oldAttempt?.connectionAttemptToken))
            oldAttempt?.let {
                add(KeepB5SessionAction.StopScan(it))
                add(KeepB5SessionAction.CancelPendingConnect(it))
            }
            add(KeepB5SessionAction.StopSync(oldGatt?.gattToken))
            oldGatt?.let { add(KeepB5SessionAction.CancelReadyTimeout(it)) }
            add(KeepB5SessionAction.PublishConnectionState(ConnectionState.DISCONNECTED))
            oldGatt?.let { add(KeepB5SessionAction.CloseGatt(it, disconnectFirst = true)) }
        }
    }

    private fun handleScanMatched(guard: KeepB5AttemptGuard): List<KeepB5SessionAction> {
        if (!attemptIsCurrent(guard, KeepB5ConnectionPhase.SCANNING)) {
            return listOf(recordStale(BleStaleEventKind.SCAN_RESULT, guard))
        }
        linkState = KeepB5LinkState.Connecting(KeepB5ConnectionPhase.GATT_CONNECTING)
        return listOf(
            KeepB5SessionAction.StopScan(guard, preserveMatchedTarget = true),
            KeepB5SessionAction.ConnectRemembered(guard),
        )
    }

    private fun handleScanFailed(
        guard: KeepB5AttemptGuard,
        reason: BleFailureReason,
    ): List<KeepB5SessionAction> {
        if (!attemptIsCurrent(guard, KeepB5ConnectionPhase.SCANNING)) {
            val kind = if (reason == BleFailureReason.SCAN_TIMEOUT) {
                BleStaleEventKind.SCAN_TIMEOUT
            } else {
                BleStaleEventKind.SCAN_RESULT
            }
            return listOf(recordStale(kind, guard))
        }
        return listOf(KeepB5SessionAction.StopScan(guard)) + failCurrentAttempt(guard, reason)
    }

    private fun handleConnectFailed(
        guard: KeepB5AttemptGuard,
        reason: BleFailureReason,
    ): List<KeepB5SessionAction> {
        if (!attemptIsCurrent(guard, KeepB5ConnectionPhase.GATT_CONNECTING) || currentGatt != null) {
            return listOf(recordStale(BleStaleEventKind.GATT_CALLBACK, guard))
        }
        return failCurrentAttempt(guard, reason)
    }

    private fun handleGattCreated(guard: KeepB5GattGuard): List<KeepB5SessionAction> {
        if (currentGatt == guard) return listOf(recordGattStale(guard))
        if (!attemptIsCurrent(guard.toAttemptGuard(), KeepB5ConnectionPhase.GATT_CONNECTING) || currentGatt != null) {
            return listOf(
                recordStale(BleStaleEventKind.GATT_CALLBACK, guard),
                KeepB5SessionAction.CloseGatt(guard, disconnectFirst = true),
            )
        }
        currentGatt = guard
        pendingChannel = null
        privateFallbackUsed = false
        lastPrivateActivityAtMillis = null
        pendingPrivateActivity = null
        return listOf(
            KeepB5SessionAction.ActivateGattData(guard),
            KeepB5SessionAction.ArmReadyTimeout(guard),
        )
    }

    private fun handleGattConnected(guard: KeepB5GattGuard): List<KeepB5SessionAction> {
        if (!gattIsCurrent(guard, KeepB5ConnectionPhase.GATT_CONNECTING)) return listOf(recordGattStale(guard))
        linkState = KeepB5LinkState.Connecting(KeepB5ConnectionPhase.DISCOVERING_SERVICES)
        return emptyList()
    }

    private fun handleServicesDiscovered(
        guard: KeepB5GattGuard,
        channel: KeepB5ChannelKind,
    ): List<KeepB5SessionAction> {
        if (currentGatt != guard) return listOf(recordGattStale(guard))
        val phase = (linkState as? KeepB5LinkState.Connecting)?.phase
        if (phase == KeepB5ConnectionPhase.DISCOVERING_SERVICES) {
            pendingChannel = channel
            linkState = KeepB5LinkState.Connecting(KeepB5ConnectionPhase.ENABLING_NOTIFICATIONS)
            return emptyList()
        }
        if (
            phase == KeepB5ConnectionPhase.ENABLING_NOTIFICATIONS &&
            pendingChannel == KeepB5ChannelKind.STANDARD_HR &&
            channel == KeepB5ChannelKind.PRIVATE_B3 &&
            !privateFallbackUsed
        ) {
            pendingChannel = KeepB5ChannelKind.PRIVATE_B3
            privateFallbackUsed = true
            return emptyList()
        }
        return listOf(recordGattStale(guard))
    }

    private fun handleNotificationsEnabled(
        guard: KeepB5GattGuard,
        channel: KeepB5ChannelKind,
    ): List<KeepB5SessionAction> {
        if (
            !gattIsCurrent(guard, KeepB5ConnectionPhase.ENABLING_NOTIFICATIONS) ||
            pendingChannel != channel
        ) {
            return listOf(recordGattStale(guard))
        }
        return when (channel) {
            KeepB5ChannelKind.STANDARD_HR -> {
                pendingChannel = null
                linkState = KeepB5LinkState.Ready(KeepB5ChannelKind.STANDARD_HR)
                listOf(
                    KeepB5SessionAction.CancelReadyTimeout(guard),
                    KeepB5SessionAction.PublishConnectionState(ConnectionState.CONNECTED),
                    recordLinkChanged(
                        DiagnosticBleLink.CONNECTING,
                        DiagnosticBleLink.READY_STANDARD_HR,
                        guard,
                    ),
                )
            }
            KeepB5ChannelKind.PRIVATE_B3 -> {
                if (prepareOnly) {
                    linkState = KeepB5LinkState.PreparedPrivate
                    listOf(
                        KeepB5SessionAction.CancelReadyTimeout(guard),
                        KeepB5SessionAction.PublishConnectionState(ConnectionState.CONNECTED),
                        recordLinkChanged(DiagnosticBleLink.CONNECTING, DiagnosticBleLink.PREPARED_PRIVATE_B3, guard),
                    )
                } else {
                    linkState = KeepB5LinkState.Connecting(KeepB5ConnectionPhase.START_PENDING)
                    listOf(KeepB5SessionAction.SendStart(guard))
                }
            }
        }
    }

    private fun startPreparedWorkout(guard: KeepB5GattGuard): List<KeepB5SessionAction> {
        linkState = KeepB5LinkState.Connecting(KeepB5ConnectionPhase.START_PENDING)
        return listOf(
            recordLinkChanged(DiagnosticBleLink.PREPARED_PRIVATE_B3, DiagnosticBleLink.CONNECTING, guard),
            KeepB5SessionAction.PublishConnectionState(ConnectionState.CONNECTING),
            KeepB5SessionAction.ArmReadyTimeout(guard),
            KeepB5SessionAction.SendStart(guard),
        )
    }

    private fun handleStartWriteCompleted(
        guard: KeepB5GattGuard,
        accepted: Boolean,
    ): List<KeepB5SessionAction> {
        if (!gattIsCurrent(guard, KeepB5ConnectionPhase.START_PENDING)) return listOf(recordGattStale(guard))
        if (!accepted) return failCurrentGatt(BleFailureReason.START_WRITE_REJECTED)

        syncGatt = guard
        linkState = KeepB5LinkState.Connecting(KeepB5ConnectionPhase.AWAITING_PRIVATE_ACTIVITY)
        val actions = mutableListOf<KeepB5SessionAction>(KeepB5SessionAction.StartSync(guard))
        pendingPrivateActivity?.takeIf { it.guard == guard }?.let {
            pendingPrivateActivity = null
            acceptPrivateActivity(it, actions)
        }
        return actions
    }

    private fun handleSyncWriteCompleted(
        submission: KeepB5SubmittedWrite,
        accepted: Boolean,
    ): List<KeepB5SessionAction> {
        val registered = inFlightWrites.remove(submission.operationId)
        if (currentGatt != submission.gattGuard || registered != submission) {
            return listOf(recordStale(BleStaleEventKind.SYNC_WRITE, submission.gattGuard))
        }
        val writeDiagnostic = KeepB5SessionAction.RecordDiagnostic(
            DiagnosticEvent.BleSyncWrite(
                submission.kind.toDiagnostic(),
                if (accepted) BleWriteResult.ACCEPTED else BleWriteResult.REJECTED,
                submission.gattGuard.epoch,
                submission.gattGuard.gattToken,
            ),
        )
        if (accepted) {
            consecutiveSyncRejects.remove(submission.kind)
            return listOf(writeDiagnostic)
        }
        // 一次提交拒绝不等于链路断开；下一正常周期发送最新指标，不重放旧值或 START。
        // 分别计数，避免频繁成功的 UPDATE 掩盖连续失败的心跳。
        val rejectedCount = (consecutiveSyncRejects[submission.kind] ?: 0) + 1
        consecutiveSyncRejects[submission.kind] = rejectedCount
        if (rejectedCount < MAX_CONSECUTIVE_SYNC_REJECTS) return listOf(writeDiagnostic)
        return listOf(writeDiagnostic) + failCurrentGatt(BleFailureReason.SYNC_WRITE_REJECTED)
    }

    private fun handlePrivateActivity(activity: PrivateWorkoutActivity): List<KeepB5SessionAction> {
        if (currentGatt != activity.guard || pendingChannel != KeepB5ChannelKind.PRIVATE_B3) {
            return listOf(recordGattStale(activity.guard))
        }
        return when (linkState) {
            KeepB5LinkState.Connecting(KeepB5ConnectionPhase.START_PENDING) -> {
                val previous = pendingPrivateActivity
                if (previous == null || activity.receivedAtMillis > previous.receivedAtMillis) {
                    pendingPrivateActivity = activity
                }
                emptyList()
            }
            KeepB5LinkState.Connecting(KeepB5ConnectionPhase.AWAITING_PRIVATE_ACTIVITY),
            KeepB5LinkState.Ready(KeepB5ChannelKind.PRIVATE_B3),
            -> buildList { acceptPrivateActivity(activity, this) }
            else -> listOf(recordGattStale(activity.guard))
        }
    }

    private fun handleSensorSample(sample: TokenizedSensorSample): List<KeepB5SessionAction> =
        if (publicationAllowed(sample.guard)) {
            listOf(KeepB5SessionAction.PublishSample(sample))
        } else {
            listOf(recordGattStale(sample.guard))
        }

    private fun handleDeviceInfo(
        guard: KeepB5GattGuard,
        deviceInfo: DeviceInfo,
    ): List<KeepB5SessionAction> = if (publicationAllowed(guard)) {
        listOf(KeepB5SessionAction.PublishDeviceInfo(guard, deviceInfo))
    } else {
        listOf(recordGattStale(guard))
    }

    private fun handleReadyTimeout(guard: KeepB5GattGuard): List<KeepB5SessionAction> {
        if (currentGatt != guard || linkState is KeepB5LinkState.Ready || linkState == KeepB5LinkState.PreparedPrivate) {
            return listOf(recordGattStale(guard))
        }
        return failCurrentGatt(BleFailureReason.READY_TIMEOUT)
    }

    private fun handleLinkFailed(
        guard: KeepB5GattGuard,
        reason: BleFailureReason,
    ): List<KeepB5SessionAction> = if (currentGatt == guard) {
        failCurrentGatt(reason)
    } else {
        listOf(recordGattStale(guard))
    }

    private fun handleReconnectDue(guard: KeepB5AttemptGuard): List<KeepB5SessionAction> {
        if (!maintainConnection || currentAttempt != guard || linkState != KeepB5LinkState.Disconnected) {
            return listOf(recordStale(BleStaleEventKind.RECONNECT, guard))
        }
        linkState = KeepB5LinkState.Connecting(KeepB5ConnectionPhase.SCANNING)
        return listOf(
            KeepB5SessionAction.RecordDiagnostic(
                DiagnosticEvent.BleReconnect(
                    BleReconnectStage.STARTED,
                    guard.epoch,
                    guard.connectionAttemptToken,
                ),
            ),
            KeepB5SessionAction.PublishConnectionState(ConnectionState.CONNECTING),
            KeepB5SessionAction.BeginScan(guard),
        )
    }

    private fun handleSyncDue(
        pollGuard: KeepB5WriteGuard,
        due: List<KeepB5SyncDue>,
    ): List<KeepB5SessionAction> {
        if (due.isEmpty()) return emptyList()
        val gattGuard = pollGuard.toGattGuard()
        if (!maintainConnection || currentGatt != gattGuard || syncGatt != gattGuard) {
            return listOf(recordStale(BleStaleEventKind.SYNC_WRITE, gattGuard))
        }
        if (pollGuard.lifecycleRevision != lifecycleRevision) {
            return listOf(
                KeepB5SessionAction.RecordDiagnostic(
                    DiagnosticEvent.BleStaleWriteIgnored(
                        due.first().kind.toDiagnostic(),
                        pollGuard.lifecycleRevision,
                    ),
                ),
            )
        }
        return due.map {
            when (it.kind) {
                KeepB5SyncKind.UPDATE_WORKOUT -> KeepB5SessionAction.SendWorkoutUpdate(pollGuard, it)
                KeepB5SyncKind.HEART_BEAT -> KeepB5SessionAction.SendHeartbeat(pollGuard)
            }
        }
    }

    private fun startNewAttempt(): List<KeepB5SessionAction> {
        val oldAttempt = currentAttempt
        val oldEpoch = epoch
        connectionAttemptToken++
        val guard = KeepB5AttemptGuard(epoch, connectionAttemptToken)
        currentAttempt = guard
        linkState = KeepB5LinkState.Connecting(KeepB5ConnectionPhase.SCANNING)
        return buildList {
            add(KeepB5SessionAction.CancelReconnect(oldEpoch, oldAttempt?.connectionAttemptToken))
            oldAttempt?.let {
                add(KeepB5SessionAction.StopScan(it))
                add(KeepB5SessionAction.CancelPendingConnect(it))
            }
            add(KeepB5SessionAction.PublishConnectionState(ConnectionState.CONNECTING))
            add(KeepB5SessionAction.BeginScan(guard))
        }
    }

    private fun failCurrentAttempt(
        oldGuard: KeepB5AttemptGuard,
        reason: BleFailureReason,
    ): List<KeepB5SessionAction> {
        connectionAttemptToken++
        val retryGuard = KeepB5AttemptGuard(epoch, connectionAttemptToken)
        currentAttempt = retryGuard
        linkState = KeepB5LinkState.Disconnected
        return listOf(
            KeepB5SessionAction.CancelPendingConnect(oldGuard),
            KeepB5SessionAction.PublishConnectionState(ConnectionState.ERROR),
            KeepB5SessionAction.RecordDiagnostic(
                DiagnosticEvent.BleReconnect(
                    BleReconnectStage.SCHEDULED,
                    retryGuard.epoch,
                    retryGuard.connectionAttemptToken,
                    reason,
                ),
            ),
            KeepB5SessionAction.ScheduleReconnect(retryGuard, reason),
        )
    }

    private fun failCurrentGatt(reason: BleFailureReason): List<KeepB5SessionAction> {
        val oldGatt = requireNotNull(currentGatt)
        val oldLink = linkState.toDiagnostic()
        clearGattState()
        connectionAttemptToken++
        val retryGuard = KeepB5AttemptGuard(epoch, connectionAttemptToken)
        currentAttempt = retryGuard
        linkState = KeepB5LinkState.Disconnected
        return listOf(
            KeepB5SessionAction.RecordDiagnostic(
                DiagnosticEvent.BleLinkChanged(
                    oldLink,
                    DiagnosticBleLink.DISCONNECTED,
                    oldGatt.connectionAttemptToken,
                    oldGatt.gattToken,
                    reason,
                ),
            ),
            KeepB5SessionAction.RecordDiagnostic(
                DiagnosticEvent.BleReconnect(
                    BleReconnectStage.SCHEDULED,
                    retryGuard.epoch,
                    retryGuard.connectionAttemptToken,
                    reason,
                ),
            ),
            KeepB5SessionAction.StopSync(oldGatt.gattToken),
            KeepB5SessionAction.CancelReadyTimeout(oldGatt),
            KeepB5SessionAction.CloseGatt(oldGatt, disconnectFirst = true),
            KeepB5SessionAction.PublishConnectionState(ConnectionState.ERROR),
            KeepB5SessionAction.ScheduleReconnect(retryGuard, reason),
        )
    }

    private fun clearGattState() {
        currentGatt = null
        syncGatt = null
        pendingChannel = null
        privateFallbackUsed = false
        lastPrivateActivityAtMillis = null
        pendingPrivateActivity = null
        inFlightWrites.clear()
        consecutiveSyncRejects.clear()
    }

    private fun acceptPrivateActivity(
        activity: PrivateWorkoutActivity,
        actions: MutableList<KeepB5SessionAction>,
    ) {
        lastPrivateActivityAtMillis = maxOf(
            lastPrivateActivityAtMillis ?: Long.MIN_VALUE,
            activity.receivedAtMillis,
        )
        if (linkState == KeepB5LinkState.Connecting(KeepB5ConnectionPhase.AWAITING_PRIVATE_ACTIVITY)) {
            linkState = KeepB5LinkState.Ready(KeepB5ChannelKind.PRIVATE_B3)
            actions += KeepB5SessionAction.CancelReadyTimeout(activity.guard)
            actions += KeepB5SessionAction.PublishConnectionState(ConnectionState.CONNECTED)
            actions += recordLinkChanged(
                DiagnosticBleLink.CONNECTING,
                DiagnosticBleLink.READY_PRIVATE_B3,
                activity.guard,
            )
        }
    }

    private fun attemptIsCurrent(
        guard: KeepB5AttemptGuard,
        phase: KeepB5ConnectionPhase,
    ) = maintainConnection && currentAttempt == guard && linkState == KeepB5LinkState.Connecting(phase)

    private fun gattIsCurrent(
        guard: KeepB5GattGuard,
        phase: KeepB5ConnectionPhase,
    ) = maintainConnection && currentGatt == guard && linkState == KeepB5LinkState.Connecting(phase)

    private fun publicationAllowed(guard: KeepB5GattGuard) = maintainConnection && currentGatt == guard

    private fun writeActionAllowed(guard: KeepB5WriteGuard) = maintainConnection &&
        currentGatt == guard.toGattGuard() &&
        syncGatt == guard.toGattGuard() &&
        lifecycleRevision == guard.lifecycleRevision

    private fun isPrivateSyncPhase() =
        linkState == KeepB5LinkState.Connecting(KeepB5ConnectionPhase.AWAITING_PRIVATE_ACTIVITY) ||
            linkState == KeepB5LinkState.Ready(KeepB5ChannelKind.PRIVATE_B3)

    private fun recordGattStale(guard: KeepB5GattGuard) =
        KeepB5SessionAction.RecordDiagnostic(stale(BleStaleEventKind.GATT_CALLBACK, guard))

    private fun recordStale(kind: BleStaleEventKind, guard: KeepB5AttemptGuard) =
        KeepB5SessionAction.RecordDiagnostic(stale(kind, guard))

    private fun recordStale(kind: BleStaleEventKind, guard: KeepB5GattGuard) =
        KeepB5SessionAction.RecordDiagnostic(stale(kind, guard))

    private fun recordLinkChanged(
        from: DiagnosticBleLink,
        to: DiagnosticBleLink,
        guard: KeepB5GattGuard,
    ) = KeepB5SessionAction.RecordDiagnostic(
        DiagnosticEvent.BleLinkChanged(
            from,
            to,
            guard.connectionAttemptToken,
            guard.gattToken,
        ),
    )

    private fun stale(kind: BleStaleEventKind, guard: KeepB5AttemptGuard) =
        DiagnosticEvent.BleStaleEventIgnored(
            kind,
            guard.epoch,
            guard.connectionAttemptToken,
            null,
        )

    private fun stale(kind: BleStaleEventKind, guard: KeepB5GattGuard) =
        DiagnosticEvent.BleStaleEventIgnored(
            kind,
            guard.epoch,
            guard.connectionAttemptToken,
            guard.gattToken,
        )

    private fun KeepB5SessionAction.staleDiagnostic(kind: BleStaleEventKind) = when (this) {
        is KeepB5SessionAction.BeginScan -> stale(kind, guard)
        is KeepB5SessionAction.ConnectRemembered -> stale(kind, guard)
        is KeepB5SessionAction.ScheduleReconnect -> stale(kind, guard)
        is KeepB5SessionAction.SendStart -> stale(kind, guard)
        is KeepB5SessionAction.StartSync -> stale(kind, guard)
        is KeepB5SessionAction.ArmReadyTimeout -> stale(kind, guard)
        is KeepB5SessionAction.PublishSample -> stale(kind, sample.guard)
        is KeepB5SessionAction.PublishDeviceInfo -> stale(kind, guard)
        is KeepB5SessionAction.SendWorkoutUpdate -> stale(kind, guard.toGattGuard())
        is KeepB5SessionAction.SendHeartbeat -> stale(kind, guard.toGattGuard())
        else -> stale(kind, currentGatt ?: KeepB5GattGuard(epoch, connectionAttemptToken, 0L))
    }

    private fun KeepB5AttemptGuard.toGattGuard() = KeepB5GattGuard(epoch, connectionAttemptToken, 0L)
    private fun KeepB5GattGuard.toAttemptGuard() = KeepB5AttemptGuard(epoch, connectionAttemptToken)
    private fun KeepB5WriteGuard.toGattGuard() = KeepB5GattGuard(epoch, connectionAttemptToken, gattToken)

    private fun KeepB5SyncKind.toDiagnostic() = when (this) {
        KeepB5SyncKind.UPDATE_WORKOUT -> DiagnosticSyncKind.UPDATE_WORKOUT
        KeepB5SyncKind.HEART_BEAT -> DiagnosticSyncKind.HEART_BEAT
    }

    private fun KeepB5Lifecycle.toDiagnostic() = when (this) {
        KeepB5Lifecycle.STOPPED -> DiagnosticBleLifecycle.STOPPED
        KeepB5Lifecycle.RUNNING -> DiagnosticBleLifecycle.RUNNING
        KeepB5Lifecycle.PAUSED -> DiagnosticBleLifecycle.PAUSED
    }

    private fun KeepB5LinkState.toDiagnostic() = when (this) {
        KeepB5LinkState.PreparedPrivate -> DiagnosticBleLink.PREPARED_PRIVATE_B3
        KeepB5LinkState.Disconnected -> DiagnosticBleLink.DISCONNECTED
        is KeepB5LinkState.Connecting -> DiagnosticBleLink.CONNECTING
        is KeepB5LinkState.Ready -> when (channel) {
            KeepB5ChannelKind.STANDARD_HR -> DiagnosticBleLink.READY_STANDARD_HR
            KeepB5ChannelKind.PRIVATE_B3 -> DiagnosticBleLink.READY_PRIVATE_B3
        }
    }
}
