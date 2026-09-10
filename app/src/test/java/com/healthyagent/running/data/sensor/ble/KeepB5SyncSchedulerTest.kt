package com.healthyagent.running.data.sensor.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepB5SyncSchedulerTest {
    @Test
    fun emitsFirstUpdateAtFourPointFiveSecondsAndHeartbeatAtSix() {
        var now = 0L
        val scheduler = KeepB5SyncScheduler(monotonicMillis = { now })
        scheduler.start()
        assertEquals(4_500L, scheduler.nextDelayMillis())
        now = 4_500L
        assertEquals(listOf(KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 4L)), scheduler.pollDue())
        now = 5_500L
        assertEquals(listOf(KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 1L)), scheduler.pollDue())
        now = 6_000L
        assertEquals(listOf(KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT)), scheduler.pollDue())
    }

    @Test
    fun preservesUpdateRemainderAndReschedulesFromDelayedPollTime() {
        var now = 0L
        val scheduler = KeepB5SyncScheduler(
            monotonicMillis = { now },
            heartbeatInitialDelayMillis = 60_000L,
        )
        scheduler.start()

        now = 4_500L
        assertEquals(
            listOf(KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 4L)),
            scheduler.pollDue(),
        )
        now = 10_000L
        assertEquals(
            listOf(KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 6L)),
            scheduler.pollDue(),
        )
        assertEquals(1_000L, scheduler.nextDelayMillis())
    }

    @Test
    fun emitsUpdateBeforeHeartbeatWithoutBacklog() {
        var now = 0L
        val scheduler = KeepB5SyncScheduler(
            monotonicMillis = { now },
            updateInitialDelayMillis = 1_000L,
            heartbeatInitialDelayMillis = 1_000L,
        )
        scheduler.start()
        now = 7_000L
        assertEquals(
            listOf(
                KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 7L),
                KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT),
            ),
            scheduler.pollDue(),
        )
        assertTrue(scheduler.pollDue().isEmpty())
    }

    @Test
    fun keepsHeartbeatCadenceAtSixTwelveAndEighteenSeconds() {
        var now = 0L
        val scheduler = KeepB5SyncScheduler(
            monotonicMillis = { now },
            updateInitialDelayMillis = 60_000L,
        )
        scheduler.start()
        listOf(6_000L, 12_000L, 18_000L).forEach { deadline ->
            now = deadline
            assertEquals(
                listOf(KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT)),
                scheduler.pollDue(),
            )
        }
    }

    @Test
    fun resetDropsOverdueUpdateButKeepsHeartbeatDue() {
        var now = 0L
        val scheduler = KeepB5SyncScheduler(monotonicMillis = { now })
        scheduler.start()

        now = 60_000L
        scheduler.resetUpdateAccounting()
        assertEquals(
            listOf(KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT)),
            scheduler.pollDue(),
        )
        now = 60_999L
        assertTrue(scheduler.pollDue().isEmpty())
        now = 61_000L
        assertEquals(
            listOf(KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 1L)),
            scheduler.pollDue(),
        )
    }

    @Test
    fun stopClearsNextDelayAndDueActions() {
        var now = 0L
        var clockReads = 0
        val scheduler = KeepB5SyncScheduler(monotonicMillis = {
            clockReads++
            now
        })
        scheduler.start()
        val clockReadsAfterStart = clockReads
        scheduler.stop()
        scheduler.stop()
        scheduler.resetUpdateAccounting()

        now = 60_000L
        assertEquals(clockReadsAfterStart, clockReads)
        assertEquals(null, scheduler.nextDelayMillis())
        assertTrue(scheduler.pollDue().isEmpty())
    }
}
