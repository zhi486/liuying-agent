package com.healthyagent.running.data.sensor.ble

/** Keep B5 私有训练报文使用的、与墙钟无关的训练时长游标。 */
internal class KeepB5WorkoutSessionState {
    private val lock = Any()
    private var isInitialized = false
    private var elapsedSeconds = 0L

    var isActive: Boolean = false
        private set
        get() = synchronized(lock) { field }

    fun start(initialElapsedSeconds: Long) = synchronized(lock) {
        elapsedSeconds = initialElapsedSeconds.coerceAtLeast(0L)
        isInitialized = true
        isActive = true
    }

    fun pause(currentElapsedSeconds: Long) = synchronized(lock) {
        if (!isInitialized) {
            elapsedSeconds = currentElapsedSeconds.coerceAtLeast(0L)
            isInitialized = true
        } else {
            elapsedSeconds = maxOf(elapsedSeconds, currentElapsedSeconds.coerceAtLeast(0L))
        }
        isActive = false
    }

    fun resume(currentElapsedSeconds: Long) = synchronized(lock) {
        if (!isInitialized) {
            elapsedSeconds = currentElapsedSeconds.coerceAtLeast(0L)
            isInitialized = true
        }
        isActive = true
    }

    fun stop() = synchronized(lock) {
        elapsedSeconds = 0L
        isInitialized = false
        isActive = false
    }

    fun advanceUpdate(skippedIntervals: Long = 0L): Long? = synchronized(lock) {
        if (!isActive) return@synchronized null
        elapsedSeconds += 1L + skippedIntervals.coerceAtLeast(0L)
        elapsedSeconds
    }
}
