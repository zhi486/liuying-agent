package com.healthyagent.running.core.engine.rules

import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** 高心率持续与冷却门控，使用可注入的单调时间。 */
class HrAlertGate(private val timeSource: TimeSource = TimeSource.Monotonic) {
    private var aboveSince: TimeMark? = null
    private var lastEvaluation: TimeMark? = null
    private var lastAlert: TimeMark? = null

    fun shouldAlert(
        heartRate: Int?,
        isFresh: Boolean,
        thresholdBpm: Int,
        repeatIntervalSec: Int,
    ): Boolean {
        if (lastEvaluation?.elapsedNow()?.let { it > 2.seconds } == true) aboveSince = null
        lastEvaluation = timeSource.markNow()
        if (!isFresh || heartRate == null || heartRate < thresholdBpm) {
            aboveSince = null
            return false
        }
        val start = aboveSince ?: timeSource.markNow().also { aboveSince = it }
        if (start.elapsedNow() < 10.seconds) return false
        if (lastAlert?.elapsedNow()?.let { it < repeatIntervalSec.seconds } == true) return false
        lastAlert = timeSource.markNow()
        return true
    }

    fun onPause() {
        aboveSince = null
        lastEvaluation = null
    }

    fun reset() {
        onPause()
        lastAlert = null
    }
}
