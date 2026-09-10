package com.healthyagent.running.data.sensor.ble

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeepB5GattCallbackProcessorTest {
    @Test
    fun `pending raw FIFO performs no work until activation then initializes once before complete batches`() = runTest {
        val trace = mutableListOf<String>()
        val fixture = fixture(trace)
        assertTrue(fixture.processor.offer(RawGattCallbackEvent.ConnectionStateChanged(0, 2)))
        assertTrue(fixture.processor.offer(services()))
        runCurrent()
        assertTrue(trace.isEmpty())

        fixture.gate.activate()
        runCurrent()

        assertEquals(
            listOf(
                "signal:DeviceInfoUpdated",
                "signal:Connected",
                "io:discover",
                "signal:ServicesDiscovered",
                "io:enable:STANDARD_HR",
            ),
            trace,
        )
        assertSame(fixture.processor.shutdown(), fixture.processor.shutdown())
        runCurrent()
    }

    @Test
    fun `shutdown while pending closes intake drops FIFO and activation cannot revive it`() = runTest {
        val trace = mutableListOf<String>()
        val fixture = fixture(trace)
        fixture.processor.offer(RawGattCallbackEvent.ConnectionStateChanged(0, 2))

        val job = fixture.processor.shutdown()
        assertFalse(fixture.processor.offer(RawGattCallbackEvent.ConnectionStateChanged(0, 2)))
        assertFalse(fixture.gate.activate())
        runCurrent()

        assertTrue(job.isCompleted)
        assertTrue(trace.isEmpty())
    }

    @Test
    fun `activation followed by shutdown before actor scheduling still performs zero work`() = runTest {
        val trace = mutableListOf<String>()
        val fixture = fixture(trace)
        fixture.processor.offer(RawGattCallbackEvent.ConnectionStateChanged(0, 2))
        assertTrue(fixture.gate.activate())

        fixture.processor.shutdown()
        runCurrent()

        assertTrue(trace.isEmpty())
    }

    @Test
    fun `closing gate inside permitted IO suppresses fallback sink and all later callbacks`() = runTest {
        val trace = mutableListOf<String>()
        lateinit var fixture: Fixture
        fixture = fixture(trace, discover = {
            fixture.gate.close()
            GattIoSubmission.REJECTED
        })
        fixture.processor.offer(RawGattCallbackEvent.ConnectionStateChanged(0, 2))
        fixture.processor.offer(services())
        fixture.gate.activate()

        runCurrent()

        assertEquals(
            listOf("signal:DeviceInfoUpdated", "signal:Connected", "io:discover"),
            trace,
        )
        fixture.processor.shutdown().join()
        assertFalse(fixture.processor.offer(services()))
    }

    @Test
    fun `two processors keep notification and metadata state isolated`() = runTest {
        val traceA = mutableListOf<String>()
        val traceB = mutableListOf<String>()
        val a = fixture(traceA)
        val b = fixture(traceB)
        a.gate.activate()
        b.gate.activate()
        a.processor.offer(services())
        b.processor.offer(services())
        a.processor.offer(RawGattCallbackEvent.DescriptorWrite(0, HR_SERVICE, CCCD, HR_MEASUREMENT))
        b.processor.offer(RawGattCallbackEvent.DescriptorWrite(0, HR_SERVICE, CCCD, HR_MEASUREMENT))

        runCurrent()

        assertEquals(traceA, traceB)
        assertEquals(1, traceA.count { it == "io:enable:STANDARD_HR" })
        assertEquals(1, traceA.count { it == "io:battery" })
        a.processor.shutdown()
        b.processor.shutdown()
        runCurrent()
    }

    @Test(timeout = 5_000L)
    fun `concurrent offers are serialized as complete non-interleaved callback batches`() = runTest {
        val trace = mutableListOf<String>()
        val fixture = fixture(trace)
        fixture.gate.activate()
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val connection = pool.submit<Boolean> {
                ready.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                fixture.processor.offer(RawGattCallbackEvent.ConnectionStateChanged(0, 2))
            }
            val discovery = pool.submit<Boolean> {
                ready.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                fixture.processor.offer(services())
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(connection.get(5, TimeUnit.SECONDS))
            assertTrue(discovery.get(5, TimeUnit.SECONDS))
            runCurrent()

            val callbackTrace = trace.drop(1)
            assertTrue(
                callbackTrace == listOf(
                    "signal:Connected",
                    "io:discover",
                    "signal:ServicesDiscovered",
                    "io:enable:STANDARD_HR",
                ) || callbackTrace == listOf(
                    "signal:ServicesDiscovered",
                    "io:enable:STANDARD_HR",
                    "signal:Connected",
                    "io:discover",
                ),
            )
        } finally {
            release.countDown()
            pool.shutdownNow()
            fixture.processor.shutdown()
            runCurrent()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        trace: MutableList<String>,
        discover: () -> GattIoSubmission = { GattIoSubmission.ACCEPTED },
    ): Fixture {
        val gate = KeepB5GattRegistrationGate()
        val io = object : KeepB5GattIoPort {
            override fun discoverServices(): GattIoSubmission {
                trace += "io:discover"
                return discover()
            }

            override fun enableNotifications(channel: KeepB5ChannelKind): GattIoSubmission {
                trace += "io:enable:$channel"
                return GattIoSubmission.ACCEPTED
            }

            override fun readBattery(): GattIoSubmission {
                trace += "io:battery"
                return GattIoSubmission.ACCEPTED
            }

            override fun readFirmware(): GattIoSubmission {
                trace += "io:firmware"
                return GattIoSubmission.ACCEPTED
            }
        }
        val executor = KeepB5GattDecisionExecutor(
            io = io,
            deviceName = "Keep B5",
            signalSink = { trace += "signal:${it::class.simpleName}" },
            diagnosticLogger = com.healthyagent.running.diagnostics.NoOpDiagnosticLogger,
            registrationGate = gate,
        )
        return Fixture(
            gate,
            KeepB5GattCallbackProcessor(
                mapper = KeepB5GattCallbackMapper(),
                executor = executor,
                registrationGate = gate,
                scope = this,
                dispatcher = StandardTestDispatcher(testScheduler),
            ),
        )
    }

    private fun services() = RawGattCallbackEvent.ServicesDiscovered(
        status = 0,
        services = GattServiceSnapshot(
            mapOf(
                HR_SERVICE to listOf(GattCharacteristicSnapshot(HR_MEASUREMENT, 0x10)),
                PRIVATE_SERVICE to listOf(
                    GattCharacteristicSnapshot(PRIVATE_COMMAND, 0x04),
                    GattCharacteristicSnapshot(PRIVATE_NOTIFY, 0x10),
                ),
            ),
        ),
    )

    private data class Fixture(
        val gate: KeepB5GattRegistrationGate,
        val processor: KeepB5GattCallbackProcessor,
    )

    private companion object {
        val HR_SERVICE: UUID = uuid16("180d")
        val HR_MEASUREMENT: UUID = uuid16("2a37")
        val CCCD: UUID = uuid16("2902")
        val PRIVATE_SERVICE: UUID = uuid16("190a")
        val PRIVATE_COMMAND: UUID = uuid16("0001")
        val PRIVATE_NOTIFY: UUID = uuid16("0002")
        fun uuid16(value: String): UUID = UUID.fromString("0000$value-0000-1000-8000-00805f9b34fb")
    }
}
