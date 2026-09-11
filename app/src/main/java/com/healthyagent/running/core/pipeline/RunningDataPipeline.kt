package com.healthyagent.running.core.pipeline

import com.healthyagent.running.core.math.HrZoneCalculator
import com.healthyagent.running.core.math.MetricsCalculator
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 实时跑步数据处理管道。
 *
 * 样本只更新归约状态，单调时钟驱动的 1Hz tick 是唯一出帧来源。这样即使传感器
 * 完全断流，UI、规则引擎和会话计时仍会按秒收到带有真实新鲜度的帧。
 */
class RunningDataPipeline(
    initialConfig: RunConfig,
    private val tickSource: Flow<Long>? = null,
    private val monotonicClockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var config = initialConfig

    /** 仅应在新跑步开始前替换，保证本次管道使用同一组用户配置。 */
    fun updateConfig(config: RunConfig) {
        this.config = config
    }

    /** 接收时刻只与本管道的时钟比较，不能混用含休眠差值的协调器时钟。 */
    fun isReceiptFresh(receivedAtMillis: Long?): Boolean = isFresh(receivedAtMillis, monotonicClockMillis())

    companion object {
        const val HR_SMOOTH_WINDOW = 5
        const val PACE_WINDOW_SEC = 10
        const val DATA_FRESH_TIMEOUT = 5_000L
        private const val TICK_INTERVAL_MILLIS = 1_000L
    }

    private data class TimedHeartRate(
        val receivedAtMillis: Long,
        val value: Int,
    )

    private data class DistancePoint(
        val receivedAtMillis: Long,
        val distanceMeters: Double,
    )

    private class PipelineState {
        var lastHeartRate: Int? = null
        var lastCadence: Int? = null
        var totalDistanceMeters: Double = 0.0
        var totalSteps: Long = 0
        var lastHeartRateReceiptMillis: Long? = null
        var lastCadenceReceiptMillis: Long? = null
        var lastDistanceReceiptMillis: Long? = null
        var firstTickMillis: Long? = null
        var elapsedSeconds: Long = 0
        val heartRates = ArrayDeque<TimedHeartRate>()
        val distancePoints = ArrayDeque<DistancePoint>()
        val lastSourceTimestampByType = mutableMapOf<SampleType, Long>()
    }

    private sealed interface PipelineEvent {
        data class Sample(
            val value: SensorSample,
            val receivedAtMillis: Long,
        ) : PipelineEvent

        data class Tick(
            val monotonicMillis: Long,
            val wallClockMillis: Long,
        ) : PipelineEvent
    }

    /**
     * 从传感器样本流生成指标帧流。
     *
     * [ticks] 传递单调时钟毫秒值。生产环境使用默认 1Hz 时钟，测试可传入
     * `MutableSharedFlow<Long>` 精确控制每次出帧。
     */
    fun process(
        samples: Flow<SensorSample>,
        ticks: Flow<Long> = tickSource ?: defaultTicks(),
        onOrderedDistanceReceived: (receivedAtMillis: Long) -> Unit = {},
        onOrderedHeartRateReceived: (sample: SensorSample, receivedAtMillis: Long) -> Unit = { _, _ -> },
    ): Flow<MetricsFrame> = flow {
        // 状态位于冷流内部，每次 collect 都从全新窗口开始。
        val state = PipelineState()
        events(samples, ticks).collect { event ->
            when (event) {
                is PipelineEvent.Sample -> acceptSample(
                    state = state,
                    sample = event.value,
                    receivedAtMillis = event.receivedAtMillis,
                    onOrderedDistanceReceived = onOrderedDistanceReceived,
                    onOrderedHeartRateReceived = onOrderedHeartRateReceived,
                )

                is PipelineEvent.Tick -> emit(
                    buildFrame(
                        state = state,
                        tickMillis = event.monotonicMillis,
                        timestampMillis = event.wallClockMillis,
                    ),
                )
            }
        }
    }.buffer(1)

    private fun events(
        samples: Flow<SensorSample>,
        ticks: Flow<Long>,
    ): Flow<PipelineEvent> = channelFlow {
        launch {
            samples.collect { sample ->
                send(
                    PipelineEvent.Sample(
                        value = sample,
                        receivedAtMillis = monotonicClockMillis(),
                    ),
                )
            }
        }
        launch {
            ticks.collect { tickMillis ->
                send(
                    PipelineEvent.Tick(
                        monotonicMillis = tickMillis,
                        wallClockMillis = wallClockMillis(),
                    ),
                )
            }
        }
    }

    private fun defaultTicks(): Flow<Long> = flow {
        while (currentCoroutineContext().isActive) {
            delay(TICK_INTERVAL_MILLIS)
            emit(monotonicClockMillis())
        }
    }

    private fun acceptSample(
        state: PipelineState,
        sample: SensorSample,
        receivedAtMillis: Long,
        onOrderedDistanceReceived: (receivedAtMillis: Long) -> Unit,
        onOrderedHeartRateReceived: (sample: SensorSample, receivedAtMillis: Long) -> Unit,
    ) {
        // 无效值不能建立接收基线，也不能推进源时间戳而挡住后续有效值。
        if (sample.type == SampleType.DISTANCE && (!sample.value.isFinite() || sample.value < 0)) return
        if (sample.type == SampleType.HEART_RATE &&
            (!sample.value.isFinite() || sample.value != sample.value.toInt().toDouble() ||
                sample.value.toInt() !in 1..300)
        ) return
        val previousSourceTimestamp = state.lastSourceTimestampByType[sample.type]
        if (previousSourceTimestamp != null && sample.timestampMillis < previousSourceTimestamp) return
        state.lastSourceTimestampByType[sample.type] = sample.timestampMillis

        when (sample.type) {
            SampleType.HEART_RATE -> {
                onOrderedHeartRateReceived(sample, receivedAtMillis)
                val heartRate = sample.value.toInt()
                state.lastHeartRate = heartRate
                state.lastHeartRateReceiptMillis = receivedAtMillis
                state.heartRates.addLast(TimedHeartRate(receivedAtMillis, heartRate))
                trimHeartRates(state, receivedAtMillis)
            }

            SampleType.CADENCE -> {
                state.lastCadence = sample.value.toInt()
                state.lastCadenceReceiptMillis = receivedAtMillis
            }

            SampleType.DISTANCE -> {
                acceptDistance(state, sample.value, receivedAtMillis)
                onOrderedDistanceReceived(receivedAtMillis)
            }
            SampleType.STEPS -> state.totalSteps = sample.value.toLong()
            else -> Unit
        }
    }

    private fun acceptDistance(
        state: PipelineState,
        distanceMeters: Double,
        receivedAtMillis: Long,
    ) {
        val previousReceipt = state.lastDistanceReceiptMillis
        val resumedAfterGap = previousReceipt != null &&
            receivedAtMillis - previousReceipt >= DATA_FRESH_TIMEOUT
        val distanceRestarted = distanceMeters < state.totalDistanceMeters
        if (resumedAfterGap || distanceRestarted) {
            state.distancePoints.clear()
        }

        state.totalDistanceMeters = distanceMeters
        state.lastDistanceReceiptMillis = receivedAtMillis

        if (state.distancePoints.lastOrNull()?.receivedAtMillis == receivedAtMillis) {
            state.distancePoints.removeLast()
        }
        state.distancePoints.addLast(DistancePoint(receivedAtMillis, distanceMeters))
        trimDistancePoints(state, receivedAtMillis)
    }

    private fun buildFrame(
        state: PipelineState,
        tickMillis: Long,
        timestampMillis: Long,
    ): MetricsFrame {
        val firstTick = state.firstTickMillis ?: tickMillis.also { state.firstTickMillis = it }
        val elapsedFromClock = ((tickMillis - firstTick).coerceAtLeast(0L) / 1_000L) + 1L
        state.elapsedSeconds = maxOf(state.elapsedSeconds, elapsedFromClock)

        val isDataFresh = isFresh(state.lastDistanceReceiptMillis, tickMillis)
        val isHeartRateFresh = isFresh(state.lastHeartRateReceiptMillis, tickMillis)
        val isCadenceFresh = isFresh(state.lastCadenceReceiptMillis, tickMillis)

        trimHeartRates(state, tickMillis)
        val heartRate = state.lastHeartRate.takeIf { isHeartRateFresh }
        val smoothedHeartRate = if (heartRate != null) {
            MetricsCalculator.movingAverage(
                state.heartRates.takeLast(HR_SMOOTH_WINDOW).map(TimedHeartRate::value),
            )
        } else {
            null
        }
        val heartRateZone = smoothedHeartRate?.let { HrZoneCalculator.zone(it, config.maxHeartRate) }
            ?: HrZone.Z1
        val cadence = state.lastCadence.takeIf { isCadenceFresh }
        val pace = calculatePace(state, tickMillis, isDataFresh)

        val strideLength = if (state.totalDistanceMeters > 0 && state.totalSteps > 0) {
            MetricsCalculator.strideLength(state.totalDistanceMeters, state.totalSteps, 0)
        } else {
            null
        }
        val heartRatePaceRatio = if (smoothedHeartRate != null && pace != null) {
            MetricsCalculator.hrPaceRatio(smoothedHeartRate, pace)
        } else {
            null
        }

        return MetricsFrame(
            timestampMillis = timestampMillis,
            elapsedSeconds = state.elapsedSeconds,
            heartRate = heartRate,
            heartRateSmoothed = smoothedHeartRate,
            hrZone = heartRateZone,
            cadence = cadence,
            strideLengthCm = strideLength,
            currentPaceSecPerKm = pace,
            distanceMeters = state.totalDistanceMeters,
            splitDistanceMeters = state.totalDistanceMeters,
            totalSteps = state.totalSteps,
            isDataFresh = isDataFresh,
            isHeartRateFresh = isHeartRateFresh,
            hasDistanceSample = state.lastDistanceReceiptMillis != null,
            hasHeartRateSample = state.lastHeartRateReceiptMillis != null,
            hrPaceRatio = heartRatePaceRatio,
        )
    }

    private fun calculatePace(
        state: PipelineState,
        tickMillis: Long,
        isDataFresh: Boolean,
    ): Double? {
        if (!isDataFresh) return null

        val visiblePoints = state.distancePoints.filter { point ->
            tickMillis - point.receivedAtMillis in 0..(PACE_WINDOW_SEC * 1_000L)
        }
        if (visiblePoints.size < 2) return null

        val oldest = visiblePoints.first()
        val newest = visiblePoints.last()
        val deltaDistance = newest.distanceMeters - oldest.distanceMeters
        val deltaSeconds = (newest.receivedAtMillis - oldest.receivedAtMillis) / 1_000.0
        return MetricsCalculator.currentPace(deltaDistance, deltaSeconds)
    }

    private fun trimHeartRates(state: PipelineState, nowMillis: Long) {
        while (state.heartRates.isNotEmpty() &&
            nowMillis - state.heartRates.first().receivedAtMillis >= DATA_FRESH_TIMEOUT
        ) {
            state.heartRates.removeFirst()
        }
    }

    private fun trimDistancePoints(state: PipelineState, nowMillis: Long) {
        val windowMillis = PACE_WINDOW_SEC * 1_000L
        while (state.distancePoints.isNotEmpty() &&
            nowMillis - state.distancePoints.first().receivedAtMillis > windowMillis
        ) {
            state.distancePoints.removeFirst()
        }
    }

    private fun isFresh(lastReceiptMillis: Long?, nowMillis: Long): Boolean {
        if (lastReceiptMillis == null) return false
        return nowMillis - lastReceiptMillis in 0 until DATA_FRESH_TIMEOUT
    }
}
