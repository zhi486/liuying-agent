package com.healthyagent.running.data.sensor.ble

import android.bluetooth.BluetoothGattCharacteristic
import com.healthyagent.running.diagnostics.BleFailureReason
import com.healthyagent.running.diagnostics.BleOperation
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.testing.FakeDiagnosticLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class KeepB5GattBindingTest {
    @Test
    fun `battery and firmware without read property are unavailable without platform read`() {
        listOf("battery", "firmware").forEach { metadata ->
            val platform = FakeAndroidGattMetadataReadPlatform(accepted = true)

            assertEquals(
                metadata,
                GattIoSubmission.UNAVAILABLE,
                submitAndroidGattMetadataRead(
                    properties = BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    platform = platform,
                ),
            )
            assertEquals("$metadata platform read calls", 0, platform.readCalls)
        }
    }

    @Test
    fun `readable battery and firmware map platform result on api 33 and legacy`() {
        listOf("battery", "firmware").forEach { metadata ->
            listOf("legacy" to 32, "api33" to 33).forEach { (api, _) ->
                listOf(
                    true to GattIoSubmission.ACCEPTED,
                    false to GattIoSubmission.REJECTED,
                ).forEach { (accepted, expected) ->
                    val platform = FakeAndroidGattMetadataReadPlatform(accepted)

                    assertEquals(
                        "$metadata $api accepted=$accepted",
                        expected,
                        submitAndroidGattMetadataRead(
                            properties = BluetoothGattCharacteristic.PROPERTY_READ,
                            platform = platform,
                        ),
                    )
                    assertEquals("$metadata $api platform read calls", 1, platform.readCalls)
                }
            }
        }
    }

    @Test(timeout = 10_000L)
    fun `shutdown waits for connect job publication and prevents registry resurrection`() {
        val executor = Executors.newFixedThreadPool(3)
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val bindingRegistered = CountDownLatch(1)
        val releaseJobPublication = CountDownLatch(1)
        val connectorCalls = AtomicInteger()
        val coordinator = KeepB5GattBindingCoordinator(
            connector = KeepB5GattConnector { _, _, _ ->
                connectorCalls.incrementAndGet()
                FakeGattSession()
            },
            scope = scope,
            connectDispatcher = dispatcher,
            newGattToken = AtomicLong()::incrementAndGet,
            eventMapper = KeepB5GattEventMapper(),
            sink = RecordingSink(),
            diagnosticLogger = FakeDiagnosticLogger(),
            onBindingRegisteredForTest = {
                bindingRegistered.countDown()
                assertTrue(releaseJobPublication.await(5, TimeUnit.SECONDS))
            },
        )
        try {
            val connectResult = executor.submit<Boolean> {
                coordinator.connect(KeepB5AttemptGuard(1L, 99L), FakeTarget("publication-race"))
            }
            assertTrue(bindingRegistered.await(5, TimeUnit.SECONDS))

            val shutdown = coordinator.shutdown()
            val shutdownCompleted = CountDownLatch(1)
            shutdown.invokeOnCompletion { shutdownCompleted.countDown() }
            assertFalse(shutdownCompleted.await(200, TimeUnit.MILLISECONDS))

            releaseJobPublication.countDown()
            assertFalse(connectResult.get(5, TimeUnit.SECONDS))
            assertTrue(shutdownCompleted.await(5, TimeUnit.SECONDS))
            assertEquals(0, connectorCalls.get())
            assertEquals(
                KeepB5GattBindingCoordinator.RegistrySnapshot(0, 0, 0),
                coordinator.registrySnapshotForTest(),
            )
        } finally {
            releaseJobPublication.countDown()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 10_000L)
    fun `concurrent quiesce publishes one waitable callback job and close waits for it`() {
        val executor = Executors.newFixedThreadPool(4)
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val shutdownEntered = CountDownLatch(1)
        val releaseShutdown = CountDownLatch(1)
        val callbackGate = CompletableDeferred<Unit>()
        val session = FakeGattSession(
            callbackJob = callbackGate,
            onShutdownCallbacks = {
                shutdownEntered.countDown()
                assertTrue(releaseShutdown.await(5, TimeUnit.SECONDS))
            },
        )
        val sink = RecordingSink(expectedCreated = 1)
        val coordinator = KeepB5GattBindingCoordinator(
            connector = KeepB5GattConnector { _, _, _ -> session },
            scope = scope,
            connectDispatcher = dispatcher,
            newGattToken = AtomicLong()::incrementAndGet,
            eventMapper = KeepB5GattEventMapper(),
            sink = sink,
            diagnosticLogger = FakeDiagnosticLogger(),
        )
        try {
            assertTrue(coordinator.connect(KeepB5AttemptGuard(7L, 9L), FakeTarget("publication")))
            assertTrue(sink.awaitCreated(1))
            val guard = (sink.controls.single() as BleTransportEvent.GattCreated).guard

            val firstCall = executor.submit<Job> { coordinator.quiesceGattCallbacks(guard) }
            assertTrue(shutdownEntered.await(5, TimeUnit.SECONDS))
            val secondJob = executor.submit<Job> {
                coordinator.quiesceGattCallbacks(guard)
            }.get(5, TimeUnit.SECONDS)
            val thirdJob = executor.submit<Job> {
                coordinator.quiesceGattCallbacks(guard)
            }.get(5, TimeUnit.SECONDS)
            assertSame(secondJob, thirdJob)
            assertFalse(secondJob.isCompleted)

            val teardown = coordinator.close(guard, disconnectFirst = true)
            val teardownCompleted = CountDownLatch(1)
            teardown.invokeOnCompletion { teardownCompleted.countDown() }
            assertFalse(teardownCompleted.await(200, TimeUnit.MILLISECONDS))

            releaseShutdown.countDown()
            val firstJob = firstCall.get(5, TimeUnit.SECONDS)
            assertSame(firstJob, secondJob)
            assertEquals(1, session.shutdownCalls.get())
            assertFalse(teardownCompleted.await(200, TimeUnit.MILLISECONDS))

            callbackGate.complete(Unit)
            assertTrue(teardownCompleted.await(5, TimeUnit.SECONDS))
            assertEquals(1, session.disconnectCalls.get())
            assertEquals(1, session.closeCalls.get())
        } finally {
            releaseShutdown.countDown()
            callbackGate.complete(Unit)
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `shutdown callback Error still publishes terminal job and teardown completes`() = runTest {
        val failure = AssertionError("callback shutdown failed")
        val session = FakeGattSession(onShutdownCallbacks = { throw failure })
        val sink = RecordingSink()
        val coordinator = coordinator(
            connector = KeepB5GattConnector { _, _, _ -> session },
            sink = sink,
        )
        assertTrue(coordinator.connect(KeepB5AttemptGuard(7L, 10L), FakeTarget("shutdown-error")))
        runCurrent()
        val guard = (sink.controls.single() as BleTransportEvent.GattCreated).guard

        val callbackJob = coordinator.quiesceGattCallbacks(guard)
        assertSame(callbackJob, coordinator.quiesceGattCallbacks(guard))
        assertTrue(callbackJob.isCompleted)
        val teardown = coordinator.close(guard, disconnectFirst = true)
        advanceUntilIdle()

        assertTrue(teardown.isCompleted)
        assertEquals(1, session.shutdownCalls.get())
        assertEquals(1, session.disconnectCalls.get())
        assertEquals(1, session.closeCalls.get())
    }

    @Test
    fun `synchronous signal binds once but cannot pass GattCreated or activation gate`() = runTest {
        val sink = RecordingSink()
        lateinit var listener: KeepB5GattSessionListener
        val session = FakeGattSession()
        val coordinator = coordinator(
            connector = KeepB5GattConnector { _, _, captured ->
                listener = captured
                captured.onSignal(session, RawGattSignal.Connected)
                session
            },
            sink = sink,
        )
        val attempt = KeepB5AttemptGuard(1L, 2L)

        assertTrue(coordinator.connect(attempt, FakeTarget("A")))
        runCurrent()

        val created = sink.controls.single() as BleTransportEvent.GattCreated
        assertEquals(attempt, created.guard.toAttemptGuard())
        assertEquals(1, sink.controls.size)

        listener.onSignal(session, RawGattSignal.Connected)
        assertEquals(1, sink.controls.size)
        coordinator.activateGattData(created.guard)
        listener.onSignal(session, RawGattSignal.Connected)
        assertEquals(
            listOf(created, BleTransportEvent.GattConnected(created.guard)),
            sink.controls,
        )
    }

    @Test(timeout = 10_000L)
    fun `reverse A B returns and concurrent callback binding allocate distinct immutable guards`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val sink = RecordingSink(expectedCreated = 2)
        val tokens = AtomicLong(40L)
        val entered = mapOf("A" to CountDownLatch(1), "B" to CountDownLatch(1))
        val release = mapOf("A" to CountDownLatch(1), "B" to CountDownLatch(1))
        val sessions = mapOf("A" to FakeGattSession(), "B" to FakeGattSession())
        val listeners = ConcurrentHashMap<String, KeepB5GattSessionListener>()
        val coordinator = KeepB5GattBindingCoordinator(
            connector = KeepB5GattConnector { target, _, listener ->
                val id = (target as FakeTarget).id
                listeners[id] = listener
                entered.getValue(id).countDown()
                assertTrue(release.getValue(id).await(5, TimeUnit.SECONDS))
                val session = sessions.getValue(id)
                listener.onSignal(session, RawGattSignal.Connected)
                session
            },
            scope = scope,
            connectDispatcher = dispatcher,
            newGattToken = tokens::incrementAndGet,
            eventMapper = KeepB5GattEventMapper(),
            sink = sink,
            diagnosticLogger = FakeDiagnosticLogger(),
        )
        try {
            val attemptA = KeepB5AttemptGuard(2L, 10L)
            val attemptB = KeepB5AttemptGuard(2L, 11L)
            assertTrue(coordinator.connect(attemptA, FakeTarget("A")))
            assertTrue(coordinator.connect(attemptB, FakeTarget("B")))
            assertTrue(entered.getValue("A").await(5, TimeUnit.SECONDS))
            assertTrue(entered.getValue("B").await(5, TimeUnit.SECONDS))

            release.getValue("B").countDown()
            assertTrue(sink.awaitCreated(1))
            release.getValue("A").countDown()
            assertTrue(sink.awaitCreated(2))

            val created = sink.controls.filterIsInstance<BleTransportEvent.GattCreated>()
            val guardA = created.single { it.guard.connectionAttemptToken == 10L }.guard
            val guardB = created.single { it.guard.connectionAttemptToken == 11L }.guard
            assertNotEquals(guardA.gattToken, guardB.gattToken)
            assertEquals(attemptA, guardA.toAttemptGuard())
            assertEquals(attemptB, guardB.toAttemptGuard())

            coordinator.activateGattData(guardA)
            coordinator.activateGattData(guardB)
            listeners.getValue("A").onSignal(sessions.getValue("A"), RawGattSignal.Connected)
            listeners.getValue("B").onSignal(sessions.getValue("B"), RawGattSignal.Connected)
            assertTrue(sink.controls.contains(BleTransportEvent.GattConnected(guardA)))
            assertTrue(sink.controls.contains(BleTransportEvent.GattConnected(guardB)))
        } finally {
            release.values.forEach(CountDownLatch::countDown)
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `pending cancellation closes gate and prevents connector and business events`() = runTest {
        val calls = AtomicInteger()
        val sink = RecordingSink()
        val coordinator = coordinator(
            connector = KeepB5GattConnector { _, _, _ ->
                calls.incrementAndGet()
                FakeGattSession()
            },
            sink = sink,
        )
        val guard = KeepB5AttemptGuard(3L, 1L)

        assertTrue(coordinator.connect(guard, FakeTarget("pending")))
        coordinator.cancelPendingConnect(guard)
        runCurrent()

        assertEquals(0, calls.get())
        assertTrue(sink.controls.isEmpty())
    }

    @Test(timeout = 10_000L)
    fun `started cancellation returns immediately and late handle is cleaned once without events`() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = FakeGattSession()
        val sink = RecordingSink()
        val coordinator = KeepB5GattBindingCoordinator(
            connector = KeepB5GattConnector { _, _, _ ->
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                session
            },
            scope = scope,
            connectDispatcher = dispatcher,
            newGattToken = AtomicLong()::incrementAndGet,
            eventMapper = KeepB5GattEventMapper(),
            sink = sink,
            diagnosticLogger = FakeDiagnosticLogger(),
        )
        try {
            val guard = KeepB5AttemptGuard(4L, 1L)
            assertTrue(coordinator.connect(guard, FakeTarget("started")))
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            coordinator.cancelPendingConnect(guard)
            assertTrue(sink.controls.isEmpty())
            release.countDown()

            assertTrue(session.closed.await(5, TimeUnit.SECONDS))
            assertEquals(1, session.shutdownCalls.get())
            assertEquals(1, session.disconnectCalls.get())
            assertEquals(1, session.closeCalls.get())
            assertTrue(sink.controls.isEmpty())
        } finally {
            release.countDown()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `null throwable and cancelled scope each publish one ConnectFailed without a token`() = runTest {
        suspend fun runCase(connector: KeepB5GattConnector): Pair<List<BleTransportEvent>, Long> {
            val sink = RecordingSink()
            val tokens = AtomicLong()
            val coordinator = coordinator(connector, sink, tokens::incrementAndGet)
            assertTrue(coordinator.connect(KeepB5AttemptGuard(5L, 1L), FakeTarget("failure")))
            runCurrent()
            return sink.controls to tokens.get()
        }

        val nullCase = runCase(KeepB5GattConnector { _, _, _ -> null })
        val throwCase = runCase(KeepB5GattConnector { _, _, _ -> throw AssertionError("connect") })
        listOf(nullCase, throwCase).forEach { (events, tokens) ->
            assertEquals(
                listOf(BleTransportEvent.ConnectFailed(KeepB5AttemptGuard(5L, 1L), BleFailureReason.GATT_ERROR)),
                events,
            )
            assertEquals(0L, tokens)
        }

        val cancelledJob = Job().apply { cancel() }
        val cancelledScope = CoroutineScope(cancelledJob + StandardTestDispatcher(testScheduler))
        val sink = RecordingSink()
        val rejected = KeepB5GattBindingCoordinator(
            KeepB5GattConnector { _, _, _ -> FakeGattSession() },
            cancelledScope,
            StandardTestDispatcher(testScheduler),
            AtomicLong()::incrementAndGet,
            KeepB5GattEventMapper(),
            sink,
            FakeDiagnosticLogger(),
        )
        assertFalse(rejected.connect(KeepB5AttemptGuard(5L, 2L), FakeTarget("rejected")))
        assertEquals(1, sink.controls.filterIsInstance<BleTransportEvent.ConnectFailed>().size)

        val rejectingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                throw RejectedExecutionException("dispatcher rejected")
            }
        }
        val rejectionSink = RecordingSink()
        val rejectionCoordinator = KeepB5GattBindingCoordinator(
            KeepB5GattConnector { _, _, _ -> FakeGattSession() },
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            rejectingDispatcher,
            AtomicLong()::incrementAndGet,
            KeepB5GattEventMapper(),
            rejectionSink,
            FakeDiagnosticLogger(),
        )
        assertFalse(rejectionCoordinator.connect(KeepB5AttemptGuard(5L, 3L), FakeTarget("dispatch-rejected")))
        assertEquals(1, rejectionSink.controls.filterIsInstance<BleTransportEvent.ConnectFailed>().size)
    }

    @Test
    fun `terminal unbound attempts leave no registry state and do not poison later guards`() = runTest {
        val calls = AtomicInteger()
        val sink = RecordingSink()
        val coordinator = coordinator(
            connector = KeepB5GattConnector { _, _, _ ->
                when (calls.incrementAndGet()) {
                    1 -> null
                    2 -> throw IllegalStateException("connect failed")
                    3 -> throw java.util.concurrent.CancellationException("connect cancelled unexpectedly")
                    else -> null
                }
            },
            sink = sink,
        )
        val sameGuard = KeepB5AttemptGuard(5L, 20L)
        repeat(3) {
            assertTrue(coordinator.connect(sameGuard, FakeTarget("same-$it")))
            runCurrent()
            assertEquals(
                KeepB5GattBindingCoordinator.RegistrySnapshot(0, 0, 0),
                coordinator.registrySnapshotForTest(),
            )
        }
        val differentGuard = KeepB5AttemptGuard(5L, 21L)
        assertTrue(coordinator.connect(differentGuard, FakeTarget("different")))
        runCurrent()
        assertEquals(
            KeepB5GattBindingCoordinator.RegistrySnapshot(0, 0, 0),
            coordinator.registrySnapshotForTest(),
        )
        assertEquals(4, sink.controls.filterIsInstance<BleTransportEvent.ConnectFailed>().size)

        val pendingSink = RecordingSink()
        val pendingCoordinator = coordinator(
            connector = KeepB5GattConnector { _, _, _ -> error("cancelled attempt must not connect") },
            sink = pendingSink,
        )
        val cancelledGuard = KeepB5AttemptGuard(5L, 22L)
        assertTrue(pendingCoordinator.connect(cancelledGuard, FakeTarget("cancelled")))
        pendingCoordinator.cancelPendingConnect(cancelledGuard)
        runCurrent()
        assertEquals(
            KeepB5GattBindingCoordinator.RegistrySnapshot(0, 0, 0),
            pendingCoordinator.registrySnapshotForTest(),
        )
        assertTrue(pendingSink.controls.filterIsInstance<BleTransportEvent.ConnectFailed>().isEmpty())

        val rejectedDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                throw RejectedExecutionException("dispatcher rejected")
            }
        }
        val rejectedCoordinator = KeepB5GattBindingCoordinator(
            connector = KeepB5GattConnector { _, _, _ -> FakeGattSession() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            connectDispatcher = rejectedDispatcher,
            newGattToken = AtomicLong()::incrementAndGet,
            eventMapper = KeepB5GattEventMapper(),
            sink = RecordingSink(),
            diagnosticLogger = FakeDiagnosticLogger(),
        )
        assertFalse(rejectedCoordinator.connect(KeepB5AttemptGuard(5L, 23L), FakeTarget("rejected")))
        assertEquals(
            KeepB5GattBindingCoordinator.RegistrySnapshot(0, 0, 0),
            rejectedCoordinator.registrySnapshotForTest(),
        )

        val cancelledParent = Job().apply { cancel() }
        val cancelledCoordinator = KeepB5GattBindingCoordinator(
            connector = KeepB5GattConnector { _, _, _ -> FakeGattSession() },
            scope = CoroutineScope(cancelledParent + StandardTestDispatcher(testScheduler)),
            connectDispatcher = StandardTestDispatcher(testScheduler),
            newGattToken = AtomicLong()::incrementAndGet,
            eventMapper = KeepB5GattEventMapper(),
            sink = RecordingSink(),
            diagnosticLogger = FakeDiagnosticLogger(),
        )
        assertFalse(cancelledCoordinator.connect(KeepB5AttemptGuard(5L, 24L), FakeTarget("scope-cancelled")))
        assertEquals(
            KeepB5GattBindingCoordinator.RegistrySnapshot(0, 0, 0),
            cancelledCoordinator.registrySnapshotForTest(),
        )
    }

    @Test
    fun `exact guard owns activation signals and all three write methods`() = runTest {
        val sink = RecordingSink()
        val session = FakeGattSession()
        lateinit var listener: KeepB5GattSessionListener
        val coordinator = coordinator(
            KeepB5GattConnector { _, _, captured -> listener = captured; session },
            sink,
        )
        assertTrue(coordinator.connect(KeepB5AttemptGuard(6L, 1L), FakeTarget("writes")))
        runCurrent()
        val guard = (sink.controls.single() as BleTransportEvent.GattCreated).guard
        val stale = guard.copy(gattToken = guard.gattToken + 1L)

        coordinator.activateGattData(stale)
        listener.onSignal(session, RawGattSignal.Connected)
        assertFalse(coordinator.sendStart(stale, 123L))
        coordinator.activateGattData(guard)
        listener.onSignal(session, RawGattSignal.Connected)

        val update = KeepB5SubmittedWrite(7L, guard, 9L, KeepB5SyncKind.UPDATE_WORKOUT)
        val heartbeat = KeepB5SubmittedWrite(8L, guard, 9L, KeepB5SyncKind.HEART_BEAT)
        assertTrue(coordinator.sendStart(guard, 123L))
        assertTrue(coordinator.sendWorkoutUpdate(update, 45L, KeepB5DisplayMetrics(153, 816)))
        assertTrue(coordinator.sendHeartbeat(heartbeat))
        assertEquals(listOf(123L), session.startArguments)
        assertEquals(listOf(45L), session.updateArguments)
        assertEquals(listOf(KeepB5DisplayMetrics(153, 816)), session.updateMetrics)
        assertEquals(1, session.heartbeatCalls.get())
        assertTrue(sink.controls.contains(BleTransportEvent.GattConnected(guard)))
        assertTrue(sink.controls.contains(BleTransportEvent.StartWriteCompleted(guard, true)))
        assertTrue(sink.controls.contains(BleTransportEvent.SyncWriteCompleted(update, true)))
        assertTrue(sink.controls.contains(BleTransportEvent.SyncWriteCompleted(heartbeat, true)))
    }

    @Test
    fun `write operation terminal is once only and cancellation completes false before propagating`() = runTest {
        val sink = RecordingSink()
        val session = FakeGattSession(onUpdate = { false })
        val coordinator = coordinator(KeepB5GattConnector { _, _, _ -> session }, sink)
        assertTrue(coordinator.connect(KeepB5AttemptGuard(6L, 2L), FakeTarget("once")))
        runCurrent()
        val guard = (sink.controls.single() as BleTransportEvent.GattCreated).guard
        coordinator.activateGattData(guard)
        val submission = KeepB5SubmittedWrite(91L, guard, 1L, KeepB5SyncKind.UPDATE_WORKOUT)

        assertFalse(coordinator.sendWorkoutUpdate(submission, 12L))
        assertFalse(coordinator.sendWorkoutUpdate(submission, 12L))
        assertEquals(listOf(12L), session.updateArguments)
        assertEquals(
            1,
            sink.controls.count { it == BleTransportEvent.SyncWriteCompleted(submission, false) },
        )

        val cancellation = java.util.concurrent.CancellationException("write cancelled")
        val cancelledSink = RecordingSink()
        val logger = FakeDiagnosticLogger()
        val cancelledSession = FakeGattSession(onHeartbeat = { throw cancellation })
        val cancelledCoordinator = coordinator(
            KeepB5GattConnector { _, _, _ -> cancelledSession },
            cancelledSink,
            logger = logger,
        )
        assertTrue(cancelledCoordinator.connect(KeepB5AttemptGuard(6L, 3L), FakeTarget("cancel-write")))
        runCurrent()
        val cancelledGuard = (cancelledSink.controls.single() as BleTransportEvent.GattCreated).guard
        cancelledCoordinator.activateGattData(cancelledGuard)
        val heartbeat = KeepB5SubmittedWrite(92L, cancelledGuard, 1L, KeepB5SyncKind.HEART_BEAT)

        assertSame(
            cancellation,
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                cancelledCoordinator.sendHeartbeat(heartbeat)
            },
        )
        assertTrue(cancelledSink.controls.contains(BleTransportEvent.SyncWriteCompleted(heartbeat, false)))
        assertEquals(
            listOf(BleOperation.HEARTBEAT_WRITE),
            logger.events.filterIsInstance<DiagnosticEvent.BleOperationFailed>().map { it.operation },
        )
    }

    @Test
    fun `write Error completes and records before same Error propagates without blocking next operation`() = runTest {
        val failure = AssertionError("provider fatal")
        val calls = AtomicInteger()
        val session = FakeGattSession(onHeartbeat = {
            if (calls.incrementAndGet() == 1) throw failure
            true
        })
        val sink = RecordingSink()
        val logger = FakeDiagnosticLogger()
        val coordinator = coordinator(
            KeepB5GattConnector { _, _, _ -> session },
            sink,
            logger = logger,
        )
        assertTrue(coordinator.connect(KeepB5AttemptGuard(6L, 4L), FakeTarget("error-write")))
        runCurrent()
        val guard = (sink.controls.single() as BleTransportEvent.GattCreated).guard
        coordinator.activateGattData(guard)
        val failed = KeepB5SubmittedWrite(93L, guard, 1L, KeepB5SyncKind.HEART_BEAT)
        val next = KeepB5SubmittedWrite(94L, guard, 1L, KeepB5SyncKind.HEART_BEAT)

        assertSame(
            failure,
            assertThrows(AssertionError::class.java) {
                coordinator.sendHeartbeat(failed)
            },
        )
        assertEquals(
            1,
            sink.controls.count { it == BleTransportEvent.SyncWriteCompleted(failed, false) },
        )
        assertEquals(
            listOf(BleOperation.HEARTBEAT_WRITE),
            logger.events.filterIsInstance<DiagnosticEvent.BleOperationFailed>().map { it.operation },
        )

        assertFalse(coordinator.sendHeartbeat(failed))
        assertTrue(coordinator.sendHeartbeat(next))
        assertEquals(2, calls.get())
        assertEquals(
            1,
            sink.controls.count { it == BleTransportEvent.SyncWriteCompleted(next, true) },
        )
    }

    @Test
    fun `quiesce closes synchronously and close reuses callback and teardown jobs in exact order`() = runTest {
        val callbackGate = CompletableDeferred<Unit>()
        val session = FakeGattSession(callbackJob = callbackGate)
        val sink = RecordingSink()
        val coordinator = coordinator(KeepB5GattConnector { _, _, _ -> session }, sink)
        assertTrue(coordinator.connect(KeepB5AttemptGuard(7L, 1L), FakeTarget("close")))
        runCurrent()
        val guard = (sink.controls.single() as BleTransportEvent.GattCreated).guard
        coordinator.activateGattData(guard)

        val callbackJob = coordinator.quiesceGattCallbacks(guard)
        assertSame(callbackJob, coordinator.quiesceGattCallbacks(guard))
        assertFalse(callbackJob.isCompleted)
        assertEquals(1, session.shutdownCalls.get())
        assertFalse(coordinator.sendHeartbeat(KeepB5SubmittedWrite(1L, guard, 1L, KeepB5SyncKind.HEART_BEAT)))

        val teardown = coordinator.close(guard, disconnectFirst = true)
        runCurrent()
        assertEquals(listOf("shutdown", "disconnect", "close"), session.trace)
        assertFalse(teardown.isCompleted)
        assertSame(teardown, coordinator.close(guard, disconnectFirst = false))
        callbackGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(teardown.isCompleted)
        assertEquals(1, session.disconnectCalls.get())
        assertEquals(1, session.closeCalls.get())
        assertEquals(1, session.shutdownCalls.get())
    }

    @Test
    fun `teardown contains ordinary failures joins callbacks and cancellation propagates after cleanup`() = runTest {
        val callbackGate = CompletableDeferred<Unit>()
        val cancellation = java.util.concurrent.CancellationException("disconnect cancelled")
        val session = FakeGattSession(
            callbackJob = callbackGate,
            onDisconnect = { throw cancellation },
            onClose = { throw IllegalStateException("close") },
        )
        val logger = FakeDiagnosticLogger()
        val sink = RecordingSink()
        val coordinator = coordinator(
            KeepB5GattConnector { _, _, _ -> session },
            sink,
            logger = logger,
        )
        assertTrue(coordinator.connect(KeepB5AttemptGuard(8L, 1L), FakeTarget("errors")))
        runCurrent()
        val guard = (sink.controls.single() as BleTransportEvent.GattCreated).guard
        val teardown = coordinator.close(guard, disconnectFirst = true)
        var completion: Throwable? = null
        teardown.invokeOnCompletion { completion = it }
        runCurrent()
        assertEquals(listOf("shutdown", "disconnect", "close"), session.trace)
        assertFalse(teardown.isCompleted)
        callbackGate.complete(Unit)
        advanceUntilIdle()

        assertSame(cancellation, completion)
        assertEquals(
            listOf(BleOperation.CLOSE_GATT),
            logger.events.filterIsInstance<DiagnosticEvent.BleOperationFailed>().map { it.operation },
        )
        assertSame(teardown, coordinator.close(guard, disconnectFirst = true))
        assertEquals(1, session.disconnectCalls.get())
        assertEquals(1, session.closeCalls.get())
    }

    @Test
    fun `shutdown quiesces and closes every binding waits callback jobs and is idempotent`() = runTest {
        val callbackA = CompletableDeferred<Unit>()
        val callbackB = CompletableDeferred<Unit>()
        val sessions = mapOf("A" to FakeGattSession(callbackA), "B" to FakeGattSession(callbackB))
        val sink = RecordingSink()
        val coordinator = coordinator(
            KeepB5GattConnector { target, _, _ -> sessions.getValue((target as FakeTarget).id) },
            sink,
        )
        assertTrue(coordinator.connect(KeepB5AttemptGuard(9L, 1L), FakeTarget("A")))
        assertTrue(coordinator.connect(KeepB5AttemptGuard(9L, 2L), FakeTarget("B")))
        runCurrent()

        val shutdown = coordinator.shutdown()
        assertSame(shutdown, coordinator.shutdown())
        assertEquals(1, sessions.getValue("A").shutdownCalls.get())
        assertEquals(1, sessions.getValue("B").shutdownCalls.get())
        assertFalse(coordinator.connect(KeepB5AttemptGuard(9L, 3L), FakeTarget("C")))
        runCurrent()
        assertFalse(shutdown.isCompleted)

        callbackA.complete(Unit)
        callbackB.complete(Unit)
        advanceUntilIdle()
        assertTrue(shutdown.isCompleted)
        sessions.values.forEach { session ->
            assertEquals(1, session.disconnectCalls.get())
            assertEquals(1, session.closeCalls.get())
            assertEquals(1, session.shutdownCalls.get())
        }
    }

    @Test
    fun `callback ingress snapshots raw values and samples both clocks once per payload callback`() {
        val events = mutableListOf<RawGattCallbackEvent>()
        val monotonicCalls = AtomicInteger()
        val wallClockCalls = AtomicInteger()
        val ingress = KeepB5GattCallbackIngress(
            offer = { event -> events.add(event) },
            monotonicMillis = { 100L + monotonicCalls.incrementAndGet() },
            wallClockMillis = { 1_000L + wallClockCalls.incrementAndGet() },
        )
        val serviceUuid = uuid16("190a")
        val characteristicUuid = uuid16("0002")
        val characteristics = mutableListOf(GattCharacteristicSnapshot(characteristicUuid, 0x10))
        val services = linkedMapOf(serviceUuid to characteristics)
        val notification = byteArrayOf(1, 2, 3)
        val read = byteArrayOf(4, 5, 6)

        assertTrue(ingress.servicesDiscovered(0, GattServiceSnapshot(services)))
        assertTrue(ingress.characteristicChanged(serviceUuid, characteristicUuid, notification))
        assertTrue(ingress.characteristicRead(0, serviceUuid, characteristicUuid, read))
        characteristics.clear()
        services.clear()
        notification[0] = 99
        read[0] = 99

        val serviceEvent = events[0] as RawGattCallbackEvent.ServicesDiscovered
        assertEquals(
            listOf(GattCharacteristicSnapshot(characteristicUuid, 0x10)),
            serviceEvent.services.characteristicsByService.getValue(serviceUuid),
        )
        val notificationEvent = events[1] as RawGattCallbackEvent.CharacteristicChanged
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(notificationEvent.value))
        assertEquals(101L, notificationEvent.receivedAtMillis)
        assertEquals(1_001L, notificationEvent.wallClockMillis)
        val readEvent = events[2] as RawGattCallbackEvent.CharacteristicRead
        assertTrue(byteArrayOf(4, 5, 6).contentEquals(readEvent.value))
        assertEquals(102L, readEvent.receivedAtMillis)
        assertEquals(1_002L, readEvent.wallClockMillis)
        assertEquals(2, monotonicCalls.get())
        assertEquals(2, wallClockCalls.get())
    }

    @Test
    fun `android session queues pending callbacks then initializes and replays FIFO after activation`() = runTest {
        val gate = KeepB5GattRegistrationGate()
        val platform = FakeAndroidGattPlatform()
        val signals = mutableListOf<RawGattSignal>()
        val session = AndroidKeepB5GattSession(
            deviceName = "Keep B5 pending",
            registrationGate = gate,
            listener = RecordingSessionListener(signals),
            processorScope = this,
            processorDispatcher = StandardTestDispatcher(testScheduler),
            diagnosticLogger = FakeDiagnosticLogger(),
        )
        session.bindPlatform(platform)
        session.callbackIngress.connectionStateChanged(status = 0, newState = 2)
        session.callbackIngress.servicesDiscovered(
            status = 0,
            services = privateServices(),
        )

        runCurrent()
        assertTrue(signals.isEmpty())
        assertTrue(platform.trace.isEmpty())

        assertTrue(gate.activate())
        runCurrent()
        assertEquals(
            listOf(
                RawGattSignal.DeviceInfoUpdated(
                    com.healthyagent.running.core.model.DeviceInfo("Keep B5 pending", null, null),
                ),
                RawGattSignal.Connected,
                RawGattSignal.ServicesDiscovered(KeepB5ChannelKind.PRIVATE_B3),
            ),
            signals,
        )
        assertEquals(listOf("discover", "enable:PRIVATE_B3"), platform.trace)

        val shutdown = session.shutdownCallbacks()
        assertSame(shutdown, session.shutdownCallbacks())
        assertFalse(session.callbackIngress.connectionStateChanged(status = 0, newState = 2))
        runCurrent()
        assertTrue(shutdown.isCompleted)
        assertEquals(listOf("discover", "enable:PRIVATE_B3"), platform.trace)
    }

    @Test
    fun `android sessions isolate metadata write target request ids and no response write type`() = runTest {
        val gateA = KeepB5GattRegistrationGate()
        val gateB = KeepB5GattRegistrationGate()
        val platformA = FakeAndroidGattPlatform()
        val platformB = FakeAndroidGattPlatform()
        val signalsA = mutableListOf<RawGattSignal>()
        val signalsB = mutableListOf<RawGattSignal>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sessionA = AndroidKeepB5GattSession(
            "A",
            gateA,
            RecordingSessionListener(signalsA),
            this,
            dispatcher,
            FakeDiagnosticLogger(),
        )
        val sessionB = AndroidKeepB5GattSession(
            "B",
            gateB,
            RecordingSessionListener(signalsB),
            this,
            dispatcher,
            FakeDiagnosticLogger(),
        )
        sessionA.bindPlatform(platformA)
        sessionB.bindPlatform(platformB)
        assertTrue(gateA.activate())
        assertTrue(gateB.activate())
        runCurrent()

        assertTrue(sessionA.sendStart(10L))
        assertTrue(sessionA.sendWorkoutUpdate(20L, KeepB5DisplayMetrics(186, 360)))
        org.junit.Assert.assertArrayEquals(
            KeepB5B3Protocol.buildWorkoutStatusRequest(2, 20L, KeepB5DisplayMetrics(186, 360)),
            platformA.frames.last(),
        )
        assertTrue(sessionA.sendHeartbeat())
        assertTrue(sessionB.sendStart(30L))
        assertTrue(sessionB.sendHeartbeat())
        assertEquals(listOf(1, 2, 3), platformA.frames.map(::requestId))
        assertEquals(listOf(1, 2), platformB.frames.map(::requestId))
        assertTrue(platformA.frames.none { frameA -> platformB.frames.any { it === frameA } })
        assertEquals(listOf(1, 1, 1), platformA.writeTypes)
        assertEquals(listOf(1, 1), platformB.writeTypes)

        sessionA.callbackIngress.characteristicRead(
            0,
            uuid16("180f"),
            uuid16("2a19"),
            byteArrayOf(40),
        )
        sessionA.callbackIngress.characteristicRead(
            0,
            uuid16("180a"),
            uuid16("2a26"),
            "fw-a".encodeToByteArray(),
        )
        sessionB.callbackIngress.characteristicRead(
            0,
            uuid16("180f"),
            uuid16("2a19"),
            byteArrayOf(80),
        )
        sessionB.callbackIngress.characteristicRead(
            0,
            uuid16("180a"),
            uuid16("2a26"),
            "fw-b".encodeToByteArray(),
        )
        runCurrent()

        assertEquals(
            com.healthyagent.running.core.model.DeviceInfo("A", 40, "fw-a"),
            signalsA.filterIsInstance<RawGattSignal.DeviceInfoUpdated>().last().deviceInfo,
        )
        assertEquals(
            com.healthyagent.running.core.model.DeviceInfo("B", 80, "fw-b"),
            signalsB.filterIsInstance<RawGattSignal.DeviceInfoUpdated>().last().deviceInfo,
        )
        sessionA.shutdownCallbacks()
        sessionB.shutdownCallbacks()
        runCurrent()
    }

    @Test
    fun `android connector synchronous callback stays behind binding activation and replays after GattCreated`() = runTest {
        val platform = FakeAndroidGattPlatform()
        val connector = androidConnector { _, callback ->
            callback.connectionStateChanged(status = 0, newState = 2)
            platform
        }
        val sink = RecordingSink()
        val coordinator = coordinator(connector, sink)

        assertTrue(coordinator.connect(KeepB5AttemptGuard(10L, 1L), FakeTarget("sync")))
        runCurrent()

        val created = sink.controls.single() as BleTransportEvent.GattCreated
        assertTrue(platform.trace.isEmpty())
        coordinator.activateGattData(created.guard)
        runCurrent()
        assertEquals(
            listOf(
                created,
                BleTransportEvent.DeviceInfoUpdated(
                    created.guard,
                    com.healthyagent.running.core.model.DeviceInfo("sync", null, null),
                ),
                BleTransportEvent.GattConnected(created.guard),
            ),
            sink.controls,
        )
        assertEquals(listOf("discover"), platform.trace)
        coordinator.shutdown()
        runCurrent()
    }

    @Test
    fun `android connector null and throw each close callback intake and publish one failure without token`() = runTest {
        suspend fun runCase(throwFailure: Boolean): Triple<List<BleTransportEvent>, Long, KeepB5GattCallbackIngress> {
            lateinit var callback: KeepB5GattCallbackIngress
            val connector = androidConnector { _, captured ->
                callback = captured
                if (throwFailure) throw IllegalStateException("connect")
                null
            }
            val sink = RecordingSink()
            val tokens = AtomicLong()
            val coordinator = coordinator(connector, sink, tokens::incrementAndGet)
            assertTrue(coordinator.connect(KeepB5AttemptGuard(11L, if (throwFailure) 2L else 1L), FakeTarget("failure")))
            runCurrent()
            return Triple(sink.controls, tokens.get(), callback)
        }

        val nullCase = runCase(false)
        val throwCase = runCase(true)
        listOf(nullCase, throwCase).forEach { (events, tokens, callback) ->
            assertEquals(1, events.filterIsInstance<BleTransportEvent.ConnectFailed>().size)
            assertEquals(0L, tokens)
            assertFalse(callback.connectionStateChanged(status = 0, newState = 2))
        }
    }

    private fun CoroutineScope.coordinator(
        connector: KeepB5GattConnector,
        sink: RecordingSink,
        token: () -> Long = AtomicLong(100L)::incrementAndGet,
        logger: FakeDiagnosticLogger = FakeDiagnosticLogger(),
    ) = KeepB5GattBindingCoordinator(
        connector = connector,
        scope = this,
        connectDispatcher = StandardTestDispatcher((this as kotlinx.coroutines.test.TestScope).testScheduler),
        newGattToken = token,
        eventMapper = KeepB5GattEventMapper(),
        sink = sink,
        diagnosticLogger = logger,
    )

    private data class FakeTarget(val id: String) : KeepB5ConnectTarget

    private fun CoroutineScope.androidConnector(
        connect: (KeepB5ConnectTarget, KeepB5GattCallbackIngress) -> AndroidKeepB5GattPlatform?,
    ) = AndroidKeepB5GattConnector(
        monotonicMillis = { 123L },
        wallClockMillis = { 456L },
        processorScope = this,
        processorDispatcher = StandardTestDispatcher((this as kotlinx.coroutines.test.TestScope).testScheduler),
        diagnosticLogger = FakeDiagnosticLogger(),
        deviceName = { (it as FakeTarget).id },
        connectionFactory = AndroidKeepB5GattConnectionFactory(connect),
    )

    private class RecordingSessionListener(
        private val signals: MutableList<RawGattSignal>,
    ) : KeepB5GattSessionListener {
        override fun onSignal(session: KeepB5GattSession, signal: RawGattSignal) {
            signals += signal
        }
    }

    private class FakeAndroidGattPlatform : AndroidKeepB5GattPlatform {
        val trace = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        val writeTypes = mutableListOf<Int>()

        override fun discoverServices(): GattIoSubmission {
            trace += "discover"
            return GattIoSubmission.ACCEPTED
        }

        override fun enableNotifications(channel: KeepB5ChannelKind): GattIoSubmission {
            trace += "enable:$channel"
            return GattIoSubmission.ACCEPTED
        }

        override fun readBattery(): GattIoSubmission {
            trace += "readBattery"
            return GattIoSubmission.ACCEPTED
        }

        override fun readFirmware(): GattIoSubmission {
            trace += "readFirmware"
            return GattIoSubmission.ACCEPTED
        }

        override fun writePrivate(value: ByteArray, writeType: Int): GattIoSubmission {
            frames += value.copyOf()
            writeTypes += writeType
            return GattIoSubmission.ACCEPTED
        }

        override fun disconnect() {
            trace += "disconnect"
        }

        override fun close() {
            trace += "close"
        }
    }

    private class FakeAndroidGattMetadataReadPlatform(
        private val accepted: Boolean,
    ) : AndroidGattMetadataReadPlatform {
        var readCalls: Int = 0
            private set

        override fun readCharacteristic(): Boolean {
            readCalls++
            return accepted
        }
    }

    private fun privateServices() = GattServiceSnapshot(
        mapOf(
            uuid16("190a") to listOf(
                GattCharacteristicSnapshot(uuid16("0001"), 0x04),
                GattCharacteristicSnapshot(uuid16("0002"), 0x10),
            ),
        ),
    )

    private fun requestId(frame: ByteArray): Int =
        (frame[0].toInt() and 0xff) or ((frame[1].toInt() and 0xff) shl 8)

    private fun uuid16(value: String): UUID =
        UUID.fromString("0000$value-0000-1000-8000-00805f9b34fb")

    private class FakeGattSession(
        private val callbackJob: Job = CompletableDeferred(Unit),
        private val onShutdownCallbacks: () -> Unit = {},
        private val onDisconnect: () -> Unit = {},
        private val onClose: () -> Unit = {},
        private val onUpdate: (Long) -> Boolean = { true },
        private val onHeartbeat: () -> Boolean = { true },
    ) : KeepB5GattSession {
        val shutdownCalls = AtomicInteger()
        val disconnectCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val heartbeatCalls = AtomicInteger()
        val startArguments = CopyOnWriteArrayList<Long>()
        val updateArguments = CopyOnWriteArrayList<Long>()
        val updateMetrics = CopyOnWriteArrayList<KeepB5DisplayMetrics>()
        val trace = CopyOnWriteArrayList<String>()
        val closed = CountDownLatch(1)

        override fun sendStart(epochSeconds: Long): Boolean {
            startArguments += epochSeconds
            return true
        }

        override fun sendWorkoutUpdate(elapsedSeconds: Long, displayMetrics: KeepB5DisplayMetrics): Boolean {
            updateArguments += elapsedSeconds
            updateMetrics += displayMetrics
            return onUpdate(elapsedSeconds)
        }

        override fun sendHeartbeat(): Boolean {
            heartbeatCalls.incrementAndGet()
            return onHeartbeat()
        }

        override fun shutdownCallbacks(): Job {
            shutdownCalls.incrementAndGet()
            trace += "shutdown"
            onShutdownCallbacks()
            return callbackJob
        }

        override fun disconnect() {
            disconnectCalls.incrementAndGet()
            trace += "disconnect"
            onDisconnect()
        }

        override fun close() {
            closeCalls.incrementAndGet()
            trace += "close"
            try {
                onClose()
            } finally {
                closed.countDown()
            }
        }
    }

    private class RecordingSink(expectedCreated: Int = 0) : KeepB5GattBindingSink {
        private val lock = Any()
        private val created = CountDownLatch(expectedCreated)
        private val storedControls = mutableListOf<BleTransportEvent>()
        private val storedActivities = mutableListOf<PrivateWorkoutActivity>()
        private val storedSamples = mutableListOf<TokenizedSensorSample>()

        val controls: List<BleTransportEvent> get() = synchronized(lock) { storedControls.toList() }

        override fun control(event: BleTransportEvent): Boolean {
            synchronized(lock) { storedControls += event }
            if (event is BleTransportEvent.GattCreated) created.countDown()
            return true
        }

        override fun activity(value: PrivateWorkoutActivity): Boolean {
            synchronized(lock) { storedActivities += value }
            return true
        }

        override fun sample(value: TokenizedSensorSample): Boolean {
            synchronized(lock) { storedSamples += value }
            return true
        }

        fun awaitCreated(count: Int): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (controls.filterIsInstance<BleTransportEvent.GattCreated>().size < count) {
                if (System.nanoTime() >= deadline) return false
                Thread.yield()
            }
            return true
        }
    }

    private fun KeepB5GattGuard.toAttemptGuard() = KeepB5AttemptGuard(epoch, connectionAttemptToken)
}
