package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.diagnostics.BleFailureReason
import com.healthyagent.running.diagnostics.BleOperation
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticFailure
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.SafeDiagnosticSummary
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface KeepB5GattBindingSink {
    fun control(event: BleTransportEvent): Boolean
    fun activity(value: PrivateWorkoutActivity): Boolean
    fun sample(value: TokenizedSensorSample): Boolean
}

internal class KeepB5GattBindingCoordinator(
    private val connector: KeepB5GattConnector,
    private val scope: CoroutineScope,
    private val connectDispatcher: CoroutineDispatcher,
    private val newGattToken: () -> Long,
    private val eventMapper: KeepB5GattEventMapper,
    private val sink: KeepB5GattBindingSink,
    private val diagnosticLogger: DiagnosticLogger,
    private val onBindingRegisteredForTest: () -> Unit = {},
) {
    internal data class RegistrySnapshot(
        val bindings: Int,
        val permits: Int,
        val connectJobs: Int,
    )

    private val lock = Any()
    private val bindings = mutableSetOf<Binding>()
    private val bindingsByAttempt = mutableMapOf<KeepB5AttemptGuard, Binding>()
    private val bindingsByGatt = mutableMapOf<KeepB5GattGuard, Binding>()
    private val connectJobs = mutableSetOf<Job>()
    private val closed = AtomicBoolean(false)
    private val shutdownStarted = AtomicBoolean(false)
    private val shutdownCompletion = CompletableDeferred<Unit>()
    private val completedJob = CompletableDeferred(Unit)
    private val containedFailureHandler = CoroutineExceptionHandler { _, _ -> Unit }

    fun connect(guard: KeepB5AttemptGuard, target: KeepB5ConnectTarget): Boolean {
        val binding = Binding(guard, target)
        val registered = synchronized(lock) {
            if (closed.get() || bindingsByAttempt.containsKey(guard)) {
                false
            } else {
                bindings += binding
                bindingsByAttempt[guard] = binding
                true
            }
        }
        if (!registered) {
            binding.connectPublication.complete(null)
            binding.registrationGate.close()
            fail(binding, null)
            return false
        }
        onBindingRegisteredForTest()
        val launchAllowed = synchronized(lock) {
            !closed.get() && bindingsByAttempt[guard] === binding
        }
        if (!launchAllowed) {
            binding.connectPublication.complete(null)
            binding.registrationGate.close()
            binding.cancelled.set(true)
            binding.permit.cancel()
            localCancel(binding)
            return false
        }

        val connectJob = try {
            scope.launch(connectDispatcher + containedFailureHandler, start = CoroutineStart.LAZY) {
                runConnect(binding)
            }
        } catch (error: Throwable) {
            binding.connectPublication.complete(null)
            fail(binding, error)
            return false
        }
        binding.connectJob = connectJob
        synchronized(lock) { connectJobs += connectJob }
        connectJob.invokeOnCompletion { error ->
            try {
                if (error != null && binding.outcome.get() == Outcome.OPEN) {
                    if (binding.permit.isCancellationRequested() || closed.get()) {
                        localCancel(binding)
                    } else {
                        fail(binding, error)
                    }
                }
            } finally {
                finishConnect(binding, connectJob)
            }
        }
        binding.connectPublication.complete(connectJob)
        if (closed.get()) {
            binding.registrationGate.close()
            binding.cancelled.set(true)
            binding.permit.cancel()
            localCancel(binding)
            connectJob.cancel()
            return false
        }
        return try {
            if (connectJob.start() && !connectJob.isCancelled) {
                true
            } else {
                if (binding.permit.isCancellationRequested() || closed.get()) {
                    localCancel(binding)
                } else {
                    fail(binding, CancellationException("GATT connect launch rejected"))
                }
                false
            }
        } catch (error: Throwable) {
            fail(binding, error)
            false
        }
    }

    fun cancelPendingConnect(guard: KeepB5AttemptGuard) {
        val binding = synchronized(lock) { bindingsByAttempt[guard] } ?: return
        binding.registrationGate.close()
        binding.cancelled.set(true)
        binding.permit.cancel()
        localCancel(binding)
        quiesce(binding)
        startTeardown(binding, disconnectFirst = true)
    }

    fun activateGattData(guard: KeepB5GattGuard) {
        val binding = synchronized(lock) { bindingsByGatt[guard] } ?: return
        if (binding.outcome.get() == Outcome.CREATED && !binding.closing.get()) {
            binding.registrationGate.activate()
            removeAttemptRegistration(binding)
        }
    }

    fun quiesceGattCallbacks(guard: KeepB5GattGuard): Job {
        val binding = synchronized(lock) { bindingsByGatt[guard] } ?: return completedJob
        return quiesce(binding)
    }

    fun sendStart(guard: KeepB5GattGuard, epochSeconds: Long): Boolean {
        val binding = writableBinding(guard) ?: return false
        if (!binding.startClaimed.compareAndSet(false, true)) return false
        return write(
            binding = binding,
            operation = BleOperation.START_WRITE,
            completion = { accepted -> BleTransportEvent.StartWriteCompleted(guard, accepted) },
        ) { it.sendStart(epochSeconds) }
    }

    fun sendWorkoutUpdate(
        submission: KeepB5SubmittedWrite,
        elapsedSeconds: Long,
        displayMetrics: KeepB5DisplayMetrics = KeepB5DisplayMetrics(),
    ): Boolean {
        val binding = writableBinding(submission.gattGuard) ?: return false
        if (!binding.claimedOperationIds.add(submission.operationId)) return false
        return write(
            binding = binding,
            operation = BleOperation.UPDATE_WRITE,
            completion = { accepted -> BleTransportEvent.SyncWriteCompleted(submission, accepted) },
        ) { it.sendWorkoutUpdate(elapsedSeconds, displayMetrics) }
    }

    fun sendHeartbeat(submission: KeepB5SubmittedWrite): Boolean {
        val binding = writableBinding(submission.gattGuard) ?: return false
        if (!binding.claimedOperationIds.add(submission.operationId)) return false
        return write(
            binding = binding,
            operation = BleOperation.HEARTBEAT_WRITE,
            completion = { accepted -> BleTransportEvent.SyncWriteCompleted(submission, accepted) },
        ) { it.sendHeartbeat() }
    }

    fun close(guard: KeepB5GattGuard, disconnectFirst: Boolean): Job {
        val binding = synchronized(lock) { bindingsByGatt[guard] } ?: return completedJob
        quiesce(binding)
        return startTeardown(binding, disconnectFirst)
    }

    fun shutdown(): Job {
        if (!shutdownStarted.compareAndSet(false, true)) return shutdownCompletion
        closed.set(true)
        val registeredBindings = synchronized(lock) { bindings.toList() }
        registeredBindings.forEach { binding ->
            binding.registrationGate.close()
            binding.cancelled.set(true)
            binding.permit.cancel()
            localCancel(binding)
            quiesce(binding)
        }
        val teardowns = registeredBindings.map { startTeardown(it, disconnectFirst = true) }
        val worker = try {
            scope.launch(connectDispatcher + containedFailureHandler) {
                try {
                    teardowns.joinAll()
                    val jobs = synchronized(lock) { connectJobs.toList() }
                    jobs.joinAll()
                    synchronized(lock) {
                        bindings.clear()
                        bindingsByAttempt.clear()
                        bindingsByGatt.clear()
                        connectJobs.clear()
                    }
                    shutdownCompletion.complete(Unit)
                } catch (error: CancellationException) {
                    shutdownCompletion.completeExceptionally(error)
                    throw error
                } catch (error: Throwable) {
                    shutdownCompletion.completeExceptionally(error)
                }
            }
        } catch (error: Throwable) {
            shutdownCompletion.completeExceptionally(error)
            null
        }
        worker?.invokeOnCompletion { error ->
            if (error != null && !shutdownCompletion.isCompleted) {
                shutdownCompletion.completeExceptionally(error)
            }
        }
        return shutdownCompletion
    }

    internal fun registrySnapshotForTest(): RegistrySnapshot = synchronized(lock) {
        RegistrySnapshot(
            bindings = bindings.size,
            permits = bindingsByAttempt.size,
            connectJobs = connectJobs.size,
        )
    }

    private fun runConnect(binding: Binding) {
        var returnedSession: KeepB5GattSession? = null
        try {
            val started = binding.permit.tryStart {
                returnedSession = connector.connect(binding.target, binding.registrationGate, binding)
                returnedSession?.let { bind(binding, it) }
            }
            if (!started) {
                localCancel(binding)
                return
            }
            if (binding.permit.isCancellationRequested() || closed.get()) {
                localCancel(binding)
                returnedSession?.let { startTeardown(binding, disconnectFirst = true) }
            } else if (returnedSession == null) {
                fail(binding, null)
            }
        } catch (error: CancellationException) {
            if (binding.permit.isCancellationRequested() || closed.get()) {
                localCancel(binding)
            } else {
                fail(binding, error)
            }
            if (binding.session != null) startTeardown(binding, disconnectFirst = true)
        } catch (error: Throwable) {
            if (binding.permit.isCancellationRequested() || closed.get()) {
                localCancel(binding)
            } else {
                fail(binding, error)
            }
            if (binding.session != null) startTeardown(binding, disconnectFirst = true)
        }
    }

    private fun bind(binding: Binding, session: KeepB5GattSession) {
        if (!binding.bindClaimed.compareAndSet(false, true)) return
        val token = try {
            newGattToken()
        } catch (error: Throwable) {
            binding.session = session
            binding.registrationGate.close()
            fail(binding, error)
            quiesce(binding)
            startTeardown(binding, disconnectFirst = true)
            return
        }
        val guard = KeepB5GattGuard(
            epoch = binding.attemptGuard.epoch,
            connectionAttemptToken = binding.attemptGuard.connectionAttemptToken,
            gattToken = token,
        )
        binding.session = session
        binding.gattGuard = guard
        synchronized(lock) { bindingsByGatt[guard] = binding }

        if (binding.cancelled.get() || binding.permit.isCancellationRequested() || closed.get()) {
            localCancel(binding)
            quiesce(binding)
            startTeardown(binding, disconnectFirst = true)
            return
        }
        if (binding.outcome.compareAndSet(Outcome.OPEN, Outcome.CREATED)) {
            if (!safeControl(BleTransportEvent.GattCreated(guard))) {
                quiesce(binding)
                startTeardown(binding, disconnectFirst = true)
            }
        } else {
            quiesce(binding)
            startTeardown(binding, disconnectFirst = true)
        }
    }

    private fun onSignal(binding: Binding, session: KeepB5GattSession, signal: RawGattSignal) {
        if (!binding.bindClaimed.get()) bind(binding, session)
        val guard = binding.gattGuard ?: return
        if (binding.session !== session || binding.outcome.get() != Outcome.CREATED) return
        if (!binding.registrationGate.isActive() || binding.closing.get()) return
        val outputs = try {
            eventMapper.map(guard, signal)
        } catch (_: Throwable) {
            return
        }
        outputs.forEach { output ->
            if (!binding.registrationGate.isActive() || binding.closing.get()) return@forEach
            try {
                when (output) {
                    is MappedGattOutput.Control -> sink.control(output.event)
                    is MappedGattOutput.Activity -> sink.activity(output.value)
                    is MappedGattOutput.Sample -> sink.sample(output.value)
                }
            } catch (_: Throwable) {
                Unit
            }
        }
    }

    private fun writableBinding(guard: KeepB5GattGuard): Binding? {
        val binding = synchronized(lock) { bindingsByGatt[guard] } ?: return null
        if (binding.outcome.get() != Outcome.CREATED || binding.closing.get()) return null
        if (!binding.registrationGate.isActive()) return null
        return binding.takeIf { it.session != null }
    }

    private fun write(
        binding: Binding,
        operation: BleOperation,
        completion: (Boolean) -> BleTransportEvent,
        call: (KeepB5GattSession) -> Boolean,
    ): Boolean {
        val session = binding.session ?: return false
        val accepted = try {
            call(session)
        } catch (error: CancellationException) {
            safeControl(completion(false))
            recordFailure(operation, error)
            throw error
        } catch (error: Error) {
            safeControl(completion(false))
            recordFailure(operation, error)
            throw error
        } catch (error: Exception) {
            recordFailure(operation, error)
            false
        }
        safeControl(completion(accepted))
        return accepted
    }

    private fun quiesce(binding: Binding): Job {
        binding.registrationGate.close()
        binding.closing.set(true)
        val session = binding.session ?: return binding.callbackCompletion
        if (binding.callbackShutdownStarted.compareAndSet(false, true)) {
            val callbackJob = try {
                session.shutdownCallbacks()
            } catch (_: Throwable) {
                null
            }
            if (callbackJob == null) {
                binding.callbackCompletion.complete(Unit)
            } else {
                callbackJob.invokeOnCompletion {
                    binding.callbackCompletion.complete(Unit)
                }
            }
        }
        return binding.callbackCompletion
    }

    private fun startTeardown(binding: Binding, disconnectFirst: Boolean): Job {
        binding.teardownJob.get()?.let { return it }
        val candidate = scope.launch(connectDispatcher + containedFailureHandler, start = CoroutineStart.LAZY) {
            teardown(binding, disconnectFirst)
        }
        if (!binding.teardownJob.compareAndSet(null, candidate)) {
            candidate.cancel()
            return requireNotNull(binding.teardownJob.get())
        }
        candidate.start()
        return candidate
    }

    private suspend fun teardown(binding: Binding, disconnectFirst: Boolean) {
        var cancellation: CancellationException? = null
        withContext(NonCancellable) {
            binding.connectPublication.await()?.join()
            val session = binding.session
            val callbackJob = quiesce(binding)
            if (session == null) binding.callbackCompletion.complete(Unit)
            if (session != null) {
                if (disconnectFirst) {
                    try {
                        session.disconnect()
                    } catch (error: CancellationException) {
                        cancellation = error
                    } catch (error: Throwable) {
                        recordFailure(BleOperation.DISCONNECT_GATT, error)
                    }
                }
                try {
                    session.close()
                } catch (error: CancellationException) {
                    if (cancellation == null) cancellation = error
                } catch (error: Throwable) {
                    recordFailure(BleOperation.CLOSE_GATT, error)
                }
            }
            callbackJob.join()
            binding.cleaned.set(true)
            removeAttemptRegistration(binding)
            if (binding.gattGuard == null) {
                synchronized(lock) { bindings.remove(binding) }
            }
        }
        cancellation?.let { throw it }
    }

    private fun localCancel(binding: Binding) {
        if (binding.outcome.compareAndSet(Outcome.OPEN, Outcome.LOCAL_CANCELLED)) {
            removeAttemptRegistration(binding)
            cleanupTerminalUnbound(binding)
        }
    }

    private fun fail(binding: Binding, error: Throwable?) {
        binding.registrationGate.close()
        if (!binding.outcome.compareAndSet(Outcome.OPEN, Outcome.FAILED)) return
        removeAttemptRegistration(binding)
        error?.let { recordFailure(BleOperation.CONNECT_GATT, it) }
        safeControl(BleTransportEvent.ConnectFailed(binding.attemptGuard, BleFailureReason.GATT_ERROR))
        cleanupTerminalUnbound(binding)
    }

    private fun finishConnect(binding: Binding, connectJob: Job) {
        synchronized(lock) { connectJobs.remove(connectJob) }
        if (binding.session == null) binding.callbackCompletion.complete(Unit)
        cleanupTerminalUnbound(binding)
    }

    private fun cleanupTerminalUnbound(binding: Binding) {
        if (binding.outcome.get() == Outcome.OPEN || binding.outcome.get() == Outcome.CREATED) return
        if (binding.session != null) return
        val connectJob = binding.connectJob
        if (connectJob != null && !connectJob.isCompleted) return
        synchronized(lock) {
            removeAttemptRegistrationLocked(binding)
            bindings.remove(binding)
        }
    }

    private fun removeAttemptRegistration(binding: Binding) {
        synchronized(lock) { removeAttemptRegistrationLocked(binding) }
    }

    private fun removeAttemptRegistrationLocked(binding: Binding) {
        if (bindingsByAttempt[binding.attemptGuard] === binding) {
            bindingsByAttempt.remove(binding.attemptGuard)
        }
    }

    private fun safeControl(event: BleTransportEvent): Boolean = try {
        sink.control(event)
    } catch (_: Throwable) {
        false
    }

    private fun recordFailure(operation: BleOperation, error: Throwable) {
        val event = DiagnosticEvent.BleOperationFailed(
            operation = operation,
            reason = BleFailureReason.GATT_ERROR,
            failure = DiagnosticFailure.from(error, SafeDiagnosticSummary.BLE_OPERATION_FAILED),
        )
        try {
            diagnosticLogger.record(event)
        } catch (_: Throwable) {
            Unit
        }
    }

    private inner class Binding(
        val attemptGuard: KeepB5AttemptGuard,
        val target: KeepB5ConnectTarget,
    ) : KeepB5GattSessionListener {
        val registrationGate = KeepB5GattRegistrationGate()
        val permit = KeepB5ConnectPermit()
        val bindClaimed = AtomicBoolean(false)
        val callbackShutdownStarted = AtomicBoolean(false)
        val callbackCompletion = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val closing = AtomicBoolean(false)
        val cleaned = AtomicBoolean(false)
        val startClaimed = AtomicBoolean(false)
        val claimedOperationIds = ConcurrentHashMap.newKeySet<Long>()
        val outcome = AtomicReference(Outcome.OPEN)
        val teardownJob = AtomicReference<Job?>(null)
        val connectPublication = CompletableDeferred<Job?>()

        @Volatile var session: KeepB5GattSession? = null
        @Volatile var gattGuard: KeepB5GattGuard? = null
        @Volatile var connectJob: Job? = null

        override fun onSignal(session: KeepB5GattSession, signal: RawGattSignal) {
            this@KeepB5GattBindingCoordinator.onSignal(this, session, signal)
        }
    }

    private enum class Outcome { OPEN, CREATED, FAILED, LOCAL_CANCELLED }
}
