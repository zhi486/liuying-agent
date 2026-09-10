package com.healthyagent.running.data.sensor.ble

internal enum class KeepB5SyncKind {
    UPDATE_WORKOUT,
    HEART_BEAT,
}

internal data class KeepB5SyncDue(
    val kind: KeepB5SyncKind,
    val elapsedIntervals: Long = 0L,
)

internal class KeepB5SyncScheduler(
    private val monotonicMillis: () -> Long,
    private val updateInitialDelayMillis: Long = 4_500L,
    private val updateIntervalMillis: Long = 1_000L,
    private val heartbeatInitialDelayMillis: Long = 6_000L,
    private val heartbeatIntervalMillis: Long = 6_000L,
) {
    private var started = false
    private var lastAccountedUpdateAt = 0L
    private var nextUpdateAt = 0L
    private var nextHeartbeatAt = 0L

    @Synchronized
    fun start() {
        if (started) return

        val now = monotonicMillis()
        started = true
        lastAccountedUpdateAt = now
        nextUpdateAt = now + updateInitialDelayMillis
        nextHeartbeatAt = now + heartbeatInitialDelayMillis
    }

    @Synchronized
    fun resetUpdateAccounting() {
        if (!started) return

        val now = monotonicMillis()
        lastAccountedUpdateAt = now
        nextUpdateAt = now + updateIntervalMillis
    }

    @Synchronized
    fun stop() {
        started = false
    }

    @Synchronized
    fun nextDelayMillis(): Long? {
        if (!started) return null

        val now = monotonicMillis()
        return minOf(nextUpdateAt, nextHeartbeatAt).minus(now).coerceAtLeast(0L)
    }

    @Synchronized
    fun pollDue(): List<KeepB5SyncDue> {
        if (!started) return emptyList()

        val now = monotonicMillis()
        val due = buildList(2) {
            if (now >= nextUpdateAt) {
                val elapsedIntervals = maxOf(
                    1L,
                    (now - lastAccountedUpdateAt) / updateIntervalMillis,
                )
                add(KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, elapsedIntervals))
                lastAccountedUpdateAt += elapsedIntervals * updateIntervalMillis
                nextUpdateAt = now + updateIntervalMillis
            }
            if (now >= nextHeartbeatAt) {
                add(KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT))
                nextHeartbeatAt = now + heartbeatIntervalMillis
            }
        }
        return due
    }
}
