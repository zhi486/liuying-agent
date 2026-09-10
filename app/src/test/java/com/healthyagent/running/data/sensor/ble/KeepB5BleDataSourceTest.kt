package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample
import com.healthyagent.running.diagnostics.BleFailureReason
import com.healthyagent.running.testing.FakeDiagnosticLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeepB5BleDataSourceTest {
    @Test
    fun `孤立写入拒绝后下一周期复用连接并发送最新距离配速`() = runTest {
        val transport = FakeTransport(rejectedUpdateIndices = setOf(1))
        val source = newDataSource(transport) { testScheduler.currentTime }
        connectPrivate(source, transport)
        source.onRunMetricsUpdated(153.0, 816.0)
        advanceTimeBy(4_501L)
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, source.connectionState.value)
        assertEquals(0, transport.closeCalls)
        source.onRunMetricsUpdated(186.0, 360.0)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf(4L, 5L), transport.updateElapsed)
        assertEquals(KeepB5DisplayMetrics(186, 360), transport.updateMetrics.last())
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, source.connectionState.value)
        assertEquals(1, transport.startScanCalls)
        assertEquals(1, transport.startWrites.size)
        assertEquals(2, transport.heartbeatCalls)
        source.onRunPaused(15L)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(15L, transport.updateElapsed.last())
        assertEquals(KeepB5DisplayMetrics(186, 0), transport.updateMetrics.last())
        source.disconnect()
    }

    @Test
    fun `预连接保持待机且开跑后复用连接发送一次开始`() = runTest {
        val transport = FakeTransport()
        val source = newDataSource(transport) { testScheduler.currentTime }
        source.prepareConnection()
        source.prepareConnection()
        runCurrent()
        val attempt = transport.startScanGuards.single()
        transport.control(BleTransportEvent.ScanMatched(attempt))
        runCurrent()
        val guard = KeepB5GattGuard(attempt.epoch, attempt.connectionAttemptToken, 1L)
        transport.control(BleTransportEvent.GattCreated(guard))
        transport.control(BleTransportEvent.GattConnected(guard))
        transport.control(BleTransportEvent.ServicesDiscovered(guard, KeepB5ChannelKind.PRIVATE_B3))
        transport.control(BleTransportEvent.NotificationsEnabled(guard, KeepB5ChannelKind.PRIVATE_B3))
        runCurrent()
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, source.connectionState.value)
        assertTrue(transport.startWrites.isEmpty())
        assertTrue(transport.updateElapsed.isEmpty())
        assertEquals(0, transport.heartbeatCalls)
        assertEquals(0, transport.closeCalls)

        source.onRunStarted(0L)
        source.connect()
        runCurrent()
        assertEquals(listOf(guard to 123L), transport.startWrites)
        assertEquals(1, transport.startScanCalls)
        transport.control(BleTransportEvent.StartWriteCompleted(guard, true))
        transport.activity(PrivateWorkoutActivity(guard, testScheduler.currentTime))
        runCurrent()
        source.onRunMetricsUpdated(153.0, 816.0)
        advanceTimeBy(6_001L)
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, source.connectionState.value)
        assertEquals(KeepB5DisplayMetrics(153, 816), transport.updateMetrics.last())
        assertEquals(1, transport.heartbeatCalls)
        source.disconnect()
        runCurrent()
        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun `手机指标在周期提交时透传并在暂停冻结恢复清空配速`() = runTest {
        val transport = FakeTransport()
        val source = newDataSource(transport) { testScheduler.currentTime }
        connectPrivate(source, transport)
        source.onRunMetricsUpdated(153.9, 815.5)
        // 沿用现有首次同步 4.5 秒延迟，不为测试改变生产握手节奏。
        advanceTimeBy(4_501L)
        runCurrent()
        assertEquals(KeepB5DisplayMetrics(153, 816), transport.updateMetrics.last())

        source.onRunPaused(4L)
        source.onRunMetricsUpdated(9_999.0, 200.0)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(KeepB5DisplayMetrics(153, 0), transport.updateMetrics.last())
        assertEquals(4L, transport.updateElapsed.last())

        source.onRunResumed(4L)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(KeepB5DisplayMetrics(153, 0), transport.updateMetrics.last())
        source.onRunMetricsUpdated(186.8, 360.4)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(KeepB5DisplayMetrics(186, 360), transport.updateMetrics.last())
    }

    @Test
    fun updateProviderCancellationSkipsSameBatchHeartbeatBeforeCompletionIsReduced() = runTest {
        var now = 0L
        val completionGate = CompletableDeferred<Unit>()
        val providerCancellation = java.util.concurrent.CancellationException("update provider cancelled")
        val transport = FakeTransport(
            controlCompletionGate = completionGate,
            updateFailure = providerCancellation,
        )
        val source = newDataSource(transport) { now }
        connectPrivate(source, transport)

        now = 6_000L
        advanceTimeBy(4_500L)
        runCurrent()

        assertEquals(listOf(6L), transport.updateElapsed)
        assertEquals(0, transport.heartbeatCalls)
        completionGate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `单次心跳提供者取消后执行器仍能在下一周期继续而不重连`() = runTest {
        val providerCancellation = java.util.concurrent.CancellationException("heartbeat provider cancelled")
        val transport = FakeTransport(heartbeatFailure = providerCancellation)
        val source = newDataSource(transport) { testScheduler.currentTime }
        connectPrivate(source, transport)

        advanceTimeBy(6_000L)
        runCurrent()
        assertEquals(1, transport.heartbeatCalls)
        assertEquals(ConnectionState.CONNECTED, source.connectionState.value)
        assertEquals(0, transport.closeCalls)

        advanceTimeBy(6_000L)
        runCurrent()
        assertEquals(2, transport.heartbeatCalls)
        assertEquals(1, transport.startScanCalls)
        assertEquals(1, transport.startWrites.size)
        assertEquals(ConnectionState.CONNECTED, source.connectionState.value)
    }

    @Test
    fun providerCancellationExceptionDoesNotKillActiveEffectActor() = runTest {
        val providerCancellation = java.util.concurrent.CancellationException("provider cancelled")
        val transport = FakeTransport(startScanFailure = providerCancellation)
        val source = KeepB5BleDataSource(
            transport, backgroundScope, { testScheduler.currentTime }, { 123L }, FakeBackoff(1_000L),
        )

        source.connect()
        runCurrent()
        assertEquals(1, transport.startScanCalls)
        assertEquals(ConnectionState.ERROR, source.connectionState.value)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, transport.startScanCalls)
        assertEquals(ConnectionState.CONNECTING, source.connectionState.value)
    }

    @Test
    fun externalScopeCancellationStillTerminatesEveryDataSourceChild() = runTest {
        val parent = SupervisorJob()
        val ownedScope = CoroutineScope(parent + StandardTestDispatcher(testScheduler))
        KeepB5BleDataSource(
            FakeTransport(), ownedScope, { testScheduler.currentTime }, { 123L }, FakeBackoff(1_000L),
        )
        runCurrent()
        val sourceRoot = parent.children.single()
        val children = sourceRoot.children.toList()
        assertTrue(children.size >= 4)

        parent.cancel(java.util.concurrent.CancellationException("owner shutdown"))
        runCurrent()
        assertTrue(sourceRoot.isCompleted)
        assertTrue(children.all { it.isCompleted })
    }

    @Test
    fun providerErrorEscapesActorAndPreventsLaterEffectsFromRunning() = runTest {
        val providerError = AssertionError("provider invariant")
        val errors = mutableListOf<Throwable>()
        val parent = SupervisorJob()
        val ownedScope = CoroutineScope(
            parent + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> errors += error },
        )
        val transport = FakeTransport(startScanFailure = providerError)
        KeepB5BleDataSource(
            transport, ownedScope, { testScheduler.currentTime }, { 123L }, FakeBackoff(1_000L),
        ).connect()
        runCurrent()

        assertEquals(1, errors.size)
        assertSame(providerError, errors.single())
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, transport.startScanCalls)
        parent.cancel()
    }

    @Test
    fun completedExactGattClosesLeaveNoDataSourceQuiesceTombstones() = runTest {
        val transport = FakeTransport()
        val source = KeepB5BleDataSource(
            transport, backgroundScope, { testScheduler.currentTime }, { 123L }, FakeBackoff(1_000L),
        )
        source.connect()
        runCurrent()
        val a = connectStandard(source, transport)
        transport.control(BleTransportEvent.GattDisconnected(a, null))
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        val b = connectStandard(source, transport)
        transport.control(BleTransportEvent.GattDisconnected(b, null))
        runCurrent()

        assertEquals(2, transport.quiesced.size)
        assertEquals(2, transport.closeCalls)
        assertEquals(0, source.resourceSnapshot.quiescedCallbackJobs)
    }

    @Test
    fun queuedBeginScanIsRejectedAfterStopAndCurrentGattIsQuiescedBeforeStopReturns() = runTest {
        val callbackGate = CompletableDeferred<Unit>()
        val teardownGate = CompletableDeferred<Unit>()
        val transport = FakeTransport(callbackGate, teardownGate)
        val logger = FakeDiagnosticLogger()
        val source = KeepB5BleDataSource(
            transport, backgroundScope, { testScheduler.currentTime }, { 123L }, FakeBackoff(1_000L), logger,
        )

        source.connect()
        source.onRunStopped()
        runCurrent()
        assertTrue(transport.startScanGuards.isEmpty())

        source.connect()
        runCurrent()
        val guard = connectStandard(source, transport)
        source.onRunStopped()
        assertEquals(listOf(guard), transport.quiesced)
        assertEquals(0, transport.closeCalls)
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, source.connectionState.value)
        assertEquals(1, transport.closeCalls)
        assertTrue(logger.events.any { it is com.healthyagent.running.diagnostics.DiagnosticEvent.BleStaleEventIgnored })
        callbackGate.complete(Unit)
        teardownGate.complete(Unit)
    }

    @Test
    fun privateSyncUsesSchedulerAndPauseResumeDropsFrozenTime() = runTest {
        val transport = FakeTransport()
        val source = KeepB5BleDataSource(
            transport, backgroundScope, { testScheduler.currentTime }, { 321L }, FakeBackoff(1_000L),
        )
        source.onRunStarted(10L)
        runCurrent()
        val attempt = transport.startScanGuards.single()
        transport.control(BleTransportEvent.ScanMatched(attempt))
        runCurrent()
        val guard = KeepB5GattGuard(attempt.epoch, attempt.connectionAttemptToken, 7L)
        transport.control(BleTransportEvent.GattCreated(guard))
        transport.control(BleTransportEvent.GattConnected(guard))
        transport.control(BleTransportEvent.ServicesDiscovered(guard, KeepB5ChannelKind.PRIVATE_B3))
        transport.control(BleTransportEvent.NotificationsEnabled(guard, KeepB5ChannelKind.PRIVATE_B3))
        runCurrent()
        assertEquals(listOf(guard to 321L), transport.startWrites)
        transport.control(BleTransportEvent.StartWriteCompleted(guard, true))
        transport.activity(PrivateWorkoutActivity(guard, testScheduler.currentTime))
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, source.connectionState.value)

        advanceTimeBy(4_500L)
        runCurrent()
        assertEquals(listOf(14L), transport.updateElapsed)
        source.onRunPaused(14L)
        advanceTimeBy(1_500L)
        runCurrent()
        assertTrue(transport.updateElapsed.drop(1).all { it == 14L })
        source.onRunResumed(14L)
        runCurrent()
        val writesAtResume = transport.updateElapsed.size
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(writesAtResume, transport.updateElapsed.size)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(15L, transport.updateElapsed.last())
    }

    @Test
    fun terminalFlushDoesNotBlockEffectActorOrLateStaleDiagnostics() = runTest {
        val flushGate = CompletableDeferred<Unit>()
        val logger = FakeDiagnosticLogger(flushGate)
        val transport = FakeTransport()
        val source = KeepB5BleDataSource(
            transport, backgroundScope, { testScheduler.currentTime }, { 123L }, FakeBackoff(1_000L), logger,
        )
        source.connect()
        runCurrent()
        val guard = connectStandard(source, transport)

        source.onRunStopped()
        runCurrent()
        assertEquals(1, logger.flushCalls)
        val before = logger.events.size
        transport.control(BleTransportEvent.GattDisconnected(guard, null))
        runCurrent()
        assertTrue(logger.events.size > before)
        assertEquals(ConnectionState.DISCONNECTED, source.connectionState.value)
        flushGate.complete(Unit)
    }

    @Test
    fun threeTransportFlowsKeepActivityPrivateAndPublishOnlyCurrentGuardData() = runTest {
        val transport = FakeTransport()
        val source = KeepB5BleDataSource(
            transport,
            backgroundScope,
            { testScheduler.currentTime },
            { 123L },
            FakeBackoff(1_000L),
        )
        val published = mutableListOf<SensorSample>()
        backgroundScope.launch { source.samples.take(2).collect(published::add) }

        source.connect()
        runCurrent()
        val a = connectStandard(source, transport)
        val aSample = sample(a, 70.0, 1_000L)
        transport.control(BleTransportEvent.DeviceInfoUpdated(a, DeviceInfo("A", 10, "a")))
        transport.sample(aSample)
        transport.activity(PrivateWorkoutActivity(a, 1_000L))
        runCurrent()
        assertEquals(DeviceInfo("A", 10, "a"), source.deviceInfo)
        assertEquals(listOf(aSample.sample), published)

        transport.control(BleTransportEvent.GattDisconnected(a, null))
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        val b = connectStandard(source, transport)
        val bSample = sample(b, 82.0, 2_000L)
        transport.sample(bSample)
        transport.sample(sample(a, 99.0, 3_000L))
        transport.activity(PrivateWorkoutActivity(a, 3_000L))
        transport.control(BleTransportEvent.DeviceInfoUpdated(a, DeviceInfo("late-A", 99, "bad")))
        transport.control(BleTransportEvent.DeviceInfoUpdated(b, DeviceInfo("B", 80, "b")))
        runCurrent()

        assertEquals(listOf(aSample.sample, bSample.sample), published)
        assertEquals(DeviceInfo("B", 80, "b"), source.deviceInfo)
    }

    @Test
    fun lifecycleProjectsConnectingErrorRetryingAndReadyStatesWithBackoff() = runTest {
        val transport = FakeTransport()
        val backoff = FakeBackoff(1_000L, 2_000L)
        val source = KeepB5BleDataSource(
            transport = transport,
            scope = backgroundScope,
            monotonicMillis = { testScheduler.currentTime },
            epochSeconds = { 123L },
            reconnectBackoff = backoff,
            diagnosticLogger = FakeDiagnosticLogger(),
        )

        source.connect()
        runCurrent()
        assertEquals(ConnectionState.CONNECTING, source.connectionState.value)
        val first = transport.startScanGuards.single()

        transport.control(BleTransportEvent.ScanFailed(first, BleFailureReason.SCAN_FAILED))
        runCurrent()
        assertEquals(ConnectionState.ERROR, source.connectionState.value)
        assertEquals(1, backoff.nextCalls)

        advanceTimeBy(999L)
        runCurrent()
        assertEquals(1, transport.startScanGuards.size)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(ConnectionState.CONNECTING, source.connectionState.value)
        assertEquals(2, transport.startScanGuards.size)

        val readyGuard = connectStandard(source, transport)
        assertEquals(ConnectionState.CONNECTED, source.connectionState.value)
        assertEquals(2, backoff.resetCalls) // explicit connect + first Ready

        transport.control(BleTransportEvent.GattDisconnected(readyGuard, null))
        runCurrent()
        assertEquals(ConnectionState.ERROR, source.connectionState.value)
        assertEquals(2, backoff.nextCalls)

        source.onRunStopped()
        assertEquals(ConnectionState.ERROR, source.connectionState.value)
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, source.connectionState.value)
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(3, backoff.resetCalls)
        assertEquals(2, transport.startScanGuards.size)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.connectStandard(
        source: KeepB5BleDataSource,
        transport: FakeTransport,
    ): KeepB5GattGuard {
        if (transport.startScanGuards.isEmpty()) {
            source.connect()
            runCurrent()
        }
        val attempt = transport.startScanGuards.last()
        transport.control(BleTransportEvent.ScanMatched(attempt))
        runCurrent()
        val guard = KeepB5GattGuard(attempt.epoch, attempt.connectionAttemptToken, transport.nextGattToken++)
        transport.control(BleTransportEvent.GattCreated(guard))
        transport.control(BleTransportEvent.GattConnected(guard))
        transport.control(BleTransportEvent.ServicesDiscovered(guard, KeepB5ChannelKind.STANDARD_HR))
        transport.control(BleTransportEvent.NotificationsEnabled(guard, KeepB5ChannelKind.STANDARD_HR))
        runCurrent()
        return guard
    }

    private fun kotlinx.coroutines.test.TestScope.newDataSource(
        transport: FakeTransport,
        clock: () -> Long,
    ) = KeepB5BleDataSource(
        transport, backgroundScope, clock, { 123L }, FakeBackoff(1_000L),
    )

    private suspend fun kotlinx.coroutines.test.TestScope.connectPrivate(
        source: KeepB5BleDataSource,
        transport: FakeTransport,
    ): KeepB5GattGuard {
        source.onRunStarted(0L)
        runCurrent()
        val attempt = transport.startScanGuards.single()
        transport.control(BleTransportEvent.ScanMatched(attempt))
        runCurrent()
        val guard = KeepB5GattGuard(attempt.epoch, attempt.connectionAttemptToken, transport.nextGattToken++)
        transport.control(BleTransportEvent.GattCreated(guard))
        transport.control(BleTransportEvent.GattConnected(guard))
        transport.control(BleTransportEvent.ServicesDiscovered(guard, KeepB5ChannelKind.PRIVATE_B3))
        transport.control(BleTransportEvent.NotificationsEnabled(guard, KeepB5ChannelKind.PRIVATE_B3))
        runCurrent()
        transport.control(BleTransportEvent.StartWriteCompleted(guard, true))
        transport.activity(PrivateWorkoutActivity(guard, 0L))
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, source.connectionState.value)
        return guard
    }

    private fun sample(guard: KeepB5GattGuard, value: Double, receivedAt: Long) =
        TokenizedSensorSample(
            guard,
            SensorSample(SampleType.HEART_RATE, value, 1_700_000_000_000L + receivedAt, "test"),
            receivedAt,
        )

    private class FakeBackoff(vararg delays: Long) : ReconnectBackoffPolicy {
        private val values = delays.toList()
        var nextCalls = 0
        var resetCalls = 0

        override fun nextDelayMillis(): Long = values[nextCalls.coerceAtMost(values.lastIndex)].also { nextCalls++ }
        override fun reset() { resetCalls++ }
    }

    private class FakeTransport(
        val callbackGate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        val teardownGate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        private var startScanFailure: Throwable? = null,
        private val controlCompletionGate: CompletableDeferred<Unit>? = null,
        private var updateFailure: Throwable? = null,
        private var heartbeatFailure: Throwable? = null,
        private val rejectedUpdateIndices: Set<Int> = emptySet(),
    ) : BleTransport {
        private val control = Channel<BleTransportEvent>(Channel.UNLIMITED)
        private val activity = Channel<PrivateWorkoutActivity>(Channel.UNLIMITED)
        private val sample = Channel<TokenizedSensorSample>(Channel.UNLIMITED)
        override val controlEvents: Flow<BleTransportEvent> = flow {
            for (event in control) {
                if (event is BleTransportEvent.SyncWriteCompleted) controlCompletionGate?.await()
                emit(event)
            }
        }
        override val privateActivities: Flow<PrivateWorkoutActivity> = activity.receiveAsFlow()
        override val samples: Flow<TokenizedSensorSample> = sample.receiveAsFlow()

        val startScanGuards = mutableListOf<KeepB5AttemptGuard>()
        var startScanCalls = 0
        var nextGattToken = 1L
        val quiesced = mutableListOf<KeepB5GattGuard>()
        var closeCalls = 0
        val startWrites = mutableListOf<Pair<KeepB5GattGuard, Long>>()
        val updateElapsed = mutableListOf<Long>()
        val updateMetrics = mutableListOf<KeepB5DisplayMetrics>()
        val heartbeatWrites = mutableListOf<KeepB5SubmittedWrite>()
        var heartbeatCalls = 0

        fun control(event: BleTransportEvent) { assertTrue(control.trySend(event).isSuccess) }
        fun activity(value: PrivateWorkoutActivity) { assertTrue(activity.trySend(value).isSuccess) }
        fun sample(value: TokenizedSensorSample) { assertTrue(sample.trySend(value).isSuccess) }

        override fun startScan(guard: KeepB5AttemptGuard): Boolean {
            startScanCalls++
            startScanGuards += guard
            val failure = startScanFailure.also { startScanFailure = null }
            if (failure != null) {
                control(BleTransportEvent.ScanFailed(guard, BleFailureReason.SCAN_FAILED))
                throw failure
            }
            return true
        }
        override fun stopScan(guard: KeepB5AttemptGuard, preserveMatchedTarget: Boolean) = Unit
        override fun connectRemembered(guard: KeepB5AttemptGuard): Boolean = true
        override fun cancelPendingConnect(guard: KeepB5AttemptGuard) = Unit
        override fun activateGattData(guard: KeepB5GattGuard) = Unit
        override fun quiesceGattCallbacks(guard: KeepB5GattGuard): Job = callbackGate.also { quiesced += guard }
        override fun sendStart(guard: KeepB5GattGuard, epochSeconds: Long): Boolean = true.also {
            startWrites += guard to epochSeconds
        }
        override fun sendWorkoutUpdate(
            submission: KeepB5SubmittedWrite,
            elapsedSeconds: Long,
            displayMetrics: KeepB5DisplayMetrics,
        ): Boolean {
            updateElapsed += elapsedSeconds
            updateMetrics += displayMetrics
            val failure = updateFailure.also { updateFailure = null }
            val accepted = failure == null && updateElapsed.size !in rejectedUpdateIndices
            control(BleTransportEvent.SyncWriteCompleted(submission, accepted))
            if (failure != null) throw failure
            return accepted
        }
        override fun sendHeartbeat(submission: KeepB5SubmittedWrite): Boolean = true.also {
            heartbeatCalls++
            heartbeatWrites += submission
            val failure = heartbeatFailure.also { heartbeatFailure = null }
            control(BleTransportEvent.SyncWriteCompleted(submission, failure == null))
            if (failure != null) throw failure
        }
        override fun closeGatt(guard: KeepB5GattGuard, disconnectFirst: Boolean): Job = teardownGate.also {
            closeCalls++
        }
        override fun shutdown(): Job = CompletableDeferred(Unit)
    }
}
