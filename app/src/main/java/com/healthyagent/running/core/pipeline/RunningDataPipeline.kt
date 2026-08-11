package com.healthyagent.running.core.pipeline

import com.healthyagent.running.core.math.HrZoneCalculator
import com.healthyagent.running.core.math.MetricsCalculator
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold

/**
 * 实时跑步数据处理管道
 *
 * 将传感器原始样本流（1Hz+）加工为每秒一帧的 MetricsFrame。
 * 内置心率 5s 滑动平均平滑、配速滚动 10s 计算、数据新鲜度检测。
 */
class RunningDataPipeline(private val config: RunConfig) {

    companion object {
        const val HR_SMOOTH_WINDOW = 5       // 心率平滑窗口（秒）
        const val PACE_WINDOW_SEC = 10       // 配速计算窗口（秒）
        const val DATA_FRESH_TIMEOUT = 5_000L // 超过 5s 无新数据视为数据中断
        const val HISTORY_MAX = 60           // 最多保留 60 帧历史
    }

    private data class PipelineState(
        val lastHeartRate: Int? = null,
        val lastCadence: Int? = null,
        val totalDistanceMeters: Double = 0.0,
        val totalSteps: Long = 0,
        val lastHrSampleTime: Long = 0,
        val lastCadenceSampleTime: Long = 0,
        val recentFrames: List<MetricsFrame> = emptyList(),
        val elapsedSeconds: Long = 0,
    )

    /**
     * 从传感器样本流生成指标帧流
     *
     * 每秒产出一帧。心率做 5s 窗口滑动平均，配速基于过去 10s 的距离增量计算。
     */
    fun process(samples: Flow<SensorSample>): Flow<MetricsFrame> {
        val initialState = PipelineState()

        return samples
            .buffer(64)
            .groupBySecond()
            .runningFold(initialState) { state, (timestamp, secondSamples) ->
                val updated = updateState(state, secondSamples, timestamp)
                // 每处理完一秒的样本就产出一帧
                updated
            }
            .filter { it.recentFrames.isNotEmpty() }
            .map { state -> buildFrame(state) }
            .buffer(1)
    }

    /**
     * 将样本按秒分组 → (秒级时间戳, 该秒内所有样本)
     */
    private fun Flow<SensorSample>.groupBySecond(): Flow<Pair<Long, List<SensorSample>>> = flow {
        var currentSecond = -1L
        var acc = mutableListOf<SensorSample>()

        collect { sample ->
            val sec = sample.timestampMillis / 1000
            if (sec != currentSecond) {
                if (acc.isNotEmpty()) {
                    emit(currentSecond * 1000 to acc.toList())
                }
                currentSecond = sec
                acc = mutableListOf(sample)
            } else {
                acc.add(sample)
            }
        }
        if (acc.isNotEmpty()) {
            emit(currentSecond * 1000 to acc.toList())
        }
    }

    private fun updateState(
        state: PipelineState,
        samples: List<SensorSample>,
        timestamp: Long
    ): PipelineState {
        var hr = state.lastHeartRate
        var cadence = state.lastCadence
        var distance = state.totalDistanceMeters
        var steps = state.totalSteps
        var lastHrTime = state.lastHrSampleTime
        var lastCadenceTime = state.lastCadenceSampleTime

        for (s in samples) {
            when (s.type) {
                SampleType.HEART_RATE -> {
                    hr = s.value.toInt()
                    lastHrTime = timestamp
                }
                SampleType.CADENCE -> {
                    cadence = s.value.toInt()
                    lastCadenceTime = timestamp
                }
                SampleType.DISTANCE -> distance = s.value
                SampleType.STEPS -> steps = s.value.toLong()
                else -> { /* 忽略 */ }
            }
        }

        // 构建当前帧并追加到历史
        val newFrame = buildMetricsFrame(
            hr = hr,
            cadence = cadence,
            distance = distance,
            steps = steps,
            lastHrTime = lastHrTime,
            lastCadenceTime = lastCadenceTime,
            history = state.recentFrames,
            elapsedSeconds = state.elapsedSeconds + 1,
        )

        val updatedHistory = (state.recentFrames + newFrame).takeLast(HISTORY_MAX)

        return state.copy(
            lastHeartRate = hr,
            lastCadence = cadence,
            totalDistanceMeters = distance,
            totalSteps = steps,
            lastHrSampleTime = lastHrTime,
            lastCadenceSampleTime = lastCadenceTime,
            recentFrames = updatedHistory,
            elapsedSeconds = state.elapsedSeconds + 1,
        )
    }

    private fun buildFrame(state: PipelineState): MetricsFrame {
        return state.recentFrames.last()
    }

    /**
     * 基于最新样本和历史窗口构建一帧 MetricsFrame
     */
    private fun buildMetricsFrame(
        hr: Int?,
        cadence: Int?,
        distance: Double,
        steps: Long,
        lastHrTime: Long,
        lastCadenceTime: Long,
        history: List<MetricsFrame>,
        elapsedSeconds: Long,
    ): MetricsFrame {
        val now = System.currentTimeMillis()
        val isFresh = (now - lastHrTime) < DATA_FRESH_TIMEOUT

        // 心率 5s 滑动平均
        val smoothedHr = if (history.size >= 2 && hr != null) {
            val recentHr = (history.takeLast(HR_SMOOTH_WINDOW).mapNotNull { it.heartRate } + hr)
                .takeLast(HR_SMOOTH_WINDOW)
            MetricsCalculator.movingAverage(recentHr)
        } else hr

        // 心率区间
        val hrZone = if (smoothedHr != null) {
            HrZoneCalculator.zone(smoothedHr, config.maxHeartRate)
        } else HrZone.Z1

        // 配速：基于过去 PACE_WINDOW_SEC 的距离增量
        val pace = if (history.size >= 2) {
            val oldest = history.first()
            val deltaDist = distance - oldest.distanceMeters
            val deltaSec = (now - oldest.timestampMillis) / 1000.0
            if (deltaDist > 0 && deltaSec > 0) {
                MetricsCalculator.currentPace(deltaDist, deltaSec)
            } else null
        } else null

        // 步幅
        val strideLen = if (distance > 0 && steps > 0) {
            MetricsCalculator.strideLength(distance, steps, 0)
        } else null

        // 心效比
        val hrPaceRatio = if (smoothedHr != null && pace != null) {
            MetricsCalculator.hrPaceRatio(smoothedHr, pace)
        } else null

        return MetricsFrame(
            timestampMillis = now,
            elapsedSeconds = elapsedSeconds,
            heartRate = hr,
            heartRateSmoothed = smoothedHr,
            hrZone = hrZone,
            cadence = cadence,
            strideLengthCm = strideLen,
            currentPaceSecPerKm = pace,
            distanceMeters = distance,
            splitDistanceMeters = distance, // 默认 = 总距离，RunSessionManager 会覆盖
            totalSteps = steps,
            isDataFresh = isFresh,
            hrPaceRatio = hrPaceRatio,
        )
    }
}
