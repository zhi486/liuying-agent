package com.healthyagent.running.data.sensor.ble

/** 无次数上限的指数退避；延迟达上限后以固定低频继续尝试。 */
internal class BleReconnectBackoff(
    private val initialDelayMillis: Long,
    private val maxDelayMillis: Long,
) : ReconnectBackoffPolicy {
    var attempt: Int = 0
        private set

    init {
        require(initialDelayMillis > 0L)
        require(maxDelayMillis >= initialDelayMillis)
    }

    override fun nextDelayMillis(): Long {
        val exponent = attempt.coerceAtMost(MAX_SAFE_EXPONENT)
        val multiplier = 1L shl exponent
        val delayMillis = if (initialDelayMillis > maxDelayMillis / multiplier) {
            maxDelayMillis
        } else {
            (initialDelayMillis * multiplier).coerceAtMost(maxDelayMillis)
        }
        if (attempt < Int.MAX_VALUE) attempt++
        return delayMillis
    }

    override fun reset() {
        attempt = 0
    }

    private companion object {
        /** 避免左移进入符号位；实际上达到此值前已会被 maxDelayMillis 限制。 */
        const val MAX_SAFE_EXPONENT = 62
    }
}
