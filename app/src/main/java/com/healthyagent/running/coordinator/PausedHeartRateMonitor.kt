package com.healthyagent.running.coordinator

import com.healthyagent.running.core.math.HrZoneCalculator
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive

data class PausedHeartRateState(
    val heartRate: Int?,
    val hrZone: HrZone?,
)

data class PausedHeartRateSeed(
    val heartRate: Int?,
    val hrZone: HrZone?,
    val receivedAtMillis: Long?,
)

class PausedHeartRateMonitor(
    private val tickSource: Flow<Long>? = null,
    private val monotonicClockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()
    private var activeRunningGeneration: Long? = null
    private var acceptsRunningSamples = false
    private var runningReceipt: HeartRateReceipt? = null

    fun beginRunning(generation: Long) {
        synchronized(lock) {
            activeRunningGeneration = generation
            acceptsRunningSamples = true
            runningReceipt = null
        }
    }

    fun recordRunningSample(
        generation: Long,
        sample: SensorSample,
        receivedAtMillis: Long,
    ) {
        val heartRate = sample.heartRateOrNull() ?: return
        synchronized(lock) {
            if (activeRunningGeneration != generation || !acceptsRunningSamples) return
            runningReceipt = HeartRateReceipt(heartRate, receivedAtMillis)
        }
    }

    fun freezeForPause(generation: Long, maxHeartRate: Int): PausedHeartRateSeed = synchronized(lock) {
        if (activeRunningGeneration != generation) return@synchronized PausedHeartRateSeed(null, null, null)
        acceptsRunningSamples = false
        runningReceipt?.toSeed(maxHeartRate) ?: PausedHeartRateSeed(null, null, null)
    }

    fun invalidateRunning() {
        synchronized(lock) {
            activeRunningGeneration = null
            acceptsRunningSamples = false
            runningReceipt = null
        }
    }

    fun observe(
        samples: Flow<SensorSample>,
        maxHeartRate: Int,
        seed: PausedHeartRateSeed,
        ticks: Flow<Long> = tickSource ?: defaultTicks(),
    ): Flow<PausedHeartRateState> = flow {
        val completeSeed = seed.heartRate != null && seed.hrZone != null && seed.receivedAtMillis != null
        val observedAtMillis = monotonicClockMillis()
        var receipt = seed.toReceiptOrNull(maxHeartRate, observedAtMillis)
        var lastPublished = PausedHeartRateState(null, null)

        if (completeSeed && receipt != null) {
            val initial = requireNotNull(receipt).toStateOrNull(maxHeartRate, observedAtMillis)
            emit(initial)
            lastPublished = initial
        }

        merge(
            samples.map { sample -> ObserveEvent.Sample(sample, monotonicClockMillis()) },
            ticks.map(ObserveEvent::Tick),
        ).collect { event ->
            when (event) {
                is ObserveEvent.Sample -> {
                    val heartRate = event.sample.heartRateOrNull() ?: return@collect
                    receipt = HeartRateReceipt(heartRate, event.receivedAtMillis)
                }

                is ObserveEvent.Tick -> Unit
            }

            val nowMillis = when (event) {
                is ObserveEvent.Sample -> event.receivedAtMillis
                is ObserveEvent.Tick -> event.monotonicMillis
            }
            val next = receipt?.toStateOrNull(maxHeartRate, nowMillis) ?: PausedHeartRateState(null, null)
            if (event is ObserveEvent.Sample || next != lastPublished) {
                emit(next)
                lastPublished = next
            }
        }
    }

    private fun defaultTicks(): Flow<Long> = flow {
        while (currentCoroutineContext().isActive) {
            delay(TICK_INTERVAL_MILLIS)
            emit(monotonicClockMillis())
        }
    }

    private data class HeartRateReceipt(
        val heartRate: Int,
        val receivedAtMillis: Long,
    ) {
        fun toSeed(maxHeartRate: Int) = PausedHeartRateSeed(
            heartRate = heartRate,
            hrZone = HrZoneCalculator.zone(heartRate, maxHeartRate),
            receivedAtMillis = receivedAtMillis,
        )

        fun toStateOrNull(maxHeartRate: Int, nowMillis: Long): PausedHeartRateState {
            return if (nowMillis - receivedAtMillis in 0 until FRESH_TIMEOUT_MILLIS) {
                PausedHeartRateState(heartRate, HrZoneCalculator.zone(heartRate, maxHeartRate))
            } else {
                PausedHeartRateState(null, null)
            }
        }
    }

    private sealed interface ObserveEvent {
        data class Sample(
            val sample: SensorSample,
            val receivedAtMillis: Long,
        ) : ObserveEvent

        data class Tick(val monotonicMillis: Long) : ObserveEvent
    }

    private fun PausedHeartRateSeed.toReceiptOrNull(
        maxHeartRate: Int,
        nowMillis: Long,
    ): HeartRateReceipt? {
        val value = heartRate ?: return null
        val zone = hrZone ?: return null
        val receivedAt = receivedAtMillis ?: return null
        if (value !in MIN_HEART_RATE..MAX_HEART_RATE || receivedAt < 0L || receivedAt > nowMillis) return null
        if (maxHeartRate <= 0 || zone != HrZoneCalculator.zone(value, maxHeartRate)) return null
        return HeartRateReceipt(value, receivedAt)
    }

    private fun SensorSample.heartRateOrNull(): Int? {
        if (type != SampleType.HEART_RATE || !value.isFinite()) return null
        val heartRate = value.toInt()
        return heartRate.takeIf { value == heartRate.toDouble() && heartRate in MIN_HEART_RATE..MAX_HEART_RATE }
    }

    private companion object {
        const val FRESH_TIMEOUT_MILLIS = 5_000L
        const val TICK_INTERVAL_MILLIS = 1_000L
        const val MIN_HEART_RATE = 1
        const val MAX_HEART_RATE = 300
    }
}
