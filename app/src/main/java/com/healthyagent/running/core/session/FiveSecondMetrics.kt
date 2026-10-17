package com.healthyagent.running.core.session

import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RunMetricSample

/** 只消费每个活动秒的第一帧，不改动安全规则使用的平滑指标。 */
class FiveSecondMetrics {
    private var start = 0L
    private var segment = 0
    private var previous: MetricsFrame? = null
    private var distance = 0.0
    private var paceSeconds = 0
    private var hrSum = 0L
    private var hrSeconds = 0
    private var hasFrames = false

    fun restart(elapsedSeconds: Long, segmentId: Int) {
        start = elapsedSeconds
        segment = segmentId
        previous = null
        clearWindow()
    }

    fun add(frame: MetricsFrame): List<RunMetricSample> {
        val second = frame.elapsedSeconds
        val prior = previous
        if (second < start || (prior != null && second <= prior.elapsedSeconds)) return emptyList()
        val result = mutableListOf<RunMetricSample>()
        if (second > start + 5) {
            if (hasFrames) result += flush(start + 5)
            // 跳过未采集窗口，不用插值补造曲线。
            start += ((second - start - 1) / 5) * 5
        }
        if (second > start) {
            hasFrames = true
            if (frame.isHeartRateFresh && frame.hasHeartRateSample) {
                frame.heartRate?.takeIf { it > 0 }?.let {
                    hrSum += it
                    hrSeconds++
                }
            }
            if (prior != null && second - prior.elapsedSeconds == 1L &&
                prior.validDistance() && frame.validDistance()
            ) {
                val delta = frame.distanceMeters - prior.distanceMeters
                if (delta >= 0 && delta.isFinite()) {
                    distance += delta
                    paceSeconds++
                }
            }
        }
        previous = frame
        if (second == start + 5) result += flush(second)
        return result
    }

    fun finish(elapsedSeconds: Long): List<RunMetricSample> =
        if (hasFrames && elapsedSeconds > start) listOf(flush(minOf(elapsedSeconds, start + 5)))
        else emptyList()

    private fun flush(end: Long): RunMetricSample {
        val point = RunMetricSample(
            startElapsedSeconds = start, endElapsedSeconds = end, segmentId = segment,
            averagePaceSecPerKm = if (paceSeconds.toLong() == end - start && distance > 0) {
                ((end - start) * 1000.0 / distance).takeIf { it.isFinite() }
            } else null,
            averageHeartRate = if (hrSeconds > 0) hrSum.toDouble() / hrSeconds else null,
            paceValidSeconds = paceSeconds, heartRateValidSeconds = hrSeconds,
        )
        start = end
        clearWindow()
        return point
    }

    private fun clearWindow() {
        distance = 0.0
        paceSeconds = 0
        hrSum = 0
        hrSeconds = 0
        hasFrames = false
    }

    private fun MetricsFrame.validDistance() =
        isDataFresh && hasDistanceSample && distanceMeters.isFinite() && distanceMeters >= 0
}
