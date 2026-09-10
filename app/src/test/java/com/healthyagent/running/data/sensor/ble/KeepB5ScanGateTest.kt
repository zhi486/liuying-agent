package com.healthyagent.running.data.sensor.ble

import android.bluetooth.le.ScanCallback
import com.healthyagent.running.diagnostics.BleFailureReason
import com.healthyagent.running.diagnostics.BleOperation
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.testing.FakeDiagnosticLogger
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeepB5ScanGateTest {
    @Test(timeout = 10_000L)
    fun `stop before transport port start prevents the scan from starting later`() = runTest {
        val availabilityEntered = CountDownLatch(1)
        val releaseAvailability = CountDownLatch(1)
        val scanPort = FakeScanPort(
            availabilityEntered = availabilityEntered,
            releaseAvailability = releaseAvailability,
        )
        val transport = transport(scanPort, RecordingConnector())
        val guard = KeepB5AttemptGuard(0L, 1L)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val start = executor.submit<Boolean> { transport.startScan(guard) }
            assertTrue(availabilityEntered.await(5, TimeUnit.SECONDS))

            transport.stopScan(guard, preserveMatchedTarget = false)
            releaseAvailability.countDown()

            assertFalse(start.get(5, TimeUnit.SECONDS))
            assertEquals(0, scanPort.startCalls)
            assertEquals(0, scanPort.stopCalls)
        } finally {
            releaseAvailability.countDown()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 10_000L)
    fun `shutdown before transport port start prevents the scan from starting later`() = runTest {
        val availabilityEntered = CountDownLatch(1)
        val releaseAvailability = CountDownLatch(1)
        val scanPort = FakeScanPort(
            availabilityEntered = availabilityEntered,
            releaseAvailability = releaseAvailability,
        )
        val transport = transport(scanPort, RecordingConnector())
        val guard = KeepB5AttemptGuard(0L, 2L)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val start = executor.submit<Boolean> { transport.startScan(guard) }
            assertTrue(availabilityEntered.await(5, TimeUnit.SECONDS))

            val shutdown = transport.shutdown()
            releaseAvailability.countDown()

            assertFalse(start.get(5, TimeUnit.SECONDS))
            runCurrent()
            assertTrue(shutdown.isCompleted)
            assertEquals(0, scanPort.startCalls)
            assertEquals(0, scanPort.stopCalls)
        } finally {
            releaseAvailability.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `first match wins once preserves its target and never connects from callback`() = runTest {
        val scanPort = FakeScanPort()
        val connector = RecordingConnector()
        val transport = transport(scanPort, connector)
        val events = mutableListOf<BleTransportEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.controlEvents.collect { events += it }
        }
        val guard = KeepB5AttemptGuard(1L, 2L)
        val first = FakeTarget("first")

        assertTrue(transport.startScan(guard))
        scanPort.match(0, first)
        scanPort.match(0, FakeTarget("second"))
        scanPort.fail(0)

        assertEquals(listOf(BleTransportEvent.ScanMatched(guard)), events)
        assertEquals(emptyList<KeepB5ConnectTarget>(), connector.targets)

        transport.stopScan(guard, preserveMatchedTarget = true)
        assertEquals(1, scanPort.stopCalls)
        assertTrue(transport.connectRemembered(guard))
        runCurrent()
        assertEquals(listOf(first), connector.targets)

        assertFalse(transport.connectRemembered(guard))
        assertEquals(
            1,
            events.count { it == BleTransportEvent.ConnectFailed(guard, BleFailureReason.GATT_ERROR) },
        )
    }

    @Test
    fun `failure wins terminal race so late match is not remembered`() = runTest {
        val scanPort = FakeScanPort()
        val connector = RecordingConnector()
        val transport = transport(scanPort, connector)
        val events = mutableListOf<BleTransportEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.controlEvents.collect { events += it }
        }
        val guard = KeepB5AttemptGuard(2L, 3L)

        assertTrue(transport.startScan(guard))
        scanPort.fail(0)
        scanPort.match(0, FakeTarget("late"))
        transport.stopScan(guard, preserveMatchedTarget = false)
        assertFalse(transport.connectRemembered(guard))
        runCurrent()

        assertEquals(
            listOf(
                BleTransportEvent.ScanFailed(guard, BleFailureReason.SCAN_FAILED),
                BleTransportEvent.ConnectFailed(guard, BleFailureReason.GATT_ERROR),
            ),
            events,
        )
        assertTrue(connector.targets.isEmpty())
    }

    @Test
    fun `late stop for A cannot stop or terminate replacement scan B`() = runTest {
        val scanPort = FakeScanPort()
        val transport = transport(scanPort, RecordingConnector())
        val events = mutableListOf<BleTransportEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.controlEvents.collect { events += it }
        }
        val guardA = KeepB5AttemptGuard(3L, 1L)
        val guardB = KeepB5AttemptGuard(3L, 2L)

        assertTrue(transport.startScan(guardA))
        assertTrue(transport.startScan(guardB))
        assertEquals(1, scanPort.stopCalls)

        transport.stopScan(guardA, preserveMatchedTarget = false)
        assertEquals(1, scanPort.stopCalls)
        scanPort.match(0, FakeTarget("late-a"))
        scanPort.match(1, FakeTarget("current-b"))

        assertEquals(listOf(BleTransportEvent.ScanMatched(guardB)), events)
        transport.stopScan(guardB, preserveMatchedTarget = false)
        assertEquals(2, scanPort.stopCalls)
    }

    @Test
    fun `availability false throw and asynchronous failure each publish one normalized terminal`() = runTest {
        suspend fun runCase(port: FakeScanPort): Pair<Boolean, List<BleTransportEvent>> {
            val transport = transport(port, RecordingConnector())
            val events = mutableListOf<BleTransportEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                transport.controlEvents.collect { events += it }
            }
            val accepted = transport.startScan(KeepB5AttemptGuard(4L, port.caseToken))
            return accepted to events
        }

        val permission = runCase(
            FakeScanPort(
                availability = ScanAvailability.PERMISSION_MISSING,
                caseToken = 1L,
            ),
        )
        assertFalse(permission.first)
        assertEquals(BleFailureReason.PERMISSION_MISSING, (permission.second.single() as BleTransportEvent.ScanFailed).reason)

        val disabled = runCase(
            FakeScanPort(
                availability = ScanAvailability.ADAPTER_DISABLED,
                caseToken = 2L,
            ),
        )
        assertFalse(disabled.first)
        assertEquals(BleFailureReason.ADAPTER_DISABLED, (disabled.second.single() as BleTransportEvent.ScanFailed).reason)

        val rejected = runCase(FakeScanPort(startResult = false, caseToken = 3L))
        assertFalse(rejected.first)
        assertEquals(BleFailureReason.SCAN_FAILED, (rejected.second.single() as BleTransportEvent.ScanFailed).reason)

        val logger = FakeDiagnosticLogger()
        val thrownPort = FakeScanPort(startError = IllegalStateException("scanner"), caseToken = 4L)
        val thrownTransport = transport(thrownPort, RecordingConnector(), logger)
        val thrownEvents = mutableListOf<BleTransportEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            thrownTransport.controlEvents.collect { thrownEvents += it }
        }
        assertFalse(thrownTransport.startScan(KeepB5AttemptGuard(4L, 4L)))
        assertEquals(BleFailureReason.SCAN_FAILED, (thrownEvents.single() as BleTransportEvent.ScanFailed).reason)
        assertEquals(
            listOf(BleOperation.START_SCAN),
            logger.events.filterIsInstance<DiagnosticEvent.BleOperationFailed>().map { it.operation },
        )

        val asyncPort = FakeScanPort(caseToken = 5L)
        val async = runCase(asyncPort)
        assertTrue(async.first)
        asyncPort.fail(0)
        asyncPort.fail(0)
        assertEquals(1, async.second.size)
    }

    @Test
    fun `stop exception is diagnosed while a later replacement scan still starts`() = runTest {
        val logger = FakeDiagnosticLogger()
        val scanPort = FakeScanPort(stopError = IllegalStateException("stop"))
        val transport = transport(scanPort, RecordingConnector(), logger)
        val guardA = KeepB5AttemptGuard(5L, 1L)
        val guardB = KeepB5AttemptGuard(5L, 2L)

        assertTrue(transport.startScan(guardA))
        transport.stopScan(guardA, preserveMatchedTarget = false)
        assertTrue(transport.startScan(guardB))

        assertEquals(2, scanPort.startCalls)
        assertEquals(
            listOf(BleOperation.STOP_SCAN),
            logger.events.filterIsInstance<DiagnosticEvent.BleOperationFailed>().map { it.operation },
        )
    }

    @Test
    fun `shutdown stops active scan drops remembered target and returns one job`() = runTest {
        val scanPort = FakeScanPort()
        val connector = RecordingConnector()
        val transport = transport(scanPort, connector)
        val events = mutableListOf<BleTransportEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.controlEvents.collect { event -> events += event }
        }
        val guard = KeepB5AttemptGuard(6L, 1L)

        assertTrue(transport.startScan(guard))
        scanPort.match(0, FakeTarget("remembered"))
        val shutdown = transport.shutdown()
        assertSame(shutdown, transport.shutdown())
        runCurrent()

        assertTrue(shutdown.isCompleted)
        assertEquals(1, scanPort.stopCalls)
        assertFalse(transport.connectRemembered(guard))
        scanPort.match(0, FakeTarget("late"))
        runCurrent()
        assertTrue(connector.targets.isEmpty())
        assertEquals(listOf(BleTransportEvent.ScanMatched(guard)), events)
    }

    @Test
    fun `shutdown cancellation from scan stop still joins coordinator closes flows and completes once`() = runTest {
        assertShutdownStopFailureIsTerminal(CancellationException("stop cancelled"))
    }

    @Test
    fun `shutdown error from scan stop still joins coordinator closes flows and completes once`() = runTest {
        assertShutdownStopFailureIsTerminal(AssertionError("stop failed"))
    }

    @Test
    fun `Android scan adapter treats denied safe device name as no match`() {
        val port = AndroidKeepB5ScanPort(scanner = null)
        var matches = 0

        val matched = port.dispatchScanResult(
            advertisedName = null,
            safeDeviceName = { throw SecurityException("denied") },
            target = FakeTarget("device"),
            listener = object : KeepB5ScanPort.Listener {
                override fun onMatch(target: KeepB5ConnectTarget) {
                    matches++
                }

                override fun onFailure(reason: BleFailureReason) = Unit
            },
        )

        assertFalse(matched)
        assertEquals(0, matches)
    }

    @Test(timeout = 10_000L)
    fun `adapter stop before platform start prevents late start and rejects callback`() {
        val callbackRegistered = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val callback = AtomicReference<ScanCallback>()
        val platformStarts = AtomicInteger()
        val platformStops = AtomicInteger()
        val failures = AtomicInteger()
        val port = AndroidKeepB5ScanPort(
            availabilityProvider = { ScanAvailability.READY },
            onCallbackRegistered = { registered ->
                callback.set(registered)
                callbackRegistered.countDown()
                check(releaseRegistration.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release callback registration"
                }
            },
            platformStart = {
                platformStarts.incrementAndGet()
                true
            },
            platformStop = { platformStops.incrementAndGet() },
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val start = executor.submit<Boolean> {
                port.start(failureCountingListener(failures))
            }
            assertTrue(callbackRegistered.await(5, TimeUnit.SECONDS))

            port.stop()
            releaseRegistration.countDown()

            assertFalse(start.get(5, TimeUnit.SECONDS))
            assertEquals(0, platformStarts.get())
            assertEquals(0, platformStops.get())
            callback.get().onScanFailed(1)
            assertEquals(0, failures.get())
        } finally {
            releaseRegistration.countDown()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 10_000L)
    fun `adapter stop after platform start stops once in order and rejects late callback`() {
        val platformStartEntered = CountDownLatch(1)
        val releasePlatformStart = CountDownLatch(1)
        val stopAttempted = CountDownLatch(1)
        val callback = AtomicReference<ScanCallback>()
        val operations = ConcurrentLinkedQueue<String>()
        val failures = AtomicInteger()
        val port = AndroidKeepB5ScanPort(
            availabilityProvider = { ScanAvailability.READY },
            onCallbackRegistered = { registered -> callback.set(registered) },
            platformStart = {
                platformStartEntered.countDown()
                check(releasePlatformStart.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release platform start"
                }
                operations += "start"
                true
            },
            platformStop = { operations += "stop" },
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val start = executor.submit<Boolean> {
                port.start(failureCountingListener(failures))
            }
            assertTrue(platformStartEntered.await(5, TimeUnit.SECONDS))
            val stop = executor.submit {
                stopAttempted.countDown()
                port.stop()
            }
            assertTrue(stopAttempted.await(5, TimeUnit.SECONDS))

            releasePlatformStart.countDown()

            assertTrue(start.get(5, TimeUnit.SECONDS))
            stop.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("start", "stop"), operations.toList())
            callback.get().onScanFailed(1)
            assertEquals(0, failures.get())
        } finally {
            releasePlatformStart.countDown()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 10_000L)
    fun `adapter synchronous callback can stop reentrantly without deadlock`() {
        val callback = AtomicReference<ScanCallback>()
        val platformStarts = AtomicInteger()
        val platformStops = AtomicInteger()
        val failures = AtomicInteger()
        lateinit var port: AndroidKeepB5ScanPort
        port = AndroidKeepB5ScanPort(
            availabilityProvider = { ScanAvailability.READY },
            onCallbackRegistered = { registered -> callback.set(registered) },
            platformStart = { registered ->
                platformStarts.incrementAndGet()
                registered.onScanFailed(1)
                true
            },
            platformStop = { platformStops.incrementAndGet() },
        )

        val accepted = port.start(object : KeepB5ScanPort.Listener {
            override fun onMatch(target: KeepB5ConnectTarget) = Unit

            override fun onFailure(reason: BleFailureReason) {
                failures.incrementAndGet()
                port.stop()
            }
        })

        assertFalse(accepted)
        assertEquals(1, platformStarts.get())
        assertEquals(1, platformStops.get())
        assertEquals(1, failures.get())
        callback.get().onScanFailed(1)
        assertEquals(1, failures.get())
    }

    private fun kotlinx.coroutines.test.TestScope.transport(
        scanPort: KeepB5ScanPort,
        connector: KeepB5GattConnector,
        logger: FakeDiagnosticLogger = FakeDiagnosticLogger(),
    ) = AndroidKeepB5BleTransport(
        scanPort = scanPort,
        gattConnector = connector,
        scope = backgroundScope,
        connectDispatcher = StandardTestDispatcher(testScheduler),
        newGattToken = AtomicLong(10L)::incrementAndGet,
        diagnosticLogger = logger,
    )

    private fun kotlinx.coroutines.test.TestScope.assertShutdownStopFailureIsTerminal(
        stopFailure: Throwable,
    ) {
        val callbackShutdown = CompletableDeferred<Unit>()
        val connector = RecordingConnector(callbackShutdown)
        val logger = FakeDiagnosticLogger()
        val scanPort = FakeScanPort(stopError = stopFailure)
        val transport = transport(scanPort, connector, logger)
        val collectors = listOf(
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                transport.controlEvents.collect()
            },
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                transport.privateActivities.collect()
            },
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                transport.samples.collect()
            },
        )
        val guard = KeepB5AttemptGuard(7L, 1L)

        assertTrue(transport.startScan(guard))
        scanPort.match(0, FakeTarget("remembered"))
        assertTrue(transport.connectRemembered(guard))
        runCurrent()

        val firstAttempt = runCatching { transport.shutdown() }
        val shutdown = firstAttempt.getOrElse { transport.shutdown() }
        assertSame(shutdown, transport.shutdown())
        assertEquals(1, scanPort.stopCalls)

        val completionCalls = AtomicInteger()
        val completionCause = AtomicReference<Throwable?>()
        shutdown.invokeOnCompletion { cause ->
            completionCause.set(cause)
            completionCalls.incrementAndGet()
        }
        runCurrent()

        assertFalse(shutdown.isCompleted)
        collectors.forEach { collector -> assertFalse(collector.isCompleted) }

        callbackShutdown.complete(Unit)
        runCurrent()

        assertTrue(shutdown.isCompleted)
        collectors.forEach { collector -> assertTrue(collector.isCompleted) }
        assertEquals(1, completionCalls.get())
        val synchronousFailure = firstAttempt.exceptionOrNull()
        if (synchronousFailure == null) {
            assertSame(stopFailure, completionCause.get())
        } else {
            assertSame(stopFailure, synchronousFailure)
        }
        assertEquals(
            listOf(BleOperation.STOP_SCAN),
            logger.events.filterIsInstance<DiagnosticEvent.BleOperationFailed>().map { it.operation },
        )
    }

    private data class FakeTarget(val id: String) : KeepB5ConnectTarget

    private fun failureCountingListener(failures: AtomicInteger) = object : KeepB5ScanPort.Listener {
        override fun onMatch(target: KeepB5ConnectTarget) = Unit

        override fun onFailure(reason: BleFailureReason) {
            failures.incrementAndGet()
        }
    }

    private class FakeScanPort(
        var availability: ScanAvailability = ScanAvailability.READY,
        var startResult: Boolean = true,
        var startError: Throwable? = null,
        var stopError: Throwable? = null,
        val caseToken: Long = 0L,
        private val availabilityEntered: CountDownLatch? = null,
        private val releaseAvailability: CountDownLatch? = null,
    ) : KeepB5ScanPort {
        val listeners = mutableListOf<KeepB5ScanPort.Listener>()
        var startCalls = 0
        var stopCalls = 0

        override fun availability(): ScanAvailability {
            availabilityEntered?.countDown()
            check(releaseAvailability?.await(5, TimeUnit.SECONDS) != false) {
                "Timed out waiting to release scan availability"
            }
            return availability
        }

        override fun start(listener: KeepB5ScanPort.Listener): Boolean {
            startCalls++
            startError?.let { throw it }
            listeners += listener
            return startResult
        }

        override fun stop() {
            stopCalls++
            stopError?.let { throw it }
        }

        fun match(index: Int, target: KeepB5ConnectTarget) = listeners[index].onMatch(target)

        fun fail(index: Int) = listeners[index].onFailure(BleFailureReason.SCAN_FAILED)
    }

    private class RecordingConnector(
        private val callbackShutdown: Job = CompletableDeferred(Unit),
    ) : KeepB5GattConnector {
        val targets = mutableListOf<KeepB5ConnectTarget>()
        private val session = object : KeepB5GattSession {
            override fun sendStart(epochSeconds: Long): Boolean = true
            override fun sendWorkoutUpdate(elapsedSeconds: Long, displayMetrics: KeepB5DisplayMetrics): Boolean = true
            override fun sendHeartbeat(): Boolean = true
            override fun shutdownCallbacks(): Job = callbackShutdown
            override fun disconnect() = Unit
            override fun close() = Unit
        }

        override fun connect(
            target: KeepB5ConnectTarget,
            registrationGate: KeepB5GattRegistrationGate,
            listener: KeepB5GattSessionListener,
        ): KeepB5GattSession {
            targets += target
            return session
        }
    }
}
