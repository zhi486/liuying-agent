package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.diagnostics.BleOperation
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.testing.FakeDiagnosticLogger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleTransportEventQueueTest {
    @Test
    fun `one hundred control events sent before collection remain FIFO`() = runTest {
        val fixture = fixture()
        val binding = fixture.connect("fifo", KeepB5AttemptGuard(1L, 1L))
        fixture.transport.activateGattData(binding.guard)

        repeat(100) { index ->
            binding.listener.onSignal(
                binding.session,
                RawGattSignal.DeviceInfoUpdated(DeviceInfo("device-$index", index, null)),
            )
        }

        val events = fixture.transport.controlEvents.take(102).toList()
        assertEquals(BleTransportEvent.ScanMatched(binding.guard.toAttempt()), events[0])
        assertEquals(BleTransportEvent.GattCreated(binding.guard), events[1])
        assertEquals(
            (0 until 100).map { "device-$it" },
            events.drop(2).map { (it as BleTransportEvent.DeviceInfoUpdated).deviceInfo.name },
        )
    }

    @Test
    fun `activity conflates by exact guard and max received time while samples stay FIFO`() = runTest {
        val fixture = fixture()
        val bindingA = fixture.connect("A", KeepB5AttemptGuard(2L, 1L))
        val bindingB = fixture.connect("B", KeepB5AttemptGuard(2L, 2L))
        fixture.transport.activateGattData(bindingA.guard)
        fixture.transport.activateGattData(bindingB.guard)

        bindingB.listener.onSignal(bindingB.session, privateSignal(receivedAtMillis = 10_000L))
        bindingB.listener.onSignal(bindingB.session, privateSignal(receivedAtMillis = 9_000L))
        bindingA.listener.onSignal(bindingA.session, privateSignal(receivedAtMillis = 11_000L))
        bindingB.listener.onSignal(bindingB.session, RawGattSignal.BatteryLevel(80, 20_000L, 200_000L))
        bindingA.listener.onSignal(bindingA.session, RawGattSignal.BatteryLevel(81, 19_000L, 190_000L))

        val activities = async { fixture.transport.privateActivities.take(2).toList() }
        val samples = fixture.transport.samples.take(2).toList()
        assertEquals(
            listOf(
                PrivateWorkoutActivity(bindingB.guard, 10_000L),
                PrivateWorkoutActivity(bindingA.guard, 11_000L),
            ),
            activities.await(),
        )
        assertEquals(listOf(bindingB.guard, bindingA.guard), samples.map { it.guard })
        assertEquals(listOf(20_000L, 19_000L), samples.map { it.receivedAtMillis })
        assertEquals(listOf(200_000L, 190_000L), samples.map { it.sample.timestampMillis })
    }

    @Test
    fun `write results are queued once before return and exceptions keep exact operation semantics`() = runTest {
        val logger = FakeDiagnosticLogger()
        val fatal = AssertionError("start")
        val fixture = fixture(
            logger = logger,
            sessionFactory = {
                FakeGattSession(
                    onStart = { throw fatal },
                    onUpdate = { throw IllegalStateException("update") },
                    onHeartbeat = { false },
                )
            },
        )
        val events = mutableListOf<BleTransportEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.transport.controlEvents.collect { event -> events += event }
        }
        val binding = fixture.connect("writes", KeepB5AttemptGuard(3L, 1L))
        fixture.transport.activateGattData(binding.guard)
        events.clear()

        assertSame(
            fatal,
            assertThrows(AssertionError::class.java) {
                fixture.transport.sendStart(binding.guard, 123L)
            },
        )
        assertFalse(fixture.transport.sendStart(binding.guard, 123L))
        val update = KeepB5SubmittedWrite(10L, binding.guard, 4L, KeepB5SyncKind.UPDATE_WORKOUT)
        val heartbeat = KeepB5SubmittedWrite(11L, binding.guard, 4L, KeepB5SyncKind.HEART_BEAT)
        assertFalse(fixture.transport.sendWorkoutUpdate(update, 45L))
        assertFalse(fixture.transport.sendHeartbeat(heartbeat))

        assertEquals(
            listOf(
                BleTransportEvent.StartWriteCompleted(binding.guard, false),
                BleTransportEvent.SyncWriteCompleted(update, false),
                BleTransportEvent.SyncWriteCompleted(heartbeat, false),
            ),
            events,
        )
        assertEquals(1, binding.session.startCalls.get())
        assertEquals(1, binding.session.updateCalls.get())
        assertEquals(1, binding.session.heartbeatCalls.get())
        assertEquals(
            listOf(BleOperation.START_WRITE, BleOperation.UPDATE_WRITE),
            logger.events.filterIsInstance<DiagnosticEvent.BleOperationFailed>().map { it.operation },
        )
    }

    @Test
    fun `shutdown waits binding cleanup drains all queues and rejects every late callback`() = runTest {
        val callbackGate = CompletableDeferred<Unit>()
        val fixture = fixture(sessionFactory = { FakeGattSession(callbackJob = callbackGate) })
        val controls = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            fixture.transport.controlEvents.toList()
        }
        val activities = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            fixture.transport.privateActivities.toList()
        }
        val samples = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            fixture.transport.samples.toList()
        }
        val binding = fixture.connect("shutdown", KeepB5AttemptGuard(4L, 1L))
        fixture.transport.activateGattData(binding.guard)
        binding.listener.onSignal(
            binding.session,
            RawGattSignal.DeviceInfoUpdated(DeviceInfo("before", 88, "1.0")),
        )
        binding.listener.onSignal(binding.session, privateSignal(receivedAtMillis = 30_000L))
        binding.listener.onSignal(binding.session, RawGattSignal.BatteryLevel(88, 31_000L, 310_000L))

        val shutdown = fixture.transport.shutdown()
        assertSame(shutdown, fixture.transport.shutdown())
        runCurrent()
        assertFalse(shutdown.isCompleted)
        assertFalse(controls.isCompleted)
        assertFalse(activities.isCompleted)
        assertFalse(samples.isCompleted)
        assertEquals(1, binding.session.shutdownCalls.get())

        binding.listener.onSignal(
            binding.session,
            RawGattSignal.DeviceInfoUpdated(DeviceInfo("late", 1, "late")),
        )
        binding.listener.onSignal(binding.session, privateSignal(receivedAtMillis = 99_000L))
        binding.listener.onSignal(binding.session, RawGattSignal.BatteryLevel(1, 99_000L, 990_000L))
        assertFalse(fixture.transport.startScan(KeepB5AttemptGuard(5L, 1L)))

        callbackGate.complete(Unit)
        runCurrent()
        assertTrue(shutdown.isCompleted)
        assertEquals(1, binding.session.disconnectCalls.get())
        assertEquals(1, binding.session.closeCalls.get())
        assertEquals(1, binding.session.shutdownCalls.get())
        assertEquals(
            listOf("before"),
            controls.await().filterIsInstance<BleTransportEvent.DeviceInfoUpdated>().map { it.deviceInfo.name },
        )
        assertEquals(listOf(30_000L), activities.await().map { it.receivedAtMillis })
        assertEquals(listOf(31_000L), samples.await().map { it.receivedAtMillis })
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        logger: FakeDiagnosticLogger = FakeDiagnosticLogger(),
        sessionFactory: (String) -> FakeGattSession = { FakeGattSession() },
    ): Fixture {
        val scanPort = FakeScanPort()
        val connector = RecordingConnector(sessionFactory)
        val transport = AndroidKeepB5BleTransport(
            scanPort = scanPort,
            gattConnector = connector,
            scope = backgroundScope,
            connectDispatcher = StandardTestDispatcher(testScheduler),
            newGattToken = AtomicLong(100L)::incrementAndGet,
            diagnosticLogger = logger,
        )
        return Fixture(transport, scanPort, connector, this)
    }

    private data class Fixture(
        val transport: AndroidKeepB5BleTransport,
        val scanPort: FakeScanPort,
        val connector: RecordingConnector,
        val testScope: kotlinx.coroutines.test.TestScope,
    ) {
        fun connect(id: String, attempt: KeepB5AttemptGuard): BoundSession {
            assertTrue(transport.startScan(attempt))
            scanPort.match(FakeTarget(id))
            transport.stopScan(attempt, preserveMatchedTarget = true)
            assertTrue(transport.connectRemembered(attempt))
            testScope.runCurrent()
            val bound = connector.bound.getValue(id)
            return BoundSession(
                KeepB5GattGuard(attempt.epoch, attempt.connectionAttemptToken, bound.token),
                bound.session,
                bound.listener,
            )
        }
    }

    private data class FakeTarget(val id: String) : KeepB5ConnectTarget

    private class FakeScanPort : KeepB5ScanPort {
        private var listener: KeepB5ScanPort.Listener? = null

        override fun availability(): ScanAvailability = ScanAvailability.READY

        override fun start(listener: KeepB5ScanPort.Listener): Boolean {
            this.listener = listener
            return true
        }

        override fun stop() = Unit

        fun match(target: KeepB5ConnectTarget) {
            requireNotNull(listener).onMatch(target)
        }
    }

    private class RecordingConnector(
        private val sessionFactory: (String) -> FakeGattSession,
    ) : KeepB5GattConnector {
        val bound = mutableMapOf<String, Bound>()
        private var nextToken = 101L

        override fun connect(
            target: KeepB5ConnectTarget,
            registrationGate: KeepB5GattRegistrationGate,
            listener: KeepB5GattSessionListener,
        ): KeepB5GattSession {
            val id = (target as FakeTarget).id
            val session = sessionFactory(id)
            bound[id] = Bound(nextToken++, session, listener)
            return session
        }
    }

    private data class Bound(
        val token: Long,
        val session: FakeGattSession,
        val listener: KeepB5GattSessionListener,
    )

    private data class BoundSession(
        val guard: KeepB5GattGuard,
        val session: FakeGattSession,
        val listener: KeepB5GattSessionListener,
    )

    private class FakeGattSession(
        private val callbackJob: Job = CompletableDeferred(Unit),
        private val onStart: (Long) -> Boolean = { true },
        private val onUpdate: (Long) -> Boolean = { true },
        private val onHeartbeat: () -> Boolean = { true },
    ) : KeepB5GattSession {
        val startCalls = AtomicInteger()
        val updateCalls = AtomicInteger()
        val heartbeatCalls = AtomicInteger()
        val shutdownCalls = AtomicInteger()
        val disconnectCalls = AtomicInteger()
        val closeCalls = AtomicInteger()

        override fun sendStart(epochSeconds: Long): Boolean {
            startCalls.incrementAndGet()
            return onStart(epochSeconds)
        }

        override fun sendWorkoutUpdate(elapsedSeconds: Long, displayMetrics: KeepB5DisplayMetrics): Boolean {
            updateCalls.incrementAndGet()
            return onUpdate(elapsedSeconds)
        }

        override fun sendHeartbeat(): Boolean {
            heartbeatCalls.incrementAndGet()
            return onHeartbeat()
        }

        override fun shutdownCallbacks(): Job {
            shutdownCalls.incrementAndGet()
            return callbackJob
        }

        override fun disconnect() {
            disconnectCalls.incrementAndGet()
        }

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private fun privateSignal(receivedAtMillis: Long) = RawGattSignal.PrivateNotification(
        frame = hex("e7 2a 02 00 00 0e 08 84 e6"),
        receivedAtMillis = receivedAtMillis,
        wallClockMillis = receivedAtMillis * 10L,
    )

    private fun hex(value: String): ByteArray =
        value.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun KeepB5GattGuard.toAttempt() = KeepB5AttemptGuard(epoch, connectionAttemptToken)
}
