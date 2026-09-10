package com.healthyagent.running.data.sensor.ble

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedConflatedEventQueueTest {
    @Test
    fun `same key keeps merge result instead of the last arrival`() = runBlocking {
        val queue = KeyedConflatedEventQueue<String, TimedValue> { pending, incoming ->
            if (incoming.receivedAtMillis > pending.receivedAtMillis) incoming else pending
        }

        assertTrue(queue.offer("A", TimedValue("newer", 10_000L)))
        assertTrue(queue.offer("A", TimedValue("older", 9_000L)))
        queue.close()

        assertEquals(
            listOf(TimedValue("newer", 10_000L)),
            withTimeout(1_000L) { queue.values.toList() },
        )
    }

    @Test
    fun `updating a pending key preserves its original fifo position`() = runBlocking {
        val queue = KeyedConflatedEventQueue<String, String>()

        queue.offer("A", "A1")
        queue.offer("B", "B1")
        queue.offer("A", "A2")
        queue.close()

        assertEquals(listOf("A2", "B1"), queue.values.toList())
    }

    @Test
    fun `close drains pending values completes flow and rejects later offers`() = runBlocking {
        val queue = KeyedConflatedEventQueue<String, Int>()
        queue.offer("A", 1)
        queue.offer("B", 2)

        queue.close()
        queue.close()

        assertFalse(queue.offer("C", 3))
        assertEquals(listOf(1, 2), withTimeout(1_000L) { queue.values.toList() })
    }

    @Test(timeout = 5_000L)
    fun `concurrent offers for different keys all remain reachable`() = runBlocking {
        val queue = KeyedConflatedEventQueue<Int, Int>()
        val workerCount = 4
        val valuesPerWorker = 50
        val ready = CountDownLatch(workerCount)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            val futures = List(workerCount) { worker ->
                executor.submit {
                    ready.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    repeat(valuesPerWorker) { offset ->
                        val value = worker * valuesPerWorker + offset
                        assertTrue(queue.offer(value, value))
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            release.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            queue.close()

            assertEquals(
                (0 until workerCount * valuesPerWorker).toSet(),
                withTimeout(1_000L) { queue.values.toList() }.toSet(),
            )
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `only one collector may consume the queue`() {
        val queue = KeyedConflatedEventQueue<String, Int>()
        queue.close()
        runBlocking { queue.values.toList() }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { queue.values.toList() }
        }
    }

    private data class TimedValue(
        val value: String,
        val receivedAtMillis: Long,
    )
}
