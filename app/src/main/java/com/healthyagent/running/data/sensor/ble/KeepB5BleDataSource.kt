package com.healthyagent.running.data.sensor.ble

import android.content.Context
import android.os.SystemClock
import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.core.model.SensorSample
import com.healthyagent.running.data.sensor.RunLifecycleAwareSensor
import com.healthyagent.running.data.sensor.SensorDataSource
import com.healthyagent.running.diagnostics.BleFailureReason
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.NoOpDiagnosticLogger
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Keep B5 BLE data source; all session transitions belong to [KeepB5SessionSupervisor]. */
class KeepB5BleDataSource internal constructor(
    private val transport: BleTransport,
    scope: CoroutineScope,
    private val monotonicMillis: () -> Long,
    private val epochSeconds: () -> Long,
    private val reconnectBackoff: ReconnectBackoffPolicy,
    private val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
) : SensorDataSource, RunLifecycleAwareSensor {
    constructor(context: Context, diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger) : this(
        transport = AndroidKeepB5BleTransport(context, diagnosticLogger),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        monotonicMillis = SystemClock::elapsedRealtime,
        epochSeconds = { System.currentTimeMillis() / 1_000L },
        reconnectBackoff = BleReconnectBackoff(1_000L, 30_000L),
        diagnosticLogger = diagnosticLogger,
    )

    private val reducerLock = Any()
    private val supervisor = KeepB5SessionSupervisor()
    private val sourceJob = SupervisorJob(scope.coroutineContext[Job])
    private val sourceScope = CoroutineScope(scope.coroutineContext + sourceJob)
    private val effects = Channel<EffectBatch>(Channel.UNLIMITED)
    private val sampleQueue = Channel<SensorSample>(Channel.UNLIMITED)
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _connectionState.asStateFlow()
    override val samples: Flow<SensorSample> = sampleQueue.receiveAsFlow()

    @Volatile private var deviceInfoSnapshot = DeviceInfo(name = "Keep B5")
    override val deviceInfo: DeviceInfo get() = deviceInfoSnapshot
    internal val resourceSnapshot: KeepB5BleDataSourceResourceSnapshot
        get() = KeepB5BleDataSourceResourceSnapshot(quiescedCallbackJobs = 0)

    private var activeScan: TimedAttempt? = null
    private var reconnect: TimedAttempt? = null
    private var readyTimeout: TimedGatt? = null
    private var sync: ActiveSync? = null

    init {
        sourceScope.launch { runEffects() }
        sourceScope.launch { transport.controlEvents.collect { reducerDispatch(it.toSessionEvent()) } }
        sourceScope.launch { transport.privateActivities.collect { reducerDispatch(KeepB5SessionEvent.PrivateActivity(it)) } }
        sourceScope.launch { transport.samples.collect { reducerDispatch(KeepB5SessionEvent.SensorSampleReceived(it)) } }
    }

    override suspend fun connect() = reducerDispatch(KeepB5SessionEvent.ExplicitConnect)
    override suspend fun prepareConnection() = reducerDispatch(KeepB5SessionEvent.PrepareConnection)
    override suspend fun disconnect() = reducerDispatch(KeepB5SessionEvent.ExplicitDisconnect)
    override fun onRunStarted(elapsedSeconds: Long) = reducerDispatch(KeepB5SessionEvent.RunStarted(elapsedSeconds))
    override fun onRunPaused(elapsedSeconds: Long) = reducerDispatch(KeepB5SessionEvent.RunPaused(elapsedSeconds))
    override fun onRunResumed(elapsedSeconds: Long) = reducerDispatch(KeepB5SessionEvent.RunResumed(elapsedSeconds))
    override fun onRunStopped() = reducerDispatch(KeepB5SessionEvent.RunStopped)
    override fun onRunMetricsUpdated(distanceMeters: Double, paceSecPerKm: Double?) = reducerDispatch(
        KeepB5SessionEvent.MetricsUpdated(KeepB5DisplayMetrics.fromMeasurements(distanceMeters, paceSecPerKm)),
    )

    private fun reducerDispatch(event: KeepB5SessionEvent) {
        synchronized(reducerLock) {
            val actions = supervisor.handle(event, monotonicMillis())
            if (event == KeepB5SessionEvent.PrepareConnection ||
                event == KeepB5SessionEvent.ExplicitConnect || event == KeepB5SessionEvent.ExplicitDisconnect ||
                event == KeepB5SessionEvent.RunStopped ||
                actions.any { it == KeepB5SessionAction.PublishConnectionState(ConnectionState.CONNECTED) }
            ) reconnectBackoff.reset()

            val reconnectDelays = mutableMapOf<KeepB5AttemptGuard, Long>()
            actions.forEach { action ->
                when (action) {
                    is KeepB5SessionAction.RecordDiagnostic -> record(action.event)
                    is KeepB5SessionAction.ActivateGattData -> safely { transport.activateGattData(action.guard) }
                    is KeepB5SessionAction.ResetSyncUpdateAccounting ->
                        sync?.takeIf { it.guard == action.guard }?.scheduler?.resetUpdateAccounting()
                    is KeepB5SessionAction.CancelPendingConnect -> safely { transport.cancelPendingConnect(action.guard) }
                    is KeepB5SessionAction.CloseGatt -> safelyJob {
                        transport.quiesceGattCallbacks(action.guard)
                    }
                    is KeepB5SessionAction.ScheduleReconnect ->
                        reconnectDelays[action.guard] = reconnectBackoff.nextDelayMillis()
                    else -> Unit
                }
            }
            effects.trySend(EffectBatch(actions, reconnectDelays, event == KeepB5SessionEvent.RunStopped))
        }
    }

    private suspend fun runEffects() {
        for (batch in effects) {
            var skipHeartbeat = false
            batch.actions.forEach { action ->
                try {
                    when (action) {
                        is KeepB5SessionAction.RecordDiagnostic,
                        is KeepB5SessionAction.ActivateGattData,
                        is KeepB5SessionAction.ResetSyncUpdateAccounting,
                        is KeepB5SessionAction.CancelPendingConnect -> Unit
                        is KeepB5SessionAction.BeginScan -> executeGuarded(action) {
                            if (transport.startScan(action.guard)) armScanTimeout(action.guard)
                        }
                        is KeepB5SessionAction.StopScan -> stopScan(action)
                        is KeepB5SessionAction.ConnectRemembered -> executeGuarded(action) {
                            transport.connectRemembered(action.guard)
                        }
                        is KeepB5SessionAction.SendStart -> executeGuarded(action) {
                            transport.sendStart(action.guard, epochSeconds())
                        }
                        is KeepB5SessionAction.StartSync -> executeGuarded(action) { startSync(action.guard) }
                        is KeepB5SessionAction.StopSync -> stopSync(action.gattToken)
                        is KeepB5SessionAction.SendWorkoutUpdate -> {
                            try {
                                val accepted = executeSyncWrite(action.guard, action.due) { allowed ->
                                    transport.sendWorkoutUpdate(
                                        allowed.submission,
                                        requireNotNull(allowed.elapsedSeconds),
                                        allowed.displayMetrics,
                                    )
                                }
                                if (accepted == false) skipHeartbeat = true
                            } catch (error: CancellationException) {
                                skipHeartbeat = true
                                throw error
                            }
                        }
                        is KeepB5SessionAction.SendHeartbeat -> if (!skipHeartbeat) {
                            executeSyncWrite(action.guard, KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT)) { allowed ->
                                transport.sendHeartbeat(allowed.submission)
                            }
                        }
                        is KeepB5SessionAction.ArmReadyTimeout -> executeGuarded(action) {
                            armReadyTimeout(action.guard, action.delayMillis)
                        }
                        is KeepB5SessionAction.CancelReadyTimeout -> cancelReadyTimeout(action.guard)
                        is KeepB5SessionAction.ScheduleReconnect -> executeGuarded(action) {
                            scheduleReconnect(action.guard, requireNotNull(batch.reconnectDelays[action.guard]))
                        }
                        is KeepB5SessionAction.CancelReconnect -> cancelReconnect(action)
                        is KeepB5SessionAction.CloseGatt ->
                            transport.closeGatt(action.guard, action.disconnectFirst).join()
                        is KeepB5SessionAction.PublishConnectionState -> _connectionState.value = action.state
                        is KeepB5SessionAction.PublishSample -> executeGuarded(action) {
                            sampleQueue.trySend(action.sample.sample)
                        }
                        is KeepB5SessionAction.PublishDeviceInfo -> executeGuarded(action) {
                            deviceInfoSnapshot = action.deviceInfo
                        }
                    }
                } catch (error: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw error
                } catch (error: Error) {
                    effects.close(error)
                    sourceJob.cancel(
                        CancellationException("BLE effect actor terminated").apply { initCause(error) },
                    )
                    throw error
                } catch (_: Exception) {
                    // Transport owns typed operation diagnostics; cleanup failures must not kill FIFO effects.
                }
            }
            if (batch.terminalFlush) sourceScope.launch { withTimeoutOrNull(2_000L) { diagnosticLogger.flush() } }
        }
    }

    private fun executeGuarded(action: KeepB5SessionAction, effect: () -> Unit) {
        synchronized(reducerLock) {
            when (val resolution = supervisor.resolveGuardedAction(action)) {
                KeepB5ActionResolution.Allowed -> effect()
                is KeepB5ActionResolution.Rejected -> record(resolution.diagnostic)
            }
        }
    }

    private fun executeSyncWrite(
        guard: KeepB5WriteGuard,
        due: KeepB5SyncDue,
        write: (KeepB5WriteResolution.Allowed) -> Boolean,
    ): Boolean? = synchronized(reducerLock) {
        when (val resolution = supervisor.resolveSyncWrite(guard, due)) {
            is KeepB5WriteResolution.Allowed -> write(resolution)
            is KeepB5WriteResolution.Rejected -> null.also { record(resolution.diagnostic) }
        }
    }

    private fun armScanTimeout(guard: KeepB5AttemptGuard) {
        activeScan?.job?.cancel()
        activeScan = TimedAttempt(guard, sourceScope.launch {
            delay(15_000L)
            reducerDispatch(KeepB5SessionEvent.ScanFailed(guard, BleFailureReason.SCAN_TIMEOUT))
        })
    }

    private fun stopScan(action: KeepB5SessionAction.StopScan) = synchronized(reducerLock) {
        activeScan?.takeIf { it.guard == action.guard }?.let {
            it.job.cancel()
            activeScan = null
        }
        // Transport owns the same exact-guard identity and safely ignores an old generation.
        safely { transport.stopScan(action.guard, action.preserveMatchedTarget) }
    }

    private fun scheduleReconnect(guard: KeepB5AttemptGuard, delayMillis: Long) {
        reconnect?.job?.cancel()
        reconnect = TimedAttempt(guard, sourceScope.launch {
            delay(delayMillis)
            reducerDispatch(KeepB5SessionEvent.ReconnectDue(guard))
        })
    }

    private fun cancelReconnect(action: KeepB5SessionAction.CancelReconnect) = synchronized(reducerLock) {
        reconnect?.takeIf {
            it.guard.epoch == action.epoch &&
                (action.connectionAttemptToken == null || it.guard.connectionAttemptToken == action.connectionAttemptToken)
        }?.let { it.job.cancel(); reconnect = null }
    }

    private fun armReadyTimeout(guard: KeepB5GattGuard, delayMillis: Long) {
        readyTimeout?.job?.cancel()
        readyTimeout = TimedGatt(guard, sourceScope.launch {
            delay(delayMillis)
            reducerDispatch(KeepB5SessionEvent.ReadyTimeout(guard))
        })
    }

    private fun cancelReadyTimeout(guard: KeepB5GattGuard) = synchronized(reducerLock) {
        readyTimeout?.takeIf { it.guard == guard }?.let { it.job.cancel(); readyTimeout = null }
    }

    private fun startSync(guard: KeepB5GattGuard) {
        stopSync(null)
        val scheduler = KeepB5SyncScheduler(monotonicMillis).also { it.start() }
        lateinit var active: ActiveSync
        val job = sourceScope.launch {
            while (true) {
                delay(scheduler.nextDelayMillis() ?: return@launch)
                synchronized(reducerLock) {
                    if (sync !== active) return@synchronized
                    val pollGuard = supervisor.captureSyncPollGuard(guard) ?: return@synchronized
                    val actions = supervisor.handle(
                        KeepB5SessionEvent.SyncDue(pollGuard, scheduler.pollDue()),
                        monotonicMillis(),
                    )
                    actions.filterIsInstance<KeepB5SessionAction.RecordDiagnostic>().forEach { record(it.event) }
                    effects.trySend(EffectBatch(actions))
                }
            }
        }
        active = ActiveSync(guard, scheduler, job)
        sync = active
    }

    private fun stopSync(gattToken: Long?) = synchronized(reducerLock) {
        sync?.takeIf { gattToken == null || it.guard.gattToken == gattToken }?.let {
            it.scheduler.stop(); it.job.cancel(); sync = null
        }
    }

    private fun record(event: DiagnosticEvent) {
        try { diagnosticLogger.record(event) } catch (_: Exception) { Unit }
    }

    private fun safely(block: () -> Unit) {
        try { block() } catch (error: CancellationException) { throw error } catch (_: Exception) { Unit }
    }

    private fun safelyJob(block: () -> Job): Job = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Job().apply { complete() }
    }

    private fun BleTransportEvent.toSessionEvent(): KeepB5SessionEvent = when (this) {
        is BleTransportEvent.ScanMatched -> KeepB5SessionEvent.ScanMatched(guard)
        is BleTransportEvent.ScanFailed -> KeepB5SessionEvent.ScanFailed(guard, reason)
        is BleTransportEvent.ConnectFailed -> KeepB5SessionEvent.ConnectFailed(guard, reason)
        is BleTransportEvent.GattCreated -> KeepB5SessionEvent.GattCreated(guard)
        is BleTransportEvent.GattConnected -> KeepB5SessionEvent.GattConnected(guard)
        is BleTransportEvent.ServicesDiscovered -> KeepB5SessionEvent.ServicesDiscovered(guard, channel)
        is BleTransportEvent.NotificationsEnabled -> KeepB5SessionEvent.NotificationsEnabled(guard, channel)
        is BleTransportEvent.StartWriteCompleted -> KeepB5SessionEvent.StartWriteCompleted(guard, accepted)
        is BleTransportEvent.SyncWriteCompleted -> KeepB5SessionEvent.SyncWriteCompleted(submission, accepted)
        is BleTransportEvent.GattDisconnected -> KeepB5SessionEvent.LinkFailed(
            guard, reason ?: BleFailureReason.REMOTE_DISCONNECTED,
        )
        is BleTransportEvent.GattError -> KeepB5SessionEvent.LinkFailed(guard, reason)
        is BleTransportEvent.DeviceInfoUpdated -> KeepB5SessionEvent.DeviceInfoReceived(guard, deviceInfo)
    }

    private data class EffectBatch(
        val actions: List<KeepB5SessionAction>,
        val reconnectDelays: Map<KeepB5AttemptGuard, Long> = emptyMap(),
        val terminalFlush: Boolean = false,
    )
    private data class TimedAttempt(val guard: KeepB5AttemptGuard, val job: Job)
    private data class TimedGatt(val guard: KeepB5GattGuard, val job: Job)
    private data class ActiveSync(val guard: KeepB5GattGuard, val scheduler: KeepB5SyncScheduler, val job: Job)
}

internal data class KeepB5BleDataSourceResourceSnapshot(
    val quiescedCallbackJobs: Int,
)
