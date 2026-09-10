package com.healthyagent.running.data.sensor.ble

import android.content.Context
import android.os.SystemClock
import com.healthyagent.running.diagnostics.BleFailureReason
import com.healthyagent.running.diagnostics.BleOperation
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticFailure
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.SafeDiagnosticSummary
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AndroidKeepB5BleTransport(
    private val scanPort: KeepB5ScanPort,
    gattConnector: KeepB5GattConnector,
    private val scope: CoroutineScope,
    private val connectDispatcher: CoroutineDispatcher,
    newGattToken: () -> Long,
    private val diagnosticLogger: DiagnosticLogger,
) : BleTransport {
    constructor(
        context: Context,
        diagnosticLogger: DiagnosticLogger,
    ) : this(productionDependencies(context, diagnosticLogger))

    private constructor(dependencies: ProductionDependencies) : this(
        scanPort = dependencies.scanPort,
        gattConnector = dependencies.gattConnector,
        scope = dependencies.scope,
        connectDispatcher = dependencies.dispatcher,
        newGattToken = dependencies.gattToken::incrementAndGet,
        diagnosticLogger = dependencies.diagnosticLogger,
    )

    private val lock = Any()
    private val controlQueue = Channel<BleTransportEvent>(Channel.UNLIMITED)
    private val sampleQueue = Channel<TokenizedSensorSample>(Channel.UNLIMITED)
    private val activityQueue = KeyedConflatedEventQueue<KeepB5GattGuard, PrivateWorkoutActivity> {
            pending, incoming ->
        if (incoming.receivedAtMillis >= pending.receivedAtMillis) incoming else pending
    }
    private val rememberedTargets = mutableMapOf<KeepB5AttemptGuard, KeepB5ConnectTarget>()
    private var activeScan: ActiveScan? = null
    private var closed = false
    private var shutdownCompletion: CompletableDeferred<Unit>? = null
    private val queuesClosed = AtomicBoolean(false)
    private val containedFailureHandler = CoroutineExceptionHandler { _, _ -> Unit }

    private val bindingCoordinator = KeepB5GattBindingCoordinator(
        connector = gattConnector,
        scope = scope,
        connectDispatcher = connectDispatcher,
        newGattToken = newGattToken,
        eventMapper = KeepB5GattEventMapper(),
        sink = object : KeepB5GattBindingSink {
            override fun control(event: BleTransportEvent): Boolean = offerControl(event)

            override fun activity(value: PrivateWorkoutActivity): Boolean = synchronized(lock) {
                !closed && activityQueue.offer(value.guard, value)
            }

            override fun sample(value: TokenizedSensorSample): Boolean = synchronized(lock) {
                !closed && sampleQueue.trySend(value).isSuccess
            }
        },
        diagnosticLogger = diagnosticLogger,
    )

    override val controlEvents: Flow<BleTransportEvent> = controlQueue.receiveAsFlow()
    override val privateActivities: Flow<PrivateWorkoutActivity> = activityQueue.values
    override val samples: Flow<TokenizedSensorSample> = sampleQueue.receiveAsFlow()

    override fun startScan(guard: KeepB5AttemptGuard): Boolean {
        val scan = ActiveScan(guard)
        val replaced = synchronized(lock) {
            if (closed) return false
            rememberedTargets.clear()
            val previous = activeScan
            previous?.terminal?.set(true)
            activeScan = scan
            previous
        }
        if (replaced?.cancelAndClaimStop() == true) stopPortSafely()

        val availability = try {
            scanPort.availability()
        } catch (error: CancellationException) {
            failScan(scan, BleFailureReason.SCAN_FAILED)
            recordFailure(BleOperation.START_SCAN, BleFailureReason.SCAN_FAILED, error)
            throw error
        } catch (error: Error) {
            failScan(scan, BleFailureReason.SCAN_FAILED)
            recordFailure(BleOperation.START_SCAN, BleFailureReason.SCAN_FAILED, error)
            throw error
        } catch (error: Exception) {
            failScan(scan, BleFailureReason.SCAN_FAILED)
            recordFailure(BleOperation.START_SCAN, BleFailureReason.SCAN_FAILED, error)
            return false
        }
        val unavailableReason = when (availability) {
            ScanAvailability.READY -> null
            ScanAvailability.PERMISSION_MISSING -> BleFailureReason.PERMISSION_MISSING
            ScanAvailability.ADAPTER_DISABLED -> BleFailureReason.ADAPTER_DISABLED
        }
        if (unavailableReason != null) {
            failScan(scan, unavailableReason)
            return false
        }

        val listener = object : KeepB5ScanPort.Listener {
            override fun onMatch(target: KeepB5ConnectTarget) {
                val won = synchronized(lock) {
                    if (closed || activeScan !== scan || !scan.terminal.compareAndSet(false, true)) {
                        false
                    } else {
                        rememberedTargets.clear()
                        rememberedTargets[scan.guard] = target
                        true
                    }
                }
                if (won) offerControl(BleTransportEvent.ScanMatched(scan.guard))
            }

            override fun onFailure(reason: BleFailureReason) {
                failScan(scan, reason)
            }
        }
        val startResult = try {
            scan.startPort { scanPort.start(listener) }
        } catch (error: CancellationException) {
            failScan(scan, BleFailureReason.SCAN_FAILED)
            recordFailure(BleOperation.START_SCAN, BleFailureReason.SCAN_FAILED, error)
            throw error
        } catch (error: Error) {
            failScan(scan, BleFailureReason.SCAN_FAILED)
            recordFailure(BleOperation.START_SCAN, BleFailureReason.SCAN_FAILED, error)
            throw error
        } catch (error: Exception) {
            failScan(scan, BleFailureReason.SCAN_FAILED)
            recordFailure(BleOperation.START_SCAN, BleFailureReason.SCAN_FAILED, error)
            ScanStartResult(accepted = false, stopPort = false)
        }
        if (startResult.stopPort) stopPortSafely()
        if (!startResult.accepted) failScan(scan, BleFailureReason.SCAN_FAILED)
        return startResult.accepted
    }

    override fun stopScan(guard: KeepB5AttemptGuard, preserveMatchedTarget: Boolean) {
        val scan = synchronized(lock) {
            val scan = activeScan
            if (scan?.guard != guard) {
                null
            } else {
                scan.terminal.set(true)
                activeScan = null
                if (!preserveMatchedTarget) rememberedTargets.remove(guard)
                scan
            }
        }
        if (scan?.cancelAndClaimStop() == true) stopPortSafely()
    }

    override fun connectRemembered(guard: KeepB5AttemptGuard): Boolean {
        val target = synchronized(lock) {
            if (closed) null else rememberedTargets.remove(guard)
        }
        if (target == null) {
            offerControl(BleTransportEvent.ConnectFailed(guard, BleFailureReason.GATT_ERROR))
            return false
        }
        return bindingCoordinator.connect(guard, target)
    }

    override fun cancelPendingConnect(guard: KeepB5AttemptGuard) {
        synchronized(lock) { rememberedTargets.remove(guard) }
        bindingCoordinator.cancelPendingConnect(guard)
    }

    override fun activateGattData(guard: KeepB5GattGuard) {
        bindingCoordinator.activateGattData(guard)
    }

    override fun quiesceGattCallbacks(guard: KeepB5GattGuard): Job =
        bindingCoordinator.quiesceGattCallbacks(guard)

    override fun sendStart(guard: KeepB5GattGuard, epochSeconds: Long): Boolean =
        bindingCoordinator.sendStart(guard, epochSeconds)

    override fun sendWorkoutUpdate(
        submission: KeepB5SubmittedWrite,
        elapsedSeconds: Long,
        displayMetrics: KeepB5DisplayMetrics,
    ): Boolean = bindingCoordinator.sendWorkoutUpdate(submission, elapsedSeconds, displayMetrics)

    override fun sendHeartbeat(submission: KeepB5SubmittedWrite): Boolean =
        bindingCoordinator.sendHeartbeat(submission)

    override fun closeGatt(guard: KeepB5GattGuard, disconnectFirst: Boolean): Job =
        bindingCoordinator.close(guard, disconnectFirst)

    override fun shutdown(): Job {
        val state = synchronized(lock) {
            shutdownCompletion?.let { return it }
            closed = true
            val scan = activeScan
            scan?.terminal?.set(true)
            activeScan = null
            rememberedTargets.clear()
            val coordinatorJob = bindingCoordinator.shutdown()
            val completion = CompletableDeferred<Unit>()
            shutdownCompletion = completion
            ShutdownState(scan, coordinatorJob, completion)
        }
        val stopFailure = try {
            if (state.scan?.cancelAndClaimStop() == true) stopPortSafely()
            null
        } catch (error: Throwable) {
            error
        }

        val worker = try {
            scope.launch(connectDispatcher + containedFailureHandler) {
                var cancellation: CancellationException? = null
                withContext(NonCancellable) {
                    try {
                        state.coordinatorJob.join()
                    } catch (error: CancellationException) {
                        cancellation = error
                    } finally {
                        closeQueues()
                    }
                }
                cancellation?.let { throw it }
                state.completion.complete(Unit)
            }
        } catch (error: Throwable) {
            closeQueues()
            state.completion.completeExceptionally(error)
            return state.completion
        }
        worker.invokeOnCompletion { error ->
            if (error != null && !state.completion.isCompleted) {
                state.completion.completeExceptionally(error)
            }
        }
        stopFailure?.let { throw it }
        return state.completion
    }

    private fun failScan(scan: ActiveScan, reason: BleFailureReason) {
        val won = synchronized(lock) {
            if (closed || activeScan !== scan || !scan.terminal.compareAndSet(false, true)) {
                false
            } else {
                rememberedTargets.remove(scan.guard)
                true
            }
        }
        if (won) offerControl(BleTransportEvent.ScanFailed(scan.guard, reason))
    }

    private fun stopPortSafely() {
        try {
            scanPort.stop()
        } catch (error: CancellationException) {
            recordFailure(BleOperation.STOP_SCAN, BleFailureReason.SCAN_FAILED, error)
            throw error
        } catch (error: Error) {
            recordFailure(BleOperation.STOP_SCAN, BleFailureReason.SCAN_FAILED, error)
            throw error
        } catch (error: Exception) {
            recordFailure(BleOperation.STOP_SCAN, BleFailureReason.SCAN_FAILED, error)
        }
    }

    private fun offerControl(event: BleTransportEvent): Boolean = synchronized(lock) {
        !closed && controlQueue.trySend(event).isSuccess
    }

    private fun closeQueues() {
        if (!queuesClosed.compareAndSet(false, true)) return
        controlQueue.close()
        activityQueue.close()
        sampleQueue.close()
    }

    private fun recordFailure(
        operation: BleOperation,
        reason: BleFailureReason,
        error: Throwable,
    ) {
        try {
            diagnosticLogger.record(
                DiagnosticEvent.BleOperationFailed(
                    operation = operation,
                    reason = reason,
                    failure = DiagnosticFailure.from(error, SafeDiagnosticSummary.BLE_OPERATION_FAILED),
                ),
            )
        } catch (_: Throwable) {
            Unit
        }
    }

    private class ActiveScan(
        val guard: KeepB5AttemptGuard,
        val terminal: AtomicBoolean = AtomicBoolean(false),
    ) {
        private val lifecycleLock = Any()
        private var cancelled = false
        private var portStarted = false
        private var stopClaimed = false

        fun startPort(start: () -> Boolean): ScanStartResult = synchronized(lifecycleLock) {
            if (cancelled) return ScanStartResult(accepted = false, stopPort = false)
            val accepted = start()
            if (!accepted) return ScanStartResult(accepted = false, stopPort = false)
            portStarted = true
            val stopPort = cancelled && !stopClaimed
            if (stopPort) stopClaimed = true
            ScanStartResult(accepted = !cancelled, stopPort = stopPort)
        }

        fun cancelAndClaimStop(): Boolean = synchronized(lifecycleLock) {
            cancelled = true
            if (!portStarted || stopClaimed) return false
            stopClaimed = true
            true
        }
    }

    private data class ScanStartResult(
        val accepted: Boolean,
        val stopPort: Boolean,
    )

    private data class ShutdownState(
        val scan: ActiveScan?,
        val coordinatorJob: Job,
        val completion: CompletableDeferred<Unit>,
    )

    private data class ProductionDependencies(
        val scanPort: KeepB5ScanPort,
        val gattConnector: KeepB5GattConnector,
        val scope: CoroutineScope,
        val dispatcher: CoroutineDispatcher,
        val gattToken: AtomicLong,
        val diagnosticLogger: DiagnosticLogger,
    )

    private companion object {
        fun productionDependencies(
            context: Context,
            diagnosticLogger: DiagnosticLogger,
        ): ProductionDependencies {
            val applicationContext = context.applicationContext
            val dispatcher = Dispatchers.IO
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            return ProductionDependencies(
                scanPort = AndroidKeepB5ScanPort(applicationContext),
                gattConnector = AndroidKeepB5GattConnector(
                    context = applicationContext,
                    monotonicMillis = SystemClock::elapsedRealtime,
                    wallClockMillis = System::currentTimeMillis,
                    processorScope = scope,
                    processorDispatcher = dispatcher,
                    diagnosticLogger = diagnosticLogger,
                ),
                scope = scope,
                dispatcher = dispatcher,
                gattToken = AtomicLong(),
                diagnosticLogger = diagnosticLogger,
            )
        }
    }
}
