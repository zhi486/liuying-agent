package com.healthyagent.running.data.sensor.ble

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepB5ConnectPermitTest {
    @Test
    fun `cancel winning before start prevents the block`() {
        val permit = KeepB5ConnectPermit()
        val calls = AtomicInteger()

        permit.cancel()
        permit.cancel()

        assertFalse(permit.tryStart { calls.incrementAndGet() })
        assertTrue(permit.isCancellationRequested())
        assertTrue(calls.get() == 0)
    }

    @Test(timeout = 5_000L)
    fun `start winning runs block once while cancel returns and remains requested`() {
        val permit = KeepB5ConnectPermit()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val started = executor.submit<Boolean> {
                permit.tryStart {
                    calls.incrementAndGet()
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            permit.cancel()
            permit.cancel()

            assertTrue(permit.isCancellationRequested())
            release.countDown()
            assertTrue(started.get(5, TimeUnit.SECONDS))
            assertTrue(calls.get() == 1)
            assertFalse(permit.tryStart { calls.incrementAndGet() })
            assertTrue(permit.isCancellationRequested())
            assertTrue(calls.get() == 1)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `ordinary exceptions and errors from the block propagate`() {
        val ordinary = IllegalStateException("ordinary")
        val fatal = AssertionError("fatal")

        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                KeepB5ConnectPermit().tryStart { throw ordinary }
            } === ordinary,
        )
        assertTrue(
            assertThrows(AssertionError::class.java) {
                KeepB5ConnectPermit().tryStart { throw fatal }
            } === fatal,
        )
    }

    @Test
    fun `cancelling one permit does not affect another instance`() {
        val cancelled = KeepB5ConnectPermit()
        val independent = KeepB5ConnectPermit()

        cancelled.cancel()

        assertFalse(cancelled.tryStart {})
        assertTrue(independent.tryStart {})
        assertFalse(independent.isCancellationRequested())
    }
}

class KeepB5GattRegistrationGateTest {
    @Test
    fun `activation releases pending waiter and permits execution`() = runBlocking {
        val gate = KeepB5GattRegistrationGate()
        val activation = async { gate.awaitActivation() }
        yield()

        assertTrue(gate.activate())

        assertTrue(activation.await())
        assertTrue(gate.isActive())
        assertTrue(gate.tryAcquireExecution())
        assertFalse(gate.activate())
    }

    @Test
    fun `close winning while pending rejects activation and execution`() = runBlocking {
        val gate = KeepB5GattRegistrationGate()

        assertTrue(gate.close())

        assertFalse(gate.awaitActivation())
        assertFalse(gate.activate())
        assertFalse(gate.tryAcquireExecution())
        assertFalse(gate.isActive())
        assertFalse(gate.close())
    }

    @Test
    fun `close after activation preserves activation result but prevents new execution`() = runBlocking {
        val gate = KeepB5GattRegistrationGate()
        assertTrue(gate.activate())

        assertTrue(gate.close())

        assertTrue(gate.awaitActivation())
        assertFalse(gate.isActive())
        assertFalse(gate.tryAcquireExecution())
        assertFalse(gate.activate())
    }
}
